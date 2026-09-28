package com.rover.agent.core.journal;

import com.rover.agent.core.model.Evidence;
import com.rover.agent.core.model.Hypothesis;
import com.rover.agent.core.model.InvestigationReport;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.TargetType;
import com.rover.agent.core.model.TaskView;
import com.rover.agent.core.model.Verdict;
import java.util.stream.Collectors;

/** 从一次调查里抽出资源笔记。没有被证据确认的假设，不产生笔记。 */
public final class ResourceNotes {

    private ResourceNotes() {
    }

    /** 路由或服务才能作为笔记的键。实例和未知目标不记。 */
    public static String resourceKey(ResourceTarget target) {
        if (target == null || target.type() == TargetType.UNKNOWN || target.type() == TargetType.INSTANCE
                || target.value() == null || target.value().isBlank()) {
            return null;
        }
        return target.value();
    }

    /** 没有已确认根因时返回空。 */
    public static ResourceNote capture(TaskView task, long nowMillis) {
        if (task == null || task.result() == null) {
            return null;
        }
        String key = resourceKey(task.target());
        if (key == null) {
            return null;
        }
        String cause = confirmedCause(task.result());
        if (cause == null) {
            return null;
        }
        String steps = task.result().evidence() == null ? "" : task.result().evidence().stream()
                .map(Evidence::source)
                .filter(source -> source != null && !source.isBlank())
                .distinct()
                .limit(8)
                .collect(Collectors.joining("、"));
        String pitfalls = task.result().limitations() == null ? "" : task.result().limitations().stream()
                .filter(item -> item != null && !item.isBlank())
                .limit(5)
                .collect(Collectors.joining("；"));
        String symptom = task.question() == null ? "" : clip(task.question(), 200);
        return new ResourceNote(key, symptom, cause, steps, pitfalls, task.sessionId(), task.taskId(), nowMillis);
    }

    /** 给模型看的一段历史。当前现网仍以本次工具为准。 */
    public static String prompt(ResourceNote note) {
        if (note == null || note.rootCause() == null || note.rootCause().isBlank()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        text.append("该资源上次已确认的记录：根因是").append(note.rootCause()).append('。');
        if (note.usefulSteps() != null && !note.usefulSteps().isBlank()) {
            text.append("当时的证据来源：").append(note.usefulSteps()).append('。');
        }
        if (note.pitfalls() != null && !note.pitfalls().isBlank()) {
            text.append("当时的局限：").append(note.pitfalls()).append('。');
        }
        text.append("这是历史结论，当前状态以这次工具读到的数据为准。");
        return text.toString();
    }

    private static String confirmedCause(InvestigationReport report) {
        if (report.hypotheses() == null) {
            return null;
        }
        String cause = report.hypotheses().stream()
                .filter(item -> item != null && item.status() == Verdict.CONFIRMED && item.statement() != null
                        && !item.statement().isBlank())
                .map(Hypothesis::statement)
                .collect(Collectors.joining("；"));
        return cause.isBlank() ? null : clip(cause, 500);
    }

    private static String clip(String text, int max) {
        String flat = text.replace('\n', ' ').trim();
        return flat.length() <= max ? flat : flat.substring(0, max);
    }
}
