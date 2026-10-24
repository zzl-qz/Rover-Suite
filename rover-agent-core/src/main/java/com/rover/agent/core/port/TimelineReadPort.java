package com.rover.agent.core.port;

import com.rover.agent.core.timeline.TimelineQuery;
import com.rover.agent.core.timeline.TimelineSnapshot;

@FunctionalInterface
public interface TimelineReadPort {
    TimelineSnapshot query(TimelineQuery query);
}
