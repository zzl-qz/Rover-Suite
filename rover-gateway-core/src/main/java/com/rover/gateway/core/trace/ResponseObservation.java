package com.rover.gateway.core.trace;

import java.util.List;

/** 请求结束时冻结的事实；origin 来自执行路径，不能由 HTTP 状态码猜测。 */
public record ResponseObservation(String routeId, String resourceUid, long generation,
                                  String serviceName, String group, String hostPort,
                                  String responseOrigin, String failurePhase, String rejectReason,
                                  boolean responseComplete, List<Attempt> attempts) {
    public ResponseObservation {
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
    }

    public record Attempt(String hostPort, String serviceName, String group, int statusCode,
                          String responseOrigin, String failurePhase, long costMillis,
                          boolean responseComplete) { }
}
