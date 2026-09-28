package com.rover.agent.core.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.agent.core.model.Evidence;
import com.rover.agent.core.model.EvidenceType;
import com.rover.agent.core.model.Hypothesis;
import com.rover.agent.core.model.InvestigationReport;
import com.rover.agent.core.model.Confidence;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.TaskStatus;
import com.rover.agent.core.model.TaskView;
import com.rover.agent.core.model.Verdict;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResourceNotesTest {

    @Test
    void confirmedHypothesisBecomesAResourceNote() {
        TaskView task = task(new Hypothesis("H1", "上游实例返回 5xx", Verdict.CONFIRMED, "证据", List.of("指标")));

        ResourceNote note = ResourceNotes.capture(task, 99L);

        assertEquals("/api/order", note.resourceKey());
        assertEquals("上游实例返回 5xx", note.rootCause());
        assertEquals("Gateway 实时指标", note.usefulSteps());
        assertTrue(ResourceNotes.prompt(note).contains("历史结论"));
    }

    @Test
    void unconfirmedGuessIsNotANote() {
        TaskView task = task(new Hypothesis("H1", "就是发布引起的", Verdict.UNKNOWN, "", List.of()));

        assertNull(ResourceNotes.capture(task, 1L));
        assertNull(ResourceNotes.resourceKey(ResourceTarget.instance("10.0.0.1:8080")));
    }

    private static TaskView task(Hypothesis hypothesis) {
        InvestigationReport report = new InvestigationReport("结论", Confidence.MEDIUM,
                List.of(Evidence.of("t1", EvidenceType.METRIC, "Gateway 实时指标", "5xx", "有 5xx", "/api/live", 1L)),
                List.of("样本只有 3 条"), List.of(hypothesis), null);
        return new TaskView("t1", "s1", "i1", TaskStatus.COMPLETED, null, "/api/order",
                ResourceTarget.route("/api/order"), "为什么 /api/order 失败", 1L, 2L, List.of(), report, null, null);
    }
}
