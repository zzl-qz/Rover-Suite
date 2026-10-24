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
    @Test void fullModelPlanRetainsRequiredReadsAndCannotDowngradeTheirFlags() {
        var registry=CapabilityRegistry.standard();
        JsonCompletion model=new JsonCompletion() {
            public <T> Optional<T> complete(String system,String user,Function<String,Optional<T>> parser) {
                return parser.apply("{\"steps\":[\"LOG_QUERY\",\"CONFIG_READ\",\"EVENT_QUERY\",\"GATEWAY_METRICS_QUERY\",\"TRACE_QUERY\",\"CHANGE_TIMELINE_QUERY\"]}");
            }
        };
        var request=new PlanningRequest("排查",ResourceTarget.route("/orders"),"/orders",List.of(),List.of());
        var required=new RuleBasedPlanner(registry).plan(request).steps().stream().filter(PlannedStep::required).map(PlannedStep::capability).toList();
        var validated=new PlanValidator(registry,PlanningLimits.defaults()).validate(
                new LlmInvestigationPlanner(null,model,registry).plan(request),request,Set.of());
        var retained=validated.steps().stream().filter(PlannedStep::required).map(PlannedStep::capability).toList();
        assertTrue(retained.containsAll(required));
        assertTrue(validated.steps().size()<=6);
    }
    @Test void evidenceBudgetKeepsReferencedLateFactsAndBothVersionPopulations() {
        var rows = new java.util.ArrayList<Evidence>();
        for (int i=0;i<30;i++) {
            rows.add(Evidence.of("t",EvidenceType.LOG,"logs","noise","unrelated context ".repeat(100),"/logs",i));
        }
        Evidence trace=Evidence.of("t",EvidenceType.TRACE,"gateway","trace","context ".repeat(40)+"POOL_EXHAUSTED_AFTER_HEADER","/traces",100);
        trace=trace.withEnvelope(new EvidenceEnvelope(1,"gateway.traces","TRACE","q","v1",new TimeRange(1,10),null,null,100,null,
                "SAMPLED",120,EvidenceQuality.PARTIAL,null,List.of(),java.util.Map.of("samplePopulations",List.of(
                    java.util.Map.of("group","v1","sampleRequests",62,"sample5xx",0),
                    java.util.Map.of("group","v2","sampleRequests",58,"sample5xx",58))),List.of(),null,null,"v1"));
        rows.add(trace);
        String evidenceId=trace.evidenceId();
        var hypothesis=new Hypothesis("H5","窗口内失败",Verdict.UNKNOWN,"待验证",List.of(),List.of(evidenceId),List.of(),
                List.of("应用内部证据"),List.of(AgentCapability.LOG_QUERY));
        JsonCompletion capture=new JsonCompletion() {
            public <T> Optional<T> complete(String system,String user,Function<String,Optional<T>> parser) {
                assertTrue(user.contains("POOL_EXHAUSTED_AFTER_HEADER"),"关键线索不能在 160 字截断中丢失");
                assertTrue(user.contains("\"group\":\"v1\"") && user.contains("\"group\":\"v2\""));
                assertTrue(user.contains("\"sampleRequests\":58") && user.contains("\"sample5xx\":58"));
                assertTrue(user.contains("支持=["+evidenceId+"]"));
                assertTrue(user.contains("已省略") && user.contains("省略不表示无异常"));
                return parser.apply("{\"steps\":[\"LOG_QUERY\"]}");
            }
        };
        new LlmInvestigationPlanner(null,capture,CapabilityRegistry.standard(),4000).plan(
                new PlanningRequest("排查",ResourceTarget.route("/orders"),"/orders",rows,List.of(),null,List.of(hypothesis)));
    }

    @Test void onePlanningResponseCannotExhaustAllCandidateSlots() {
        JsonCompletion model=new JsonCompletion() {
            public <T> Optional<T> complete(String system,String user,Function<String,Optional<T>> parser) {
                return parser.apply("{\"candidates\":[{\"statement\":\"a\"},{\"statement\":\"b\"},{\"statement\":\"c\"},{\"statement\":\"d\"}]}");
            }
        };
        var request=new PlanningRequest("排查",ResourceTarget.route("/orders"),"/orders",List.of(),List.of());
        var plan=new LlmInvestigationPlanner(null,model,CapabilityRegistry.standard()).plan(request);
        assertEquals(3,plan.assessments().stream().filter(h->h.id().startsWith("M")).count());
        assertTrue(plan.assessments().stream().allMatch(h->h.status()==Verdict.UNKNOWN));
    }
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
                assertTrue(system.contains("最多3个新候选、6个步骤"));
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
