package com.rover.agent.core.snapshot;

import java.util.List;

/** 与请求同一时刻冻结的身份和转发事实；旧 Gateway 的 observation 为 null。 */
public record TraceObservation(String routeId, String resourceUid, long generation, String serviceName,
                                String group, String hostPort, String responseOrigin, String failurePhase,
                                String rejectReason, boolean responseComplete, List<Attempt> attempts) {
    public TraceObservation { attempts = attempts == null ? List.of() : List.copyOf(attempts); }
    public record Attempt(String hostPort, String serviceName, String group, int statusCode,
                          String responseOrigin, String failurePhase, long costMillis, boolean responseComplete) { }
}
