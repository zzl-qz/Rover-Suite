package com.rover.agent.runtime.llm;

import com.fasterxml.jackson.databind.*;
import com.rover.agent.core.capability.*;
import com.rover.agent.core.investigation.DiagnosticReport;
import com.rover.agent.core.model.*;
import com.rover.agent.core.planning.*;
import com.rover.agent.core.port.*;
import com.rover.agent.core.snapshot.*;
import com.rover.agent.core.timeline.*;
import com.rover.agent.runtime.graph.*;
import com.rover.agent.runtime.planning.LlmInvestigationPlanner;
import com.rover.agent.runtime.task.InvestigationTaskRegistry;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.junit.jupiter.api.Assertions.*;

/** 冻结输入和人工编写的判定标签，执行实际规划/图/端口/证据校验/报告，不能冒充实时模型质量。 */
class FrozenInvestigationReplayTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Path DATA=Files.isRegularFile(Path.of("deploy/evals/agent-investigations-v1.json"))
            ? Path.of("deploy/evals/agent-investigations-v1.json") : Path.of("../deploy/evals/agent-investigations-v1.json");
    private static final List<Map<String,Object>> RESULTS=new ArrayList<>();
    private static final String PATH="/orders";
    private static final ResourceScope SCOPE=new ResourceScope("fixture","ops","test");

    static Stream<Arguments> scenarios() throws Exception { return java.util.stream.StreamSupport.stream(JSON.readTree(DATA.toFile()).path("cases").spliterator(),false).map(c->Arguments.of(c.path("id").asText(),c)); }

    @ParameterizedTest(name="{0}") @MethodSource("scenarios")
    void frozenWorldMustMeetExpectedEvidenceAndUnknownBoundaries(String id,JsonNode scenario) throws Exception {
        long anchor=System.currentTimeMillis();
        TimeRange range=scenario.path("historical").asBoolean() ? new TimeRange(anchor-7_200_000,anchor-3_600_000) : TimeRange.unspecified();
        long eventAt=range.isUnspecified() ? anchor-1000 : range.fromMillis()+1000;
        boolean missing=scenario.path("unavailable").asBoolean();
        var target=ResourceTarget.route(PATH);
        RouteReadPort routes=new RouteReadPort() {
            public List<RouteSnapshot> routes(){if(missing){throw new SnapshotUnavailableException("fixture unavailable");}return scenario.path("route").asBoolean() ? List.of(new RouteSnapshot("orders",PATH,"orders","","","",1)) : List.of();}
            public DiscoveryMode discoveryMode(){return DiscoveryMode.NAMESERVER;}
        };
        List<InstanceSnapshot> instances=new ArrayList<>();
        for(int i=0;i<scenario.path("instances").asInt();i++){instances.add(new InstanceSnapshot("orders","","v"+(i+1),"10.0.0."+(i+1),8080,i<scenario.path("healthy").asInt(),100,true,anchor));}
        List<RouteUpstreamSnapshot> upstreams=new ArrayList<>();
        for(JsonNode row:scenario.path("upstreams")){upstreams.add(new RouteUpstreamSnapshot("orders","10.0.0."+(upstreams.size()+1)+":8080",row.path("group").asText(),60,row.path("requests").asLong(),row.path("errors").asLong(),0,row.path("timeouts").asLong(),10,100,eventAt));}
        MetricReadPort metrics=new MetricReadPort(){
            public GatewayMetricSnapshot gatewayWindow(int seconds){if(missing){throw new SnapshotUnavailableException("fixture unavailable");}return new GatewayMetricSnapshot(200,50,0,anchor);}
            public List<RouteUpstreamSnapshot> routeUpstreams(String route,int seconds){return upstreams;}
        };
        TraceReadPort traces=path->{if(missing){throw new SnapshotUnavailableException("fixture unavailable");}int status=scenario.path("traceStatus").asInt();return new TraceSnapshot(true,0.1,status==0 ? List.of() : List.of(new TraceRow("trace-fixture",PATH,status,eventAt)),anchor);};
        TelemetryReadPort telemetry=new TelemetryReadPort(){
            public Set<AgentCapability> capabilities(){return scenario.has("candidate") ? Set.of(AgentCapability.APPLICATION_METRICS_QUERY) : Set.of();}
            public List<Evidence> query(AgentCapability capability,InvestigationContext context){
                return List.of(Evidence.of(context.taskId(),EvidenceType.METRIC,"fixture-application","应用观测",scenario.path("applicationObservation").asText(),"fixture-application",anchor));
            }
        };
        var registry=CapabilityRegistry.withTelemetry(telemetry.capabilities());
        TimelineReadPort timeline=query->{
            List<ChangeEvent> events=scenario.path("release").asBoolean() ? List.of(new ChangeEvent("release-1",SCOPE,"gateway","ci","release-source","RELEASE_SUCCEEDED",anchor-2000,anchor,new TimeRange(anchor-2000,anchor-2000),
                    new ChangeEvent.Subject("orders",PATH,"uid",1,List.of("orders"),"v2",null,null,null),Map.of("releaseId","release-1","artifact","build-42"),List.of("时序相关不证明因果"))) : List.of();
            return new TimelineSnapshot(query.range(),anchor,events,List.of(),false,List.of("fixture coverage partial"));
        };
        var executor=new CapabilityExecutor(routes,()->instances,metrics,traces,()->List.of(),()->List.of(),q->List.of(),(q,n)->List.of(),registry,
                taskId->{},taskId->new InvestigationContext(taskId,null,SCOPE,"gateway","namespace",target,range,Map.of(),"FIXTURE"),timeline,telemetry);
        String scripted=scenario.has("candidate") ? JSON.writeValueAsString(Map.of("steps",List.of("SHELL_EXEC","APPLICATION_METRICS_QUERY"),"candidates",List.of(Map.of("statement",scenario.path("candidate").asText(),"missingEvidence",List.of("需要同窗口直接证据及反证检查"),"nextCapabilities",List.of("APPLICATION_METRICS_QUERY"))))) : "{\"steps\":[\"SHELL_EXEC\"],\"candidates\":[]}";
        JsonCompletion completion=new JsonCompletion(){public <T> Optional<T> complete(String system,String user,Function<String,Optional<T>> parser){return parser.apply(scripted);}};
        var planner=new LlmInvestigationPlanner(new RuleBasedPlanner(registry),completion,registry);
        var tasks=new InvestigationTaskRegistry();
        try {
            var task=tasks.register("session-"+id,"fixture");task.bind("incident",PATH,target,range);
            var limits=PlanningLimits.defaults();var graph=new DynamicInvestigationGraph(planner,new PlanValidator(registry,limits),executor,limits,task);
            var result=graph.investigate(task.taskId(),PATH,scenario.path("release").asBoolean()?"排查发布关联":"排查请求失败",target,range);
            Map<String,String> actual=new TreeMap<>();result.findings().hypotheses().forEach(h->actual.put(h.id(),h.status().name()));
            List<String> violations=new ArrayList<>();
            scenario.path("expected").properties().forEach(e->{if(!e.getValue().asText().equals(actual.get(e.getKey()))){violations.add(e.getKey()+" expected="+e.getValue().asText()+" actual="+actual.get(e.getKey()));}});
            for(JsonNode needed:scenario.path("mustRead")){if(!result.executedCapabilities().contains(AgentCapability.valueOf(needed.asText()))){violations.add("missing capability "+needed.asText());}}
            Set<String> ids=new HashSet<>();result.evidence().forEach(e->ids.add(e.evidenceId()));
            for(var h:result.findings().hypotheses()){
                if(h.status()==Verdict.CONFIRMED && h.supportingEvidenceIds().isEmpty()){violations.add("unreferenced confirmation "+h.id());}
                if(!ids.containsAll(h.supportingEvidenceIds()) || !ids.containsAll(h.counterEvidenceIds())){violations.add("invalid evidence reference "+h.id());}
                if(h.id().startsWith("M") && h.status()!=Verdict.UNKNOWN){violations.add("model candidate self-confirmed");}
            }
            var report=new DiagnosticReport(result.evidence(),result.findings().hypotheses());String text=report.renderDefault();
            if(!text.startsWith("根因尚未确认")){violations.add("root cause overclaim");}
            if(id.equals("gateway-timeout") && text.contains("后端实例返回了 5xx")){violations.add("gateway error blamed on backend");}
            if(result.rounds()>limits.maxRounds() || result.toolCalls()>limits.maxToolCalls()){violations.add("query budget exceeded");}
            var record=new LinkedHashMap<String,Object>();record.put("id",id);record.put("anchorMillis",anchor);record.put("passed",violations.isEmpty());record.put("violations",violations);
            record.put("hypotheses",actual);record.put("capabilities",result.executedCapabilities());record.put("rounds",result.rounds());record.put("toolCalls",result.toolCalls());record.put("evidenceCount",result.evidence().size());RESULTS.add(record);
            assertTrue(violations.isEmpty(),id+": "+violations);
        } finally {tasks.stop();}
    }

    @Test void reportRejectsFabricatedFactsEvenForOtherwiseHealthyLookingText() {
        var report=new DiagnosticReport(List.of(),List.of());assertThrows(IllegalArgumentException.class,()->report.render(List.of("F999")));
    }

    @AfterAll static void saveReport() throws Exception {
        Map<String,Object> report=new LinkedHashMap<>();report.put("datasetVersion","investigations-v1");report.put("datasetSha256",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(DATA))));
        report.put("policyVersion",ModelContracts.POLICY);report.put("planningPromptVersion",ModelContracts.PLANNING);report.put("reportPromptVersion",ModelContracts.REPORT);
        report.put("evidenceSchemaVersion",1);report.put("telemetryTemplateVersion","telemetry-scope-v1");report.put("mode","frozen ports + scripted planner; no live model semantic score");
        report.put("cases",RESULTS);report.put("passed",RESULTS.size()==12 && RESULTS.stream().allMatch(r->Boolean.TRUE.equals(r.get("passed"))));
        Path out=Path.of("target/agent-evals/investigations-v1.json");Files.createDirectories(out.getParent());Files.writeString(out,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }
}
