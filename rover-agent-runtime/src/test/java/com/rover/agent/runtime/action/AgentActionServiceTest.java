package com.rover.agent.runtime.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import com.rover.agent.core.model.ActionStatus;
import com.rover.agent.core.model.AgentAction;
import com.rover.agent.core.model.Session;
import com.rover.agent.core.model.SessionStatus;
import com.rover.agent.core.model.WeightRequestUnit;
import com.rover.agent.core.port.RouteChangePreview;
import com.rover.agent.core.port.RouteChangeResult;
import com.rover.agent.core.port.RouteControlException;
import com.rover.agent.core.port.RouteControlPort;
import com.rover.agent.core.port.RouteControlRoute;
import com.rover.agent.core.port.RouteControlState;
import com.rover.agent.core.port.RouteControlTarget;
import com.rover.agent.core.port.RouteOperation;
import com.rover.agent.core.port.RouteOperationStatus;
import com.rover.agent.core.port.SnapshotUnavailableException;
import com.rover.agent.runtime.repository.InMemoryAgentActionRepository;
import com.rover.agent.runtime.repository.InMemoryAgentSessionRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 受控变更闭环的验收：一个 Action，从提议到补偿走完整条链。
 *
 * <p>用例逐条对应生产上真会遇到的八种情形，而不是「能不能跑通」：
 * 不批准绝不写、revision 过期不覆盖别人、双击只执行一次、超时用同一个幂等号回查、
 * 回读不一致必须报失败、回滚是补偿而不是整表退回、别人的变更动不了。
 *
 * <p>测试用的是假网关（可注入四种失败）与内存存储：这里要验证的是<b>决策</b>——
 * 在哪一步停、停成什么状态、发了几次写请求——而不是 HTTP 本身。
 */
class AgentActionServiceTest {

    private static final String OWNER = "alice";
    private static final String SESSION = "s-order";
    private static final int PROPOSED_REVISION = 17;

    private FakeRouteControl gateway;
    private InMemoryAgentActionRepository repository;
    private InMemoryAgentSessionRepository sessions;
    private AgentActionService service;

    @BeforeEach
    void setUp() {
        gateway = new FakeRouteControl();
        repository = new InMemoryAgentActionRepository(50);
        sessions = new InMemoryAgentSessionRepository(10);
        sessions.save(new Session(SESSION, OWNER, "订单灰度", null, SessionStatus.ACTIVE, 1L, 1L, List.of()));
        service = new AgentActionService(repository, sessions, gateway,
                new RouteWeightActionExecutor(gateway, repository));
    }

    // ---------------------------------------------------------------- 1. 正常执行

    @Test void approvedDeterministicExecutionStillWorksWhileProviderCircuitIsOpen() {
        var isolation=new com.rover.agent.runtime.llm.ProviderIsolation(new com.rover.agent.runtime.llm.ProviderIsolation.Policy(1,30,1));
        isolation.acquire("model").failure(new com.rover.agent.runtime.llm.ModelProviderFailure(503,0));
        assertThrows(com.rover.agent.runtime.llm.ProviderIsolation.Rejected.class,()->isolation.acquire("model"));
        AgentAction pending=propose(20);
        AgentAction result=service.approve(pending.actionId(),OWNER);
        assertEquals(ActionStatus.SUCCESS,result.status());
    }

    @Test
    void anotherAuthorizedApproverCanApproveWithoutSharingPrivateChats() {
        gateway.resourceRef = planResource();
        com.rover.agent.core.port.AgentAccess access = principal -> "reviewer".equals(principal)
                ? Set.of(com.rover.agent.core.port.AgentAccess.Permission.READ, com.rover.agent.core.port.AgentAccess.Permission.APPROVE)
                : Set.of(com.rover.agent.core.port.AgentAccess.Permission.READ, com.rover.agent.core.port.AgentAccess.Permission.PROPOSE);
        service = new AgentActionService(repository, sessions, gateway, new RouteWeightActionExecutor(gateway, repository, access), access);
        AgentAction pending = propose(20);
        assertThrows(com.rover.agent.core.port.AgentAccess.Denied.class, () -> service.approve(pending.actionId(), OWNER));
        assertEquals(1, service.pendingApprovals("reviewer").size());
        AgentAction applied = service.approve(pending.actionId(), "reviewer");
        assertEquals(ActionStatus.SUCCESS, applied.status());
        assertEquals("reviewer", applied.approvedBy());
    }

    @Test
    void permissionRevokedDuringPreviewPreventsTheActualWrite() {
        gateway.resourceRef = planResource();
        AtomicBoolean allowed = new AtomicBoolean(true);
        com.rover.agent.core.port.AgentAccess access = principal -> allowed.get()
                ? Set.of(com.rover.agent.core.port.AgentAccess.Permission.values()) : Set.of();
        RouteControlPort guarded = spy(gateway);
        service = new AgentActionService(repository, sessions, guarded, new RouteWeightActionExecutor(guarded, repository, access), access);
        AgentAction pending = propose(20);
        doAnswer(invocation -> { allowed.set(false); return invocation.callRealMethod(); })
                .when(guarded).preview(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt());
        AgentAction stopped = service.approve(pending.actionId(), OWNER);
        assertEquals(ActionStatus.PRECONDITION_FAILED, stopped.status());
        org.mockito.Mockito.verify(guarded, org.mockito.Mockito.never()).adjustTargetWeight(anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(), anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void proposeThenApproveThenVerify() {
        AgentAction pending = propose(20);

        assertEquals(ActionStatus.PENDING_APPROVAL, pending.status());
        assertEquals(5, pending.beforeWeight());
        assertEquals(20, pending.desiredWeight());
        assertEquals(PROPOSED_REVISION, pending.expectedRevision());
        assertEquals("order-service@v2", pending.targetLabel());
        assertEquals("/api/order", pending.businessPrefix());
        assertEquals(OWNER, pending.requestedBy(), "提议人来自会话归属，不接受前端提交");
        assertEquals(0, gateway.writes, "提议阶段绝不能产生任何写请求");

        AgentAction done = service.approve(pending.actionId(), OWNER);

        assertEquals(ActionStatus.SUCCESS, done.status());
        assertNull(done.errorMessage());
        assertEquals(18, done.appliedRevision(), "回读确认后应记下网关生效的版本");
        assertNotNull(done.applyOperationId());
        assertNotNull(done.approvedBy());
        assertTrue(done.approvedAtMillis() > 0);
        assertEquals(1, gateway.writes);
        assertEquals(20, gateway.weightOf("v2"), "回读确认的目标权重就是真实权重");
        assertEquals(PROPOSED_REVISION, gateway.lastExpectedRevision, "提交必须带上提议时读到的版本");
        assertEquals(done.applyOperationId(), gateway.lastOperationId);
        assertTrue(gateway.queriedOperations.isEmpty(), "正常路径不该走回查");
    }

    // ---------------------------------------------------------------- 2. 不批准绝不写

    @Test
    void rejectedProposalNeverWrites() {
        AgentAction pending = propose(20);
        AgentAction rejected = service.reject(pending.actionId(), OWNER);

        assertEquals(ActionStatus.REJECTED, rejected.status());
        assertEquals(0, gateway.writes, "拒绝之后网关状态必须与提议前一致");
        assertEquals(5, gateway.weightOf("v2"));
    }

    @Test
    void proposalsAccumulateWithoutEverTouchingTheGateway() {
        propose(20);
        propose(0);

        assertEquals(2, repository.bySession(SESSION).size(), "两次提议都留下了待审批记录");
        assertEquals(2, repository.bySession(SESSION).stream()
                .filter(action -> action.status() == ActionStatus.PENDING_APPROVAL).count());
        assertEquals(0, gateway.writes, "记录再多也只是建议，网关一个字节都没变");
        assertEquals(5, gateway.weightOf("v2"));
    }

    // ---------------------------------------------------------------- 3. revision 过期

    @Test
    void staleRevisionFailsPreconditionWithoutWrite() {
        AgentAction pending = propose(20);
        gateway.revision = 19; // 有人在审批期间改了路由

        AgentAction failed = service.approve(pending.actionId(), OWNER);

        assertEquals(ActionStatus.PRECONDITION_FAILED, failed.status());
        assertTrue(failed.errorMessage().contains("revision 17 → 19"), failed.errorMessage());
        assertEquals(0, gateway.writes, "预检没过就绝不能提交，否则会覆盖别人的修改");
    }

    /**
     * 目标消失的兜底分支：正常情况下版本号会先一步不一致（由上一个用例覆盖），
     * 这里刻意在版本号不变的前提下摘掉目标，确认执行器仍然拒绝提交而不是「找不到就新建一个」。
     */
    @Test
    void targetRemovedFailsPrecondition() {
        AgentAction pending = propose(20);
        gateway.removeTarget("v2");

        AgentAction failed = service.approve(pending.actionId(), OWNER);

        assertEquals(ActionStatus.PRECONDITION_FAILED, failed.status());
        assertTrue(failed.errorMessage().contains("order-service@v2"), failed.errorMessage());
        assertEquals(0, gateway.writes);
    }

    // ---------------------------------------------------------------- 4. 双击批准

    @Test
    void doubleApproveExecutesOnce() {
        AgentAction pending = propose(20);

        service.approve(pending.actionId(), OWNER);
        ActionRequestException second = assertThrows(ActionRequestException.class,
                () -> service.approve(pending.actionId(), OWNER));

        assertEquals(ActionRequestException.Code.CONFLICT, second.code());
        assertEquals(1, gateway.writes, "状态 CAS 只允许一次真实执行");
        assertEquals(18, gateway.revision);
    }

    // ---------------------------------------------------------------- 5. 响应超时

    @Test
    void timeoutConfirmsWithSameOperationIdInsteadOfRetrying() {
        AgentAction pending = propose(20);
        // 网关其实改成功了，只是响应丢在路上
        gateway.timeoutAfterApply = true;

        AgentAction done = service.approve(pending.actionId(), OWNER);

        assertEquals(ActionStatus.SUCCESS, done.status());
        assertEquals(1, gateway.writes, "绝不能换一个幂等号重试——那就是同一个变更执行两遍");
        assertEquals(List.of(done.applyOperationId()), gateway.queriedOperations);
        assertEquals(20, gateway.weightOf("v2"));
    }

    @Test
    void timeoutWithoutRecordStaysUncertainAndResolvable() {
        AgentAction pending = propose(20);
        gateway.timeoutWithoutApply = true;

        AgentAction uncertain = service.approve(pending.actionId(), OWNER);

        assertEquals(ActionStatus.UNCERTAIN, uncertain.status());
        assertTrue(uncertain.errorMessage().contains("无法确认"), uncertain.errorMessage());
        assertEquals(1, gateway.writes);
        assertNull(uncertain.appliedRevision(), "结果未知时不能谎称有个生效版本");

        // 稍后回查：这次网关的记账说已经生效 → 继续验证并收口
        gateway.operations.put(uncertain.applyOperationId(), RouteOperationStatus.APPLIED);
        gateway.applyWeight("v2", 20);
        AgentAction resolved = service.resolve(uncertain.actionId(), OWNER);

        assertEquals(ActionStatus.SUCCESS, resolved.status());
        assertEquals(1, gateway.writes, "确认结果不是重新执行");
        assertEquals(20, gateway.weightOf("v2"));
    }

    @Test
    void expiredReceiptKeepsOriginalOperationAndNeverRetries() {
        gateway.timeoutWithoutApply = true;
        AgentAction uncertain = service.approve(propose(20).actionId(), OWNER);
        gateway.operations.put(uncertain.applyOperationId(), RouteOperationStatus.EXPIRED);
        AgentAction resolved = service.resolve(uncertain.actionId(), OWNER);
        assertEquals(ActionStatus.UNCERTAIN, resolved.status());
        assertEquals(uncertain.applyOperationId(), resolved.applyOperationId());
        assertTrue(resolved.errorMessage().contains("过期"));
        assertEquals(1, gateway.writes);
    }

    // ---------------------------------------------------------------- 6. 回读不一致

    @Test
    void delayedResolveCannotOverwriteCompletedRollback() throws Exception {
        gateway.timeoutWithoutApply = true;
        AgentAction uncertain = service.approve(propose(20).actionId(), OWNER);
        gateway.timeoutWithoutApply = false;
        gateway.applyWeight("v2", 20);
        gateway.operations.put(uncertain.applyOperationId(), RouteOperationStatus.APPLIED);

        FakeRouteControl observedGateway = spy(gateway);
        CountDownLatch queryEntered = new CountDownLatch(1);
        CountDownLatch releaseQuery = new CountDownLatch(1);
        AtomicInteger queries = new AtomicInteger();
        doAnswer(invocation -> {
            if (queries.getAndIncrement() == 0) {
                queryEntered.countDown();
                assertTrue(releaseQuery.await(5, TimeUnit.SECONDS));
            }
            return invocation.callRealMethod();
        }).when(observedGateway).operation(anyString());
        AgentActionService concurrent = new AgentActionService(repository, sessions, observedGateway,
                new RouteWeightActionExecutor(observedGateway, repository));
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            var delayed = worker.submit(() -> concurrent.resolve(uncertain.actionId(), OWNER));
            assertTrue(queryEntered.await(5, TimeUnit.SECONDS), "慢回查必须已经读到旧快照");
            AgentAction resolved = concurrent.resolve(uncertain.actionId(), OWNER);
            AgentAction rolledBack = concurrent.rollback(resolved.actionId(), OWNER);
            assertEquals(ActionStatus.ROLLED_BACK, rolledBack.status());
            releaseQuery.countDown();

            AgentAction lateResult = delayed.get(5, TimeUnit.SECONDS);
            AgentAction stored = repository.find(uncertain.actionId()).orElseThrow();
            assertEquals(ActionStatus.ROLLED_BACK, lateResult.status());
            assertEquals(rolledBack, stored, "迟到回查不能覆盖回滚状态或回滚操作号");
            assertNotNull(stored.rollbackOperationId());
            assertEquals(2, observedGateway.writes, "一条执行与一条补偿，不因回查产生额外写入");
        } finally {
            releaseQuery.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void persistenceFailureAfterSubmissionPreservesOperationForReconciliation() {
        InMemoryAgentActionRepository interrupted = spy(repository);
        AtomicBoolean failedOnce = new AtomicBoolean();
        doAnswer(invocation -> {
            AgentAction candidate = invocation.getArgument(0);
            if (candidate.status() == ActionStatus.VERIFYING && failedOnce.compareAndSet(false, true)) {
                throw new IllegalStateException("模拟：网关提交后，本地验证状态落库失败一次");
            }
            return invocation.callRealMethod();
        }).when(interrupted).save(any(AgentAction.class));
        AgentActionService resilient = new AgentActionService(interrupted, sessions, gateway,
                new RouteWeightActionExecutor(gateway, interrupted));

        AgentAction uncertain = resilient.approve(propose(20).actionId(), OWNER);

        assertTrue(failedOnce.get());
        assertEquals(ActionStatus.UNCERTAIN, uncertain.status());
        assertNotNull(uncertain.applyOperationId(), "异常兜底不能用提交前快照抹掉已持久化的操作号");
        assertEquals(gateway.lastOperationId, uncertain.applyOperationId());
        AgentAction resolved = resilient.resolve(uncertain.actionId(), OWNER);
        assertEquals(ActionStatus.SUCCESS, resolved.status());
        assertEquals(1, gateway.writes, "恢复只能按原号对账，不能重新提交");
    }

    @Test
    void rollbackPersistenceFailurePreservesBothOperationIds() {
        AgentAction applied = service.approve(propose(20).actionId(), OWNER);
        InMemoryAgentActionRepository interrupted = spy(repository);
        AtomicBoolean failedOnce = new AtomicBoolean();
        doAnswer(invocation -> {
            AgentAction candidate = invocation.getArgument(0);
            if (candidate.status() == ActionStatus.ROLLING_BACK && candidate.rollbackOperationId() != null
                    && candidate.appliedRevision() > applied.appliedRevision()
                    && failedOnce.compareAndSet(false, true)) {
                throw new IllegalStateException("模拟：补偿已提交，本地验证状态落库失败一次");
            }
            return invocation.callRealMethod();
        }).when(interrupted).save(any(AgentAction.class));
        AgentActionService resilient = new AgentActionService(interrupted, sessions, gateway,
                new RouteWeightActionExecutor(gateway, interrupted));

        AgentAction uncertain = resilient.rollback(applied.actionId(), OWNER);

        assertTrue(failedOnce.get());
        assertEquals(ActionStatus.UNCERTAIN, uncertain.status());
        assertEquals(applied.applyOperationId(), uncertain.applyOperationId());
        assertEquals(gateway.lastOperationId, uncertain.rollbackOperationId());
        assertEquals(ActionStatus.ROLLED_BACK, resilient.resolve(applied.actionId(), OWNER).status());
        assertEquals(2, gateway.writes, "补偿结果确认也不能重新提交");
    }

    @Test
    void lateResolveExceptionCannotDemoteANewerRollback() {
        gateway.timeoutWithoutApply = true;
        AgentAction uncertain = service.approve(propose(20).actionId(), OWNER);
        RouteWeightActionExecutor delayed = spy(new RouteWeightActionExecutor(gateway, repository));
        doAnswer(invocation -> {
            AgentAction resolved = repository.transition(uncertain.actionId(), ActionStatus.UNCERTAIN,
                    current -> new ActionDraft(current).move(ActionStatus.SUCCESS, null).toAction(2L)).orElseThrow();
            repository.transition(resolved.actionId(), ActionStatus.SUCCESS,
                    current -> new ActionDraft(current).rollbackOperation("newer-rollback")
                            .move(ActionStatus.ROLLING_BACK, null).toAction(3L)).orElseThrow();
            throw new IllegalStateException("旧回查发生意外异常");
        }).when(delayed).resolve(any(AgentAction.class));
        AgentActionService concurrent = new AgentActionService(repository, sessions, gateway, delayed);

        AgentAction latest = concurrent.resolve(uncertain.actionId(), OWNER);

        assertEquals(ActionStatus.ROLLING_BACK, latest.status());
        assertEquals("newer-rollback", latest.rollbackOperationId());
        assertEquals(latest, repository.find(uncertain.actionId()).orElseThrow());
    }

    @Test
    void readBackMismatchFailsInsteadOfClaimingSuccess() {
        AgentAction pending = propose(20);
        // 网关的响应说成功、版本也涨了，但权重实际没改（伪造「假成功」）
        gateway.applyOnWrite = false;

        AgentAction failed = service.approve(pending.actionId(), OWNER);

        assertEquals(ActionStatus.FAILED, failed.status());
        assertTrue(failed.errorMessage().contains("回读确认不一致"), failed.errorMessage());
        assertEquals(5, gateway.weightOf("v2"));
    }

    @Test
    void unreadableReadBackIsUncertainNotFailure() {
        AgentAction pending = propose(20);
        gateway.unreachableAfterWrite = true;

        AgentAction uncertain = service.approve(pending.actionId(), OWNER);

        assertEquals(ActionStatus.UNCERTAIN, uncertain.status());
        assertEquals(20, gateway.weightOf("v2"), "变更其实已经生效；状态必须如实说「不知道」而不是「失败」");
    }

    // ---------------------------------------------------------------- 7. 回滚

    @Test
    void rollbackCompensatesWithCurrentRevisionAndVerifies() {
        AgentAction done = service.approve(propose(20).actionId(), OWNER);
        assertEquals(18, gateway.revision);

        AgentAction rolledBack = service.rollback(done.actionId(), OWNER);

        assertEquals(ActionStatus.ROLLED_BACK, rolledBack.status());
        assertNull(rolledBack.errorMessage());
        assertEquals(5, gateway.weightOf("v2"), "补偿要把权重改回变更前的值");
        assertEquals(19, gateway.revision, "补偿也是一次写操作，产生新版本而不是覆盖历史");
        assertEquals(2, gateway.writes);
        assertEquals(rolledBack.rollbackOperationId(), gateway.lastOperationId);
        assertEquals(18, gateway.lastExpectedRevision, "回滚用当前版本，而不是提议时的旧版本");
    }

    @Test
    void rollbackRefusesWhenTargetWasChangedBySomeoneElse() {
        AgentAction done = service.approve(propose(20).actionId(), OWNER);
        gateway.applyWeight("v2", 30);

        AgentAction refused = service.rollback(done.actionId(), OWNER);

        assertEquals(ActionStatus.ROLLBACK_PRECONDITION_FAILED, refused.status());
        assertEquals(30, gateway.weightOf("v2"), "别人后来写成的 30 不能被补偿盖掉");
        assertTrue(refused.errorMessage().contains("30"), refused.errorMessage());
        assertTrue(refused.errorMessage().contains("20"), refused.errorMessage());
        assertEquals(1, gateway.writes, "预检没过时不能再写 Gateway");
        assertNull(refused.rollbackOperationId(), "没提交的补偿不能留下操作号");
    }

    @Test
    void rollbackTreatsWeightAlreadyRestoredAsRolledBack() {
        AgentAction done = service.approve(propose(20).actionId(), OWNER);
        gateway.applyWeight("v2", 5);

        AgentAction rolled = service.rollback(done.actionId(), OWNER);

        assertEquals(ActionStatus.ROLLED_BACK, rolled.status());
        assertEquals(5, gateway.weightOf("v2"));
        assertEquals(1, gateway.writes, "已经回到变更前的值时不再写一次");
    }

    @Test
    void rollbackIsRefusedBeforeSuccess() {
        AgentAction pending = propose(20);

        ActionRequestException refused = assertThrows(ActionRequestException.class,
                () -> service.rollback(pending.actionId(), OWNER));

        assertEquals(ActionRequestException.Code.CONFLICT, refused.code());
        assertEquals(0, gateway.writes);
    }

    @Test
    void failedRollbackKeepsTheChangeRollbackable() {
        AgentAction done = service.approve(propose(20).actionId(), OWNER);
        gateway.removeTarget("v2"); // 回滚时目标已经不在了

        AgentAction stillApplied = service.rollback(done.actionId(), OWNER);

        assertEquals(ActionStatus.SUCCESS, stillApplied.status(), "回滚没做成不等于变更没生效");
        assertTrue(stillApplied.errorMessage().contains("回滚未提交"), stillApplied.errorMessage());
    }

    // ---------------------------------------------------------------- 8. 权限与校验

    @Test
    void strangerCannotApproveOrRollback() {
        AgentAction pending = propose(20);

        assertThrows(ActionRequestException.class, () -> service.approve(pending.actionId(), "bob"));
        assertEquals(0, gateway.writes);

        AgentAction done = service.approve(pending.actionId(), OWNER);
        ActionRequestException denied = assertThrows(ActionRequestException.class,
                () -> service.rollback(done.actionId(), "bob"));
        assertEquals(ActionRequestException.Code.NOT_FOUND, denied.code());
        assertEquals(1, gateway.writes);
    }

    @Test
    void otherUsersCannotEvenSeeTheAction() {
        AgentAction pending = propose(20);
        assertTrue(service.find(pending.actionId(), "bob").isEmpty());
        assertTrue(service.bySession(SESSION, "bob").isEmpty());
    }

    @Test
    void proposalsAreRejectedWithAReasonInsteadOfGuessing() {
        assertTrue(propose("/api/order", "v2", null).contains("数值"), "数值必须显式给出");
        assertTrue(propose("", "v2", 20).contains("路由"));
        assertTrue(propose("/api/order", "", 20).contains("版本"));
        assertTrue(propose("/api/order", "v2", 20_001).contains("权重"));
        assertTrue(propose("/api/order", "v9", 20).contains("v9"));
        assertTrue(propose("/api/order", "v2", 5).contains("已经是 5"));
        assertTrue(propose("/api/other", "v2", 20).contains("没有匹配"));
        assertTrue(service.propose("unknown-session", "t1", "i1", "/api/order", "v2", 20,
                WeightRequestUnit.RAW_WEIGHT).reason()
                .contains("会话"), "会话不存在时不能登记变更，否则它没有归属、谁也不能处置");
        assertTrue(repository.bySession(SESSION).isEmpty(), "被拒绝的提议不该留下任何记录");
        assertEquals(0, gateway.writes);
    }

    /**
     * 数字没有单位时只能澄清，不能替用户选。
     *
     * <p>「20」在 95/5 的路由上可能是权重 20，也可能是 20% 流量（≈2000）：差两个数量级。
     * 这类请求必须回到对话里问清楚，而不是让代码挑一个看起来更可能的读法。
     */
    @Test
    void requestWithoutUnitMustBeClarifiedNotGuessed() {
        ActionProposal proposal = service.propose(SESSION, "t1", "i1", "/api/order", "v2", 20, null);

        assertFalse(proposal.created(), "语义不明时不生成变更");
        assertTrue(proposal.clarificationRequired(), "要给出「需要澄清」而不是「失败」，模型才知道该去问用户");
        assertTrue(proposal.reason().contains("权重值"), proposal.reason());
        assertTrue(proposal.reason().contains("流量占比"), proposal.reason());
        assertTrue(repository.bySession(SESSION).isEmpty(), "澄清请求不该留下待审批卡");
        assertEquals(0, gateway.writes);
    }

    /** 百分比换算只能由代码完成：模型给「20%」，写进路由表的权重由 Java 按整条路由的分布算出来。 */
    @Test
    void trafficPercentIsResolvedByCodeUsingWholeRouteWeights() {
        AgentAction action = propose(20, WeightRequestUnit.TRAFFIC_PERCENT);

        // 同路由其他版本合计 95，占 20% 需要 w' = 95 * 20 / 80 ≈ 24
        assertEquals(24, action.desiredWeight());
        assertEquals(20, action.requestedValue(), "用户要的百分比要留着，审批卡才能说明批的到底是什么");
        assertEquals(5.0, action.beforeTrafficPercent(), 0.05);
        assertEquals(20.2, action.desiredTrafficPercent(), 0.05);
    }

    /** 100% 无法通过只改一个版本达成：要澄清，而不是写一个近似值下去。 */
    @Test
    void hundredPercentIsClarifiedInsteadOfApproximated() {
        ActionProposal proposal = service.propose(SESSION, "t1", "i1", "/api/order", "v2", 100,
                WeightRequestUnit.TRAFFIC_PERCENT);

        assertFalse(proposal.created());
        assertTrue(proposal.clarificationRequired(), proposal.reason());
        assertTrue(proposal.reason().contains("其他版本"), proposal.reason());
        assertEquals(0, gateway.writes);
    }

    /** 算出来的权重超过单目标上限时拒绝提案，不能截断成一个实际占比差很远的近似值。 */
    @Test
    void unreachablePercentIsClarifiedInsteadOfClamped() {
        gateway.applyWeight("v1", AgentAction.MAX_WEIGHT);

        ActionProposal proposal = service.propose(SESSION, "t1", "i1", "/api/order", "v2", 99,
                WeightRequestUnit.TRAFFIC_PERCENT);

        assertFalse(proposal.created(), proposal.reason());
        assertTrue(proposal.clarificationRequired(), proposal.reason());
        assertTrue(proposal.reason().contains("10000") || proposal.reason().contains(String.valueOf(AgentAction.MAX_WEIGHT)),
                proposal.reason());
        assertTrue(repository.bySession(SESSION).isEmpty(), "做不到的占比不能留下待审批卡");
        assertEquals(0, gateway.writes);
    }

    /** 刚好顶到上限的占比仍然可以提案：这不是近似，是算出来的唯一解。 */
    @Test
    void percentThatLandsOnMaxWeightIsStillProposed() {
        gateway.applyWeight("v1", AgentAction.MAX_WEIGHT);
        gateway.applyWeight("v2", 1);

        AgentAction action = propose(50, WeightRequestUnit.TRAFFIC_PERCENT);

        assertEquals(AgentAction.MAX_WEIGHT, action.desiredWeight());
        assertEquals(0, gateway.writes);
    }

    @Test
    void recreatedResourceCannotUseOldApprovalEvenWhenNameAndRevisionLookUnchanged() {
        var scope = new com.rover.agent.core.model.ResourceScope("org", "ops", "prod");
        gateway.resourceRef = new com.rover.agent.core.model.ResourceRef(scope, "gateway-one", "GATEWAY_ROUTE", "order-api", "uid-one", 1);
        AgentAction pending = propose(20);
        assertEquals(gateway.resourceRef, pending.resourceRef());
        gateway.resourceRef = new com.rover.agent.core.model.ResourceRef(scope, "gateway-one", "GATEWAY_ROUTE", "order-api", "uid-two", 1);
        AgentAction refused = service.approve(pending.actionId(), OWNER);
        assertEquals(ActionStatus.PRECONDITION_FAILED, refused.status());
        assertEquals(0, gateway.writes);
    }

    @Test
    void compensationCannotModifyRecreatedResource() {
        var scope = new com.rover.agent.core.model.ResourceScope("org", "ops", "prod");
        gateway.resourceRef = new com.rover.agent.core.model.ResourceRef(scope, "gateway-one", "GATEWAY_ROUTE", "order-api", "uid-one", 1);
        AgentAction applied = service.approve(propose(20).actionId(), OWNER);
        gateway.resourceRef = new com.rover.agent.core.model.ResourceRef(scope, "gateway-one", "GATEWAY_ROUTE", "order-api", "uid-two", 1);
        AgentAction refused = service.rollback(applied.actionId(), OWNER);
        assertFalse(refused.status() == ActionStatus.ROLLED_BACK);
        assertEquals(1, gateway.writes);
        assertTrue(refused.errorMessage().contains("UID"));
    }

    // ---------------------------------------------------------------- 工具与假网关

    @Test void planRevisionCreatesNewApprovalAndKeepsTheOldSnapshot() {
        gateway.resourceRef = planResource();
        AgentAction first = propose(20);
        var original = first.plan();
        assertNotNull(original);
        AgentAction second = service.revisePlan(original.planId(), 1, original.planHash(), 30,
                WeightRequestUnit.RAW_WEIGHT, OWNER);
        assertEquals(2, second.plan().planVersion());
        assertEquals(original, repository.findPlan(original.planId(), 1).orElseThrow());
        assertEquals(ActionStatus.SUPERSEDED, repository.find(first.actionId()).orElseThrow().status());
        assertEquals(ActionStatus.PENDING_APPROVAL, second.status());
        assertNull(second.approvedBy());
        assertNull(second.applyOperationId());
        assertEquals(2, service.planVersions(original.planId(), OWNER).orElseThrow().size());
        assertTrue(service.planVersions(original.planId(), "mallory").isEmpty());
        assertThrows(ActionRequestException.class, () -> service.approve(first.actionId(), OWNER));
        assertThrows(ActionRequestException.class, () -> service.approve(second.actionId(), OWNER, 1, original.planHash()));
        assertEquals(0, gateway.writes);
        assertEquals(ActionStatus.SUCCESS, service.approve(second.actionId(), OWNER, 2, second.plan().planHash()).status());
        assertEquals(30, gateway.weightOf("v2"));
        assertEquals(1, gateway.writes);
    }

    @Test void planRevisionChecksInputSnapshotAndResourceIdentity() {
        gateway.resourceRef = planResource();
        AgentAction first = propose(20);
        var plan = first.plan();
        assertThrows(ActionRequestException.class, () -> service.revisePlan(plan.planId(), 1, "0".repeat(64), 30, WeightRequestUnit.RAW_WEIGHT, OWNER));
        assertThrows(ActionRequestException.class, () -> service.revisePlan(plan.planId(), 1, plan.planHash(), 101, WeightRequestUnit.TRAFFIC_PERCENT, OWNER));
        assertThrows(ActionRequestException.class, () -> service.revisePlan(plan.planId(), 1, plan.planHash(), 30, null, OWNER));
        assertThrows(ActionRequestException.class, () -> service.revisePlan(plan.planId(), 1, plan.planHash(), 20, WeightRequestUnit.RAW_WEIGHT, OWNER));
        gateway.resourceRef = new com.rover.agent.core.model.ResourceRef(plan.scope(), "gateway-one", "GATEWAY_ROUTE", "order-api", "recreated", 2);
        assertThrows(ActionRequestException.class, () -> service.revisePlan(plan.planId(), 1, plan.planHash(), 30, WeightRequestUnit.RAW_WEIGHT, OWNER));
        assertEquals(ActionStatus.PENDING_APPROVAL, repository.find(first.actionId()).orElseThrow().status());
        assertEquals(1, repository.planVersions(plan.planId()).size());
        assertEquals(0, gateway.writes);
    }

    @Test void approvalRequiresTheExactPreviewAndCannotReviseAnApprovedPlan() {
        gateway.resourceRef = planResource();
        AgentAction action = propose(20);
        assertThrows(ActionRequestException.class, () -> service.approve(action.actionId(), OWNER, null, null));
        assertThrows(ActionRequestException.class, () -> service.approve(action.actionId(), OWNER, 1, "0".repeat(64)));
        assertEquals(0, gateway.writes);
        service.approve(action.actionId(), OWNER, 1, action.plan().planHash());
        assertThrows(ActionRequestException.class, () -> service.revisePlan(action.plan().planId(), 1,
                action.plan().planHash(), 30, WeightRequestUnit.RAW_WEIGHT, OWNER));
        assertEquals(1, repository.planVersions(action.plan().planId()).size());
    }

    @Test void planCompensationRejectsSameValueAfterInterveningChanges() {
        gateway.resourceRef = planResource();
        AgentAction applied = service.approve(propose(20).actionId(), OWNER);
        gateway.applyWeight("v2", 40);
        gateway.applyWeight("v2", 20);
        AgentAction refused = service.rollback(applied.actionId(), OWNER);
        assertEquals(ActionStatus.ROLLBACK_PRECONDITION_FAILED, refused.status());
        assertEquals(20, gateway.weightOf("v2"));
        assertEquals(1, gateway.writes);
    }

    @Test void planCompensationWorksWhenTheOriginalRevisionStillOwnsTheValue() {
        gateway.resourceRef = planResource();
        AgentAction applied = service.approve(propose(20).actionId(), OWNER);
        AgentAction rolledBack = service.rollback(applied.actionId(), OWNER);
        assertEquals(ActionStatus.ROLLED_BACK, rolledBack.status());
        assertEquals(applied.plan(), rolledBack.plan());
        assertEquals(5, gateway.weightOf("v2"));
        assertEquals(2, gateway.writes);
    }

    @Test void managedHostRefusesLegacyApprovalButKeepsOriginalOperationReconciliation() {
        AgentAction pending = propose(20);
        gateway.timeoutWithoutApply = true;
        AgentAction uncertain = service.approve(pending.actionId(), OWNER);
        assertEquals(ActionStatus.UNCERTAIN, uncertain.status());
        AgentAction legacyPending = propose(30);
        com.rover.agent.core.port.AgentAccess managed = principal -> Set.of(com.rover.agent.core.port.AgentAccess.Permission.values());
        service = new AgentActionService(repository, sessions, gateway, new RouteWeightActionExecutor(gateway, repository, managed), managed);
        assertThrows(ActionRequestException.class, () -> service.approve(legacyPending.actionId(), OWNER));
        AgentAction reconciled = service.resolve(uncertain.actionId(), OWNER);
        assertEquals(uncertain.applyOperationId(), reconciled.applyOperationId());
        assertEquals(ActionStatus.UNCERTAIN, reconciled.status());
        assertEquals(1, gateway.writes);
    }

    @Test void expiredPlanCannotBeApprovedOrSubmitted() {
        gateway.resourceRef = planResource();
        long created = System.currentTimeMillis() - 120_000;
        AgentAction expired = new AgentAction("expired", SESSION, null, null,
                com.rover.agent.core.model.ActionType.ADJUST_ROUTE_TARGET_WEIGHT, ActionStatus.PENDING_APPROVAL,
                "order-api", "/api/order", "order-service", "v2", 5, 20, 20, WeightRequestUnit.RAW_WEIGHT,
                5, 17.4, PROPOSED_REVISION, null, null, null, OWNER, null, 0, List.of(), "test", null,
                created, created).withResourceRef(gateway.resourceRef);
        expired = expired.withPlan(com.rover.agent.core.model.RemediationPlan.forRouteWeight(expired, "expired", 1, created + 60_000));
        AgentAction saved = repository.save(expired);
        assertThrows(ActionRequestException.class, () -> service.approve(saved.actionId(), OWNER));
        assertEquals(0, gateway.writes);
    }

    @Test void unsupportedAdapterAndChecksCannotReachTheGateway() {
        gateway.resourceRef = planResource();
        AgentAction pending = propose(20);
        var p = pending.plan();
        var s = p.steps().get(0);
        var unsupported = new com.rover.agent.core.model.RemediationPlan.RouteWeightStep(s.sequence(), s.actionType(),
                "unregistered-v99", s.resourceRef(), s.routeId(), s.businessPrefix(), s.serviceName(), s.group(),
                s.beforeWeight(), s.desiredWeight(), s.requestedValue(), s.requestedUnit(), s.beforeTrafficPercent(),
                s.desiredTrafficPercent(), s.expectedRevision(), s.requiredChecks(), s.expectedEffect());
        var changed = new com.rover.agent.core.model.RemediationPlan(p.planId(), p.planVersion(), p.sessionId(),
                p.incidentId(), p.taskId(), p.requestedBy(), p.scope(), p.goal(), p.alternatives(), p.evidenceRefs(),
                p.unresolvedAssumptions(), List.of(unsupported), p.riskAssessment(), p.resourceBudget(),
                p.verificationSpec(), p.compensationSpec(), p.timeoutMillis(), p.createdAtMillis(), p.expiresAtMillis(),
                p.provenance(), null);
        assertThrows(IllegalArgumentException.class, () -> RouteWeightActionExecutor.requireSupportedPlan(pending.withPlan(changed)));
        assertEquals(0, gateway.writes);
    }

    @Test void replayDoesNotReturnASupersededApprovalCard() {
        gateway.resourceRef = planResource();
        AgentAction first = propose(20);
        service.revisePlan(first.plan().planId(), 1, first.plan().planHash(), 30, WeightRequestUnit.RAW_WEIGHT, OWNER);
        AgentAction replay = propose(20);
        assertFalse(replay.actionId().equals(first.actionId()));
        assertEquals(ActionStatus.PENDING_APPROVAL, replay.status());
        assertEquals(0, gateway.writes);
    }

    private static com.rover.agent.core.model.ResourceRef planResource() {
        return new com.rover.agent.core.model.ResourceRef(
                new com.rover.agent.core.model.ResourceScope("org", "ops", "prod"),
                "gateway-one", "GATEWAY_ROUTE", "order-api", "uid-one", 1);
    }

    private AgentAction propose(int weight) {
        return propose(weight, WeightRequestUnit.RAW_WEIGHT);
    }

    private AgentAction propose(int weight, WeightRequestUnit unit) {
        ActionProposal proposal = service.propose(SESSION, "t1", "i1", "/api/order", "v2", weight, unit);
        assertTrue(proposal.created(), proposal.reason());
        return proposal.action();
    }

    /** 提议并返回「提不了」的原因；提成了返回空串（用例期望的是被拒）。 */
    private String propose(String route, String group, Integer weight) {
        return propose(route, group, weight, WeightRequestUnit.RAW_WEIGHT);
    }

    private String propose(String route, String group, Integer weight, WeightRequestUnit unit) {
        ActionProposal proposal = service.propose(SESSION, "t1", "i1", route, group, weight, unit);
        return proposal.created() ? "" : proposal.reason();
    }

    /**
     * 够用的假网关：保存一张路由表与版本号，并且能按需制造四种现实故障
     * （超时但已生效、超时且未生效、回读读不到、写成功但状态没变）。
     */
    private static final class FakeRouteControl implements RouteControlPort {

        private int revision = PROPOSED_REVISION;
        private com.rover.agent.core.model.ResourceRef resourceRef;
        private final Map<String, Integer> weights = new HashMap<>(Map.of("v1", 95, "v2", 5));
        private final Set<String> removed = new HashSet<>();
        private final Map<String, RouteOperationStatus> operations = new HashMap<>();
        private final List<String> queriedOperations = new ArrayList<>();

        /** 已发出的写请求次数：全部用例都靠它证明「有没有真的动过网关」。 */
        private int writes;
        private String lastOperationId;
        private int lastExpectedRevision;
        /** 写请求是否真的改了权重（false 用来伪造「网关说成功但状态没变」）。 */
        private boolean applyOnWrite = true;
        /** 超时且已生效。 */
        private boolean timeoutAfterApply;
        /** 超时且未生效。 */
        private boolean timeoutWithoutApply;
        /** 写完之后读不到路由。 */
        private boolean unreachableAfterWrite;

        @Override
        public RouteControlState state() {
            if (unreachableAfterWrite && writes > 0) {
                throw new SnapshotUnavailableException("假网关：读不到路由");
            }
            return new RouteControlState(revision, "", List.of(new RouteControlRoute("order-api", "/api/order", "",
                    targets(), resourceRef)));
        }

        @Override
        public RouteChangePreview preview(String routeId, String serviceName, String group, int weight) {
            return new RouteChangePreview(revision, "预览通过，未落盘、未生效",
                    List.of("MODIFIED /api/order：order-service@" + group + " 权重 → " + weight));
        }

        @Override
        public RouteChangeResult adjustTargetWeight(String routeId, String serviceName, String group, int weight,
                                                    int expectedRevision, String operationId) {
            writes++;
            lastOperationId = operationId;
            lastExpectedRevision = expectedRevision;
            if (expectedRevision != revision) {
                operations.put(operationId, RouteOperationStatus.CONFLICT);
                throw new RouteControlException(RouteControlException.Kind.CONFLICT,
                        "版本冲突：期望 " + expectedRevision + "，当前 " + revision);
            }
            if (timeoutAfterApply || timeoutWithoutApply) {
                if (timeoutAfterApply) {
                    applyWeight(group, weight);
                    operations.put(operationId, RouteOperationStatus.APPLIED);
                }
                throw new RouteControlException(RouteControlException.Kind.UNAVAILABLE, "假网关：请求超时");
            }
            if (applyOnWrite) {
                applyWeight(group, weight);
            } else {
                revision++;
            }
            operations.put(operationId, RouteOperationStatus.APPLIED);
            return new RouteChangeResult("APPLIED", revision, operationId, "已应用");
        }

        @Override
        public RouteOperation operation(String operationId) {
            queriedOperations.add(operationId);
            RouteOperationStatus status = operations.getOrDefault(operationId, RouteOperationStatus.UNKNOWN);
            return new RouteOperation(operationId, status, revision, "假网关", revision);
        }

        private List<RouteControlTarget> targets() {
            List<RouteControlTarget> targets = new ArrayList<>();
            if (!removed.contains("v1")) {
                targets.add(new RouteControlTarget("order-service", "v1", weights.get("v1")));
            }
            if (!removed.contains("v2")) {
                targets.add(new RouteControlTarget("order-service", "v2", weights.get("v2")));
            }
            return targets;
        }

        private void applyWeight(String group, int weight) {
            weights.put(group, weight);
            revision++;
        }

        private int weightOf(String group) {
            return weights.getOrDefault(group, -1);
        }

        /** 摘掉一个版本目标；刻意不动版本号，用来单独盯住「目标不存在」这条兜底分支。 */
        private void removeTarget(String group) {
            removed.add(group);
        }
    }
}
