package com.rover.agent.runtime.task;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 解读增量流：晚连上的订阅者靠补发对齐前缀，增量既不漏也不重。 */
class InvestigationTaskAnalysisStreamTest {

    private final InvestigationTask task = new InvestigationTask("task-1", "session-1", "incident-1",
            "/api/demo/tt", com.rover.agent.core.model.ResourceTarget.route("/api/demo/tt"), "为什么失败？",
            view -> { });

    @Test
    void replaysProducedTextAndStreamsLaterDeltasExactlyOnce() {
        RecordingListener early = new RecordingListener();
        task.subscribeAnalysis(early);
        task.appendAnalysis("路由已命中");
        task.appendAnalysis("，实例缺失");

        RecordingListener late = new RecordingListener();
        task.subscribeAnalysis(late);
        task.appendAnalysis("。");

        task.closeAnalysis();
        // 关闭后再来的增量与重复关闭都不再产生事件：前端只该看到一次结束。
        task.appendAnalysis("（这段不该出现）");
        task.closeAnalysis();

        RecordingListener afterClose = new RecordingListener();
        task.subscribeAnalysis(afterClose);

        assertEquals(List.of("snapshot:", "delta:路由已命中", "delta:，实例缺失", "delta:。", "complete"),
                early.events());
        // 补发的是"订阅前已产生的全文"，不含登记之后才产生的增量，因此不会重复。
        assertEquals(List.of("snapshot:路由已命中，实例缺失", "delta:。", "complete"), late.events());
        assertEquals(List.of("snapshot:路由已命中，实例缺失。", "complete"), afterClose.events());
    }

    @Test
    void unsubscribedListenerStopsReceivingDeltas() {
        RecordingListener listener = new RecordingListener();
        task.subscribeAnalysis(listener);
        task.unsubscribeAnalysis(listener);
        task.appendAnalysis("不该收到");
        task.closeAnalysis();

        assertEquals(List.of("snapshot:"), listener.events());
    }

    /** 记录收到的事件，形如 snapshot:全文、delta:片段、complete。 */
    private static final class RecordingListener implements AnalysisStreamListener {

        private final List<String> events = new ArrayList<>();

        @Override
        public void onSnapshot(String text) {
            events.add("snapshot:" + text);
        }

        @Override
        public void onDelta(String chunk) {
            events.add("delta:" + chunk);
        }

        @Override
        public void onComplete() {
            events.add("complete");
        }

        private List<String> events() {
            return List.copyOf(events);
        }
    }
}