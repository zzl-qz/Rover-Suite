/** live / overview 缺数据时的空壳，避免模板 NPE。 */
const EMPTY_TRAFFIC = {
    instantQps: 0, currentSecondRequests: 0, qps5s: 0, qps60s: 0, qps300s: 0,
    instantAvgMillis: 0, totalRequests: 0, avgMillis: 0, p95Millis: 0, p99Millis: 0,
    maxMillis: 0, errorRequests: 0, idle: true,
    status: { '2xx': 0, '3xx': 0, '4xx': 0, '5xx': 0 },
};
const EMPTY_RES = { activeConnections: 0, inflightRequests: 0, upstreamInFlight: 0 };
const EMPTY_ERR = { routeUnmatched: 0, proxyTimeout: 0, upstreamConnectFail: 0 };
const EMPTY_INSTANT = { heartbeat: 0, total: 0, push: 0, register: 0 };

window.RoverAdminConstants = { EMPTY_TRAFFIC, EMPTY_RES, EMPTY_ERR, EMPTY_INSTANT };
