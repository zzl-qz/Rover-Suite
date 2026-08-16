package com.rover.gateway.core.trace;

import java.util.List;

/**
 * Author: Daylight
 * Description: 单次请求的链路时间线：traceId + 各阶段耗时。
 * 阶段划分（网关内部，非完整分布式追踪）：receive 接收 / route 路由匹配 / proxy 上游代理 / write 响应写回。
 * 其中 proxy 包含连接上游与上游处理（java.net.http 不暴露两者的拆分点，故合并统计）。
 */
public class RequestTrace {

    private final String traceId;
    private final String method;
    private final String path;
    private final String routeId;
    private final String targetUrl;
    private final int statusCode;
    private final long startMillis;
    private final long totalCostMs;
    private final boolean slow;
    private final List<Phase> phases;

    public RequestTrace(
            String traceId,
            String method,
            String path,
            String routeId,
            String targetUrl,
            int statusCode,
            long startMillis,
            long totalCostMs,
            boolean slow,
            List<Phase> phases) {
        this.traceId = traceId;
        this.method = method;
        this.path = path;
        this.routeId = routeId;
        this.targetUrl = targetUrl;
        this.statusCode = statusCode;
        this.startMillis = startMillis;
        this.totalCostMs = totalCostMs;
        this.slow = slow;
        this.phases = phases;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getMethod() {
        return method;
    }

    public String getPath() {
        return path;
    }

    public String getRouteId() {
        return routeId;
    }

    public String getTargetUrl() {
        return targetUrl;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public long getStartMillis() {
        return startMillis;
    }

    public long getTotalCostMs() {
        return totalCostMs;
    }

    public boolean isSlow() {
        return slow;
    }

    public List<Phase> getPhases() {
        return phases;
    }

    /** 单阶段耗时（毫秒）。 */
    public static final class Phase {
        private final String name;
        private final long costMs;

        public Phase(String name, long costMs) {
            this.name = name;
            this.costMs = costMs;
        }

        public String getName() {
            return name;
        }

        public long getCostMs() {
            return costMs;
        }
    }
}
