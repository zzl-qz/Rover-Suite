package com.rover.agent.runtime.planning;

import static org.junit.jupiter.api.Assertions.*;
import com.rover.agent.core.capability.*;
import com.rover.agent.core.model.*;
import com.rover.agent.core.planning.*;
import com.rover.agent.runtime.llm.JsonCompletion;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class StructuredPlanningTest {
    @Test void modelCannotPromoteCandidatesForgeCitationsOrExpandTheTarget() {
        CapabilityRegistry registry = CapabilityRegistry.standard();
        JsonCompletion model = new JsonCompletion() {
            public <T> Optional<T> complete(String system, String user, Function<String, Optional<T>> parser) {
                assertTrue(user.contains("missingEvidence"));
                return parser.apply("""
                    {"goal":"排查","candidates":[{"statement":"数据库连接池耗尽","status":"CONFIRMED",
                      "supportingEvidenceIds":["forged"],"missingEvidence":["应用连接池指标"],
                      "nextCapabilities":["CONFIG_READ","SHELL_EXEC","CONFIG_READ"]}],
                     "steps":[{"capability":"CONFIG_READ","target":"/other","reason":"排查"}]}
                    """);
            }
        };
        var request = new PlanningRequest("排查 /orders", ResourceTarget.route("/orders"), "/orders", List.of(), List.of());
        var planner = new LlmInvestigationPlanner(new RuleBasedPlanner(registry), model, registry);
        var validated = new PlanValidator(registry, PlanningLimits.defaults()).validate(planner.plan(request), request, Set.of());
        Hypothesis candidate = validated.assessments().stream().filter(h -> h.id().equals("M1")).findFirst().orElseThrow();
        assertEquals(Verdict.UNKNOWN, candidate.status());
        assertTrue(candidate.supportingEvidenceIds().isEmpty());
        assertEquals(List.of(AgentCapability.CONFIG_READ), candidate.nextCapabilities());
        assertFalse(candidate.missingEvidence().isEmpty());
        assertTrue(validated.steps().stream().allMatch(step -> step.target().equals(request.target())));
        assertTrue(validated.capabilities().containsAll(Set.of(AgentCapability.ROUTE_QUERY, AgentCapability.INSTANCE_QUERY, AgentCapability.TRACE_QUERY)));
    }

    @Test void hypothesisGapsRequireFurtherReadsDespitePreviousToolsBeingSettled() {
        var missing = new Hypothesis("M1", "应用异常", Verdict.UNKNOWN, "缺日志", List.of(), List.of(), List.of(),
                List.of("应用日志"), List.of(AgentCapability.LOG_QUERY));
        var request = new PlanningRequest("排查", ResourceTarget.route("/orders"), "/orders", List.of(), List.of(), null, List.of(missing));
        var planner = new RuleBasedPlanner(CapabilityRegistry.standard());
        var progress = PlanProgress.of(1,3,Set.of(AgentCapability.ROUTE_QUERY,AgentCapability.INSTANCE_QUERY,AgentCapability.TRACE_QUERY),0);
        assertTrue(planner.evaluate(request, progress).shouldContinue());
        assertTrue(planner.plan(request).capabilities().contains(AgentCapability.LOG_QUERY));
    }
}
