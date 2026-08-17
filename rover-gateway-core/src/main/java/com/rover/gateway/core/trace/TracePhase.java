package com.rover.gateway.core.trace;

/** Gateway 请求时间线的标准阶段。 */
public enum TracePhase {

    RECEIVE("receive"),
    FILTER("filter"),
    ROUTE("route"),
    DISCOVERY("discovery"),
    LOAD_BALANCE("loadbalance"),
    PROXY("proxy"),
    WRITE("write");

    private final String phaseName;

    TracePhase(String phaseName) {
        this.phaseName = phaseName;
    }

    public String phaseName() {
        return phaseName;
    }
}
