package com.rover.agent.runtime.action;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.rover.agent.core.investigation.Findings;
import com.rover.agent.core.model.Confidence;
import com.rover.agent.core.model.Evidence;
import com.rover.agent.core.model.EvidenceEnvelope;
import com.rover.agent.core.model.EvidenceQuality;
import com.rover.agent.core.model.EvidenceType;
import com.rover.agent.core.model.Hypothesis;
import com.rover.agent.core.model.InvestigationContext;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.TimeRange;
import com.rover.agent.core.model.Verdict;
import com.rover.agent.runtime.task.InvestigationTask;
import com.rover.agent.runtime.task.InvestigationTaskRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class EvidenceGatedPlanProposerTest {

    private static final String SESSION = "s-candidate";
    private static final String PATH = "/api/order";
    private final InvestigationTaskRegistry tasks = new InvestigationTaskRegistry();

    @AfterEach
    void cleanup() { tasks.stop(); }

    @Test
    void one5xxDoesNotCreateAStopTrafficCandidate() {
        AgentActionService actions = mock(AgentActionService.class);
        EvidenceGatedPlanProposer.Decision decision = propose(actions, "ev-one", 5, 1, true, List.of("ev-one"));

        assertFalse(decision.created());
        assertTrue(decision.detail().contains("单次 5xx 不会触发"), decision.detail());
        verifyNoInteractions(actions);
    }

    @Test
    void severeCompleteCurrentEvidenceMayCreateOnlyAnApprovalCandidate() {
        AgentActionService actions = mock(AgentActionService.class);
        when(actions.proposeEvidenceGatedQuarantine(eq(SESSION), anyString(), eq("incident"), eq(PATH), eq("v2"),
                eq(List.of("ev-strong"))))
                .thenReturn(ActionProposal.rejected("Gateway 路由已变化"));
        EvidenceGatedPlanProposer.Decision decision = propose(actions, "ev-strong", 20, 5, true,
                List.of("ev-strong"));

        assertFalse(decision.created());
        assertTrue(decision.evidenceQualified());
        assertTrue(decision.detail().contains("Gateway 路由已变化"), decision.detail());
        verify(actions).proposeEvidenceGatedQuarantine(eq(SESSION), anyString(), eq("incident"), eq(PATH), eq("v2"),
                eq(List.of("ev-strong")));
    }

    @Test
    void truncatedMetricEvidenceIsRejectedBeforeCallingTheActionService() {
        AgentActionService actions = mock(AgentActionService.class);
        EvidenceGatedPlanProposer.Decision decision = propose(actions, "ev-truncated", 100, 50, false,
                List.of("ev-truncated"));

        assertFalse(decision.created());
        assertTrue(decision.detail().contains("完整的路由、版本、样本或新鲜度"), decision.detail());
        verifyNoInteractions(actions);
    }

    private EvidenceGatedPlanProposer.Decision propose(AgentActionService actions, String evidenceId,
                                                       long requests, long errors, boolean complete,
                                                       List<String> supportingIds) {
        InvestigationTask task = tasks.register(SESSION, "诊断订单路由");
        task.bind("incident", PATH, ResourceTarget.route(PATH));
        Evidence evidence = metric(task.taskId(), task.incidentId(), evidenceId, requests, errors, complete);
        Hypothesis h6 = new Hypothesis("H6", "该路由访问某个上游实例时出现 5xx", Verdict.CONFIRMED,
                "测试规则确认", List.of("/api/metrics/routes"), supportingIds, List.of(), List.of(), List.of());
        Findings findings = new Findings(List.of(h6), "诊断结论", Confidence.MEDIUM);
        return EvidenceGatedPlanProposer.propose(task, PATH, findings, List.of(evidence), List.of(), false, actions);
    }

    private static Evidence metric(String taskId, String incidentId, String id, long requests, long errors,
                                   boolean complete) {
        long now = System.currentTimeMillis();
        Map<String, Object> facts = Map.of("routeId", "order-api", "hostPort", "10.0.0.2:8080", "group", "v2",
                "windowSeconds", 60, "windowRequests", requests, "status5xx", errors, "complete", complete);
        InvestigationContext context = new InvestigationContext(taskId, incidentId, null, null, null,
                ResourceTarget.route(PATH), TimeRange.unspecified(), Map.of(), "LIVE_RBAC_V1");
        EvidenceEnvelope envelope = new EvidenceEnvelope(1, "gateway.route-upstreams", "METRIC", "q-1",
                "gateway-upstream-window-v1", TimeRange.unspecified(), null, null, now, now + 60_000,
                "WINDOW_COUNTERS", requests, EvidenceQuality.AVAILABLE, context, List.of(), facts, List.of(),
                "gateway-upstream-window-v1", null, "gateway-upstream-window-v1");
        return new Evidence(id, taskId, EvidenceType.METRIC, "Gateway 实时指标", "上游实例窗口观测", "测试指标",
                "/api/metrics/routes?routeId=order-api", Map.of(), now, envelope);
    }
}
