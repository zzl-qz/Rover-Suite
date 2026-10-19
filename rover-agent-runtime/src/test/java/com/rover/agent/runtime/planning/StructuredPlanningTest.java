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
    @Test void plannerKeepsTheFactWhenTheEvidenceHeaderAloneExceedsTheOldCutoff() {
        Evidence e = Evidence.of("t",EvidenceType.TRACE,"gateway","trace","REAL_FACT_MARKER v2 58/58 sampled 503","/api/traces",System.currentTimeMillis());
        e = e.withEnvelope(new EvidenceEnvelope(1,"gateway.traces","TRACE","q","v1",new TimeRange(1,10),null,new TimeRange(1,10),
                System.currentTimeMillis(),null,"BERNOULLI_PLUS_SLOW_WITH_CAPACITY_OVERWRITE",120,EvidenceQuality.PARTIAL,null,List.of(),
                java.util.Map.of(),List.of(),null,null,"v1"));
        assertTrue(e.modelText().indexOf("REAL_FACT_MARKER") > 160, "旧代码在截断事实之前已被头信息占满");
        JsonCompletion capture = new JsonCompletion() {
            public <T> Optional<T> complete(String system,String user,Function<String,Optional<T>> parser) {
                assertTrue(user.contains("REAL_FACT_MARKER"));
                assertTrue(user.contains("PARTIAL"));
                assertTrue(system.contains("最多1个新候选、3个步骤"));
                return parser.apply("{\"steps\":[]}");
            }
        };
        var planner = new LlmInvestigationPlanner(null,capture,CapabilityRegistry.standard());
        planner.plan(new PlanningRequest("排查",ResourceTarget.route("/orders"),"/orders",List.of(e),List.of()));
    }
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

    @Test void compactCapabilityNamesStillPassTheSameWhitelistAndTargetChecks() {
        var registry = CapabilityRegistry.standard();
        JsonCompletion model = new JsonCompletion() {
            public <T> Optional<T> complete(String system,String user,Function<String,Optional<T>> parser) {
                return parser.apply("{\"steps\":[\"LOG_QUERY\",\"SHELL_EXEC\",\"LOG_QUERY\"],\"candidates\":[]}");
            }
        };
        var request = new PlanningRequest("排查",ResourceTarget.route("/orders"),"/orders",List.of(),List.of());
        var plan = new PlanValidator(registry,PlanningLimits.defaults()).validate(new LlmInvestigationPlanner(null,model,registry).plan(request),request,Set.of());
        assertEquals(1,plan.steps().stream().filter(s -> s.capability()==AgentCapability.LOG_QUERY).count());
        assertTrue(plan.steps().stream().allMatch(s -> s.target().equals(request.target())));
        assertTrue(plan.capabilities().contains(AgentCapability.ROUTE_QUERY));
    }
}
