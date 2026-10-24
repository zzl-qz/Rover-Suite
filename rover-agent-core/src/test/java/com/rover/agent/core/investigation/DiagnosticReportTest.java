package com.rover.agent.core.investigation;

import static org.junit.jupiter.api.Assertions.*;
import com.rover.agent.core.model.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DiagnosticReportTest {
    @Test void modelSelectionCannotInventFactsOrHideTheOtherVersion() {
        Evidence evidence=Evidence.of("t",EvidenceType.TRACE,"gateway","trace","sample","/api/traces?path=/orders",10);
        evidence=evidence.withEnvelope(new EvidenceEnvelope(1,"gateway.traces","TRACE","q","v1",new TimeRange(1,10),null,null,10,null,
                "SAMPLED",120,EvidenceQuality.PARTIAL,null,List.of(),Map.of("samplePopulations",List.of(
                Map.of("routeId","r","group","v1","sampleRequests",62,"sample5xx",0,"sample5xxRate",0),
                Map.of("routeId","r","group","v2","sampleRequests",58,"sample5xx",58,"sample5xxRate",1))),List.of(),null,null,"v1"));
        var report=new DiagnosticReport(List.of(evidence),List.of());
        String rendered=report.render(List.of("F2"));
        assertTrue(rendered.contains("版本=v1") && rendered.contains("版本=v2"));
        assertTrue(rendered.contains("请求=58，5xx=58，sample5xxRate=1"));
        assertTrue(rendered.contains("不代表完整流量"));
        assertTrue(rendered.contains("根因尚未确认"));
        assertThrows(IllegalArgumentException.class,()->report.render(List.of("fabricated")));
        assertThrows(IllegalArgumentException.class,()->report.render(List.of("F1","F1")));
    }

    @Test void baselineOnlyTimelineDoesNotBecomeAHealthyConfigurationClaim() {
        Evidence event=Evidence.of("t",EvidenceType.EVENT,"timeline","timeline","baseline","/api/agent/timeline",10);
        event=event.withEnvelope(new EvidenceEnvelope(1,"rover.change-timeline","EVENT","q","v1",null,null,null,10,null,"PARTIAL",1,
                EvidenceQuality.PARTIAL,null,List.of(),Map.of("events",List.of(Map.of("kind","CONFIG_BASELINE"))),List.of(),null,null,"v1"));
        String rendered=new DiagnosticReport(List.of(event),List.of()).renderDefault();
        assertTrue(rendered.contains("不能证明没有变更或配置正常"));
        assertTrue(rendered.contains("尚未执行修复"));
        assertFalse(rendered.contains("配置没有异常"));
    }
}
