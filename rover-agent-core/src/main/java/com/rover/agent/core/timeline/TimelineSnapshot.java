package com.rover.agent.core.timeline;

import com.rover.agent.core.model.EvidenceQuality;
import com.rover.agent.core.model.TimeRange;
import java.util.List;
import java.util.Map;

public record TimelineSnapshot(TimeRange requestedRange, long observedAtMillis, List<ChangeEvent> events,
                               List<Map<String, Object>> sources, boolean truncated, List<String> limitations) {
    public TimelineSnapshot {
        events = List.copyOf(events); sources = List.copyOf(sources); limitations = List.copyOf(limitations);
    }
    /** Polling and push sources cannot prove a complete history, including when zero events matched. */
    public EvidenceQuality quality() { return EvidenceQuality.PARTIAL; }
}
