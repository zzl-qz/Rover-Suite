package com.rover.agent.runtime.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.agent.core.journal.ResourceNotes;
import com.rover.agent.core.model.Confidence;
import com.rover.agent.core.model.Hypothesis;
import com.rover.agent.core.model.InvestigationReport;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.TaskStatus;
import com.rover.agent.core.model.TaskView;
import com.rover.agent.core.model.Verdict;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;

class H2InvestigationLogTest {

    @Test
    void reopensSnapshotAndConfirmedNote() throws Exception {
        String path = Files.createTempDirectory("rover-invest").resolve("rover").toString();
        TaskView done = task(TaskStatus.COMPLETED, new Hypothesis("H1", "上游实例返回 5xx", Verdict.CONFIRMED, "", List.of()));
        TaskView running = task(TaskStatus.RUNNING, null);
        running = new TaskView("t2", "s1", "i1", TaskStatus.RUNNING, null, "/api/order",
                ResourceTarget.route("/api/order"), "还在查", 3L, 0L, List.of(), null, null, null);

        try (H2InvestigationLog first = new H2InvestigationLog(path)) {
            first.save(done);
            first.save(running);
            first.saveNote(ResourceNotes.capture(done, 50L));
        }

        try (H2InvestigationLog second = new H2InvestigationLog(path)) {
            assertEquals(TaskStatus.COMPLETED, second.find("t1").orElseThrow().status());
            assertEquals("上游实例返回 5xx", second.find("t1").orElseThrow().result().summary().isBlank()
                    ? second.findNote("/api/order").orElseThrow().rootCause()
                    : second.findNote("/api/order").orElseThrow().rootCause());
            assertEquals(TaskStatus.FAILED, second.find("t2").orElseThrow().status());
            assertTrue(second.find("t2").orElseThrow().error().contains("重启"));
            assertEquals(List.of("t1", "t2").size(), second.findBySessionId("s1").size());
        }
    }

    private static TaskView task(TaskStatus status, Hypothesis hypothesis) {
        InvestigationReport report = hypothesis == null ? null : new InvestigationReport("上游实例返回 5xx",
                Confidence.HIGH, List.of(), List.of(), List.of(hypothesis), null);
        return new TaskView("t1", "s1", "i1", status, null, "/api/order", ResourceTarget.route("/api/order"),
                "为什么失败", 1L, 2L, List.of(), report, null, null);
    }
}
