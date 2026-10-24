package com.rover.agent.runtime.action;

import com.rover.agent.core.model.ConfigActionStatus;
import com.rover.agent.core.model.GatewayConfigAction;
import com.rover.agent.core.port.AgentAccess;
import com.rover.agent.core.port.GatewayConfigActionException;
import com.rover.agent.core.port.GatewayConfigActionPort;
import com.rover.agent.core.port.GatewayConfigActionReceipt;
import com.rover.agent.core.port.RouteControlPort;
import com.rover.agent.core.repository.GatewayConfigActionRepository;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.Set;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.PreDestroy;
import org.springframework.context.event.ContextClosedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded single-process worker. After submission, restarts and failures only query the original receipt. */
public final class GatewayConfigActionCommandRunner implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(GatewayConfigActionCommandRunner.class);
    private final GatewayConfigActionRepository actions;
    private final GatewayConfigActionPort gateway;
    private final AgentAccess access;
    private final RouteControlPort control;
    private final java.util.function.BooleanSupplier durableReady;
    private final ThreadPoolExecutor worker;
    private final ScheduledThreadPoolExecutor poller;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    public GatewayConfigActionCommandRunner(GatewayConfigActionRepository actions, GatewayConfigActionPort gateway,
                                            AgentAccess access, RouteControlPort control,
                                            java.util.function.BooleanSupplier durableReady) {
        this.actions = actions; this.gateway = gateway; this.access = access; this.control = control;
        this.durableReady = durableReady;
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(64),
                r -> { Thread t = new Thread(r, "rover-config-action-worker"); t.setDaemon(true); return t; });
        poller = new ScheduledThreadPoolExecutor(1, r -> { Thread t = new Thread(r, "rover-config-action-poller"); t.setDaemon(true); return t; });
        poller.setRemoveOnCancelPolicy(true);
    }
    @org.springframework.context.event.EventListener(org.springframework.context.event.ContextRefreshedEvent.class)
    public void start() { if (started.compareAndSet(false, true)) poller.scheduleWithFixedDelay(this::poll, 0, 500, TimeUnit.MILLISECONDS); }
    public void poll() {
        if (stopped.get() || worker.isShutdown()) return;
        try {
        for (GatewayConfigAction action : actions.byStatus(Set.of(ConfigActionStatus.QUEUED,
                ConfigActionStatus.RECONCILE_QUEUED), 100)) {
            if (!running.add(action.actionId())) continue;
            try { worker.execute(() -> run(action)); }
            catch (RejectedExecutionException full) { running.remove(action.actionId()); }
        }
        // Process restart or a lost worker result: EXECUTING always means the write may have happened.
        for (GatewayConfigAction action : actions.byStatus(Set.of(ConfigActionStatus.EXECUTING,
                ConfigActionStatus.RECONCILING), 100)) {
            if (!running.add(action.actionId())) continue;
            try { worker.execute(() -> reconcile(action)); }
            catch (RejectedExecutionException full) { running.remove(action.actionId()); }
        }
        } catch (RuntimeException failure) {
            log.error("Gateway 配置动作队列读取失败，worker 将继续轮询", failure);
        }
    }
    private void run(GatewayConfigAction candidate) {
        try {
            GatewayConfigAction current = candidate;
            boolean reconciling = current.status() == ConfigActionStatus.RECONCILE_QUEUED;
            ConfigActionStatus queued = reconciling ? ConfigActionStatus.RECONCILE_QUEUED : ConfigActionStatus.QUEUED;
            ConfigActionStatus active = reconciling ? ConfigActionStatus.RECONCILING : ConfigActionStatus.EXECUTING;
            current = actions.transition(candidate.actionId(), queued, a -> a.withStatus(active, null, System.currentTimeMillis())).orElse(null);
            if (current != null) {
                if (reconciling) reconcile(current);
                else if (current.expiresAtMillis() <= System.currentTimeMillis()) {
                    actions.transition(current.actionId(), active, a -> a.withStatus(ConfigActionStatus.EXPIRED,
                            "配置计划在执行前过期，未提交 Gateway 写入", System.currentTimeMillis()));
                } else if (current.approvalComplete()) submit(current);
                else actions.transition(current.actionId(), active,
                        a -> a.withStatus(ConfigActionStatus.FAILED, "审批不足，未提交 Gateway 写入", System.currentTimeMillis()));
            }
        } catch (RuntimeException failure) { log.error("Gateway 配置动作后台处理失败", failure); }
        finally { running.remove(candidate.actionId()); }
    }
    private void submit(GatewayConfigAction action) {
        if (action.expiresAtMillis() <= System.currentTimeMillis() || !targetsCurrent(action)
                || !durableReady.getAsBoolean() || action.approvals().size() < 2) {
            actions.transition(action.actionId(), ConfigActionStatus.EXECUTING,
                    a -> a.withStatus(a.expiresAtMillis() <= System.currentTimeMillis() ? ConfigActionStatus.EXPIRED
                                    : ConfigActionStatus.FAILED, a.expiresAtMillis() <= System.currentTimeMillis()
                                    ? "配置计划在执行前过期，未提交 Gateway 写入"
                                    : !targetsCurrent(a) ? "Gateway 管理目标已变化；旧计划未提交写入，请在当前目标重新调查并提案"
                                    : a.approvals().size() < 2 ? "审批不足，未提交 Gateway 写入"
                                    : "持久存储未就绪；未提交 Gateway 写入", System.currentTimeMillis()));
            return;
        }
        boolean allApproversCurrent = action.approvals().size() >= 2 && action.approvals().stream()
                .allMatch(a -> access.permissions(a.approver()).contains(AgentAccess.Permission.APPROVE)
                        && access.writeAllowed(a.approver()));
        AgentAccess.WritePermit permit = allApproversCurrent
                && action.expiresAtMillis() > System.currentTimeMillis()
                ? access.acquireWritePermit(action.approvals().get(action.approvals().size()-1).approver(), false) : null;
        if (permit == null) {
            actions.transition(action.actionId(), ConfigActionStatus.EXECUTING,
                    a -> a.withStatus(ConfigActionStatus.FAILED, "写入权限或停写门禁未通过；未提交 Gateway 写入", System.currentTimeMillis()));
            return;
        }
        try {
            GatewayConfigActionReceipt receipt = gateway.execute(action.operationId(), action.intent().configKey(),
                    action.intent().beforeValue(), action.intent().desiredValue());
            settle(action, receipt);
        } catch (GatewayConfigActionException failure) {
            if (!targetsCurrent(action)) {
                actions.transition(action.actionId(), ConfigActionStatus.EXECUTING, a -> a.withStatus(
                        ConfigActionStatus.UNKNOWN, "Gateway 管理目标在写入期间发生变化；操作结果未知，禁止查询或重放到新目标", System.currentTimeMillis()));
                return;
            }
            if (failure.outcomeUnknown()) {
                try {
                    GatewayConfigActionReceipt receipt = gateway.query(action.operationId(), action.intent().configKey(),
                            action.intent().beforeValue(), action.intent().desiredValue());
                    settle(action, receipt);
                } catch (RuntimeException queryFailure) {
                    actions.transition(action.actionId(), ConfigActionStatus.EXECUTING,
                            a -> a.withStatus(ConfigActionStatus.UNKNOWN, "提交响应不确定，回执查询也失败；没有重发，请使用原操作号对账", System.currentTimeMillis()));
                }
            } else {
                actions.transition(action.actionId(), ConfigActionStatus.EXECUTING,
                        a -> a.withStatus(ConfigActionStatus.FAILED, safe(failure), System.currentTimeMillis()));
            }
        } finally {
            permit.close();
        }
    }
    private void reconcile(GatewayConfigAction action) {
        ConfigActionStatus active = action.status();
        if (!targetsCurrent(action)) {
            actions.transition(action.actionId(), active, a -> a.withStatus(ConfigActionStatus.UNKNOWN,
                    "Gateway 管理目标已变化，无法在原目标查询回执；未查询新目标，也未重放写入", System.currentTimeMillis()));
            return;
        }
        if (!durableReady.getAsBoolean()) {
            actions.transition(action.actionId(), active, a -> a.withStatus(ConfigActionStatus.UNKNOWN,
                    "持久存储未就绪；保持结果未知，稍后可用原操作号重新查询", System.currentTimeMillis()));
            return;
        }
        try {
            // EXECUTING may have crossed the network boundary before a crash. Recovery is always
            // read-only, even if the first request might not have left the process.
            GatewayConfigActionReceipt receipt = gateway.query(action.operationId(), action.intent().configKey(),
                    action.intent().beforeValue(), action.intent().desiredValue());
            settle(action, receipt);
        } catch (GatewayConfigActionException failure) {
            if (failure.outcomeUnknown()) {
                actions.transition(action.actionId(), active,
                        a -> a.withStatus(ConfigActionStatus.UNKNOWN, "回执仍未知；不会根据当前配置猜测，也不会重放写入", System.currentTimeMillis()));
            } else actions.transition(action.actionId(), active,
                    a -> a.withStatus(ConfigActionStatus.FAILED, safe(failure), System.currentTimeMillis()));
        } catch (RuntimeException failure) {
            actions.transition(action.actionId(), active,
                    a -> a.withStatus(ConfigActionStatus.UNKNOWN, "回执查询失败；不会重放写入", System.currentTimeMillis()));
        }
    }

    /** A persisted plan is bound to one immutable management target; never send it to a replacement target. */
    private boolean targetsCurrent(GatewayConfigAction action) {
        var intent = action.intent();
        return intent.scope().equals(control.resourceScope())
                && intent.controlTargetId().equals(control.controlTargetId())
                && intent.targetNamespace().equals(control.resourceNamespace());
    }
    private void settle(GatewayConfigAction action, GatewayConfigActionReceipt receipt) {
        ConfigActionStatus current = action.status();
        if (receipt == null || !action.operationId().equals(receipt.operationId())
                || (receipt.status() != GatewayConfigActionReceipt.Status.UNKNOWN
                    && (!present(receipt.configKey()) || !present(receipt.expectedValue()) || !present(receipt.desiredValue())))
                || !matchesIfPresent(action.intent().configKey(), receipt.configKey())
                || !matchesIfPresent(action.intent().beforeValue(), receipt.expectedValue())
                || !matchesIfPresent(action.intent().desiredValue(), receipt.desiredValue())) {
            actions.transition(action.actionId(), current, a -> a.withStatus(ConfigActionStatus.UNKNOWN,
                    "Gateway 回执缺失或与冻结意图不匹配；不会据此确认成功，也不会重放写入", System.currentTimeMillis()));
            return;
        }
        if (receipt.status() == GatewayConfigActionReceipt.Status.UNKNOWN
                && (!present(receipt.configKey()) || !present(receipt.expectedValue()) || !present(receipt.desiredValue()))) {
            actions.transition(action.actionId(), current, a -> a.withStatus(ConfigActionStatus.UNKNOWN,
                    "Gateway 未返回完整意图回执；不能确认配置结果，也不会重放写入", System.currentTimeMillis()));
            return;
        }
        ConfigActionStatus result = switch (receipt.status()) {
            case APPLIED -> ConfigActionStatus.APPLIED;
            case PRECONDITION_FAILED -> ConfigActionStatus.PRECONDITION_FAILED;
            case FAILED -> ConfigActionStatus.FAILED;
            case UNKNOWN -> ConfigActionStatus.UNKNOWN;
        };
        actions.transition(action.actionId(), current,
                a -> a.withStatus(result, receipt.message().isBlank() ? null : receipt.message(), System.currentTimeMillis()));
    }
    private static boolean matchesIfPresent(String expected, String actual) {
        return actual == null || actual.isBlank() || expected.equals(actual);
    }
    private static boolean present(String value) { return value != null && !value.isBlank(); }
    private static String safe(Throwable failure) {
        String text = failure.getMessage(); return text == null ? failure.getClass().getSimpleName()
                : text.length() > 1000 ? text.substring(0, 1000) : text;
    }
    @Override public void close() {
        if (stopped.compareAndSet(false, true)) {
            poller.shutdownNow();
            worker.shutdown();
            try { if (!worker.awaitTermination(5, TimeUnit.SECONDS)) worker.shutdownNow(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); worker.shutdownNow(); }
        }
    }
    @org.springframework.context.event.EventListener(ContextClosedEvent.class)
    @PreDestroy
    public void shutdown() { close(); }
}
