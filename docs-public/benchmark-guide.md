# Benchmark Guide

This guide explains how to benchmark Rover-Suite in a reproducible way. It is useful for local evaluation, pre-deployment capacity checks, and finding the main bottleneck in the Gateway path.

If you only want to try the project, start with the [Quick Start](./quick-start.md). Run benchmarks after Nameserver, Gateway, and the demo service work correctly.

## 1. Goal

A useful benchmark should explain where time is spent, not only report QPS.

Try to answer:

- How fast the upstream service is when called directly.
- How much overhead the minimal Gateway proxy path adds.
- How much overhead Nameserver-based discovery adds compared with static upstreams.
- How much metrics, trace, access log, rate limit, custom Filter, and custom LoadBalancer add.
- Whether route count, payload size, or upstream latency increases P95/P99.
- Where the stable throughput knee is.
- How the result compares with an Nginx reverse-proxy baseline.

## 2. Recommended order

Change one variable at a time.

```text
Direct demo
  ↓
Gateway static minimal proxy
  ↓
Gateway + Nameserver discovery
  ↓
Gateway + metrics / trace / access log / rate limit
  ↓
Gateway + custom Filter / custom LoadBalancer
  ↓
Payload size, route count, upstream latency, saturation
  ↓
Nginx baseline
```

If the static minimal Gateway path is already slow, investigate proxying, thread hops, request/response aggregation, and object allocation before enabling more features.

## 3. Record the environment

Benchmark results are only meaningful with their environment.

| Field | What to record |
| --- | --- |
| Git commit | Source version |
| OS | Windows / Linux / macOS |
| CPU | Model and core count |
| Memory | Total memory |
| JDK | Version and distribution |
| Startup mode | Docker / local Java / IDE |
| Docker limits | CPU, memory, network mode |
| Load tool | `hey` / `wrk` version |
| Gateway JVM args | Heap, GC, thread-related flags |
| Admin state | Whether Admin is running and whether `gateway.adminEnabled` is enabled |

If you use Docker Desktop, state that clearly. Docker Desktop can affect CPU scheduling and local networking, so those numbers should not be treated as Linux production numbers.

## 4. Basic command

`hey` is enough for a first pass.

```bash
hey -z 30s -c 50 http://127.0.0.1:80/api/hello
```

Run each case at least five times and keep the raw output.

### 4.1 Repository scripts (Phase A)

| Script | Purpose |
| --- | --- |
| `deploy/scripts/bench-phase-a.sh` | Direct / Gateway static / Gateway+NS on `GET /api/hello` |
| `deploy/scripts/bench-phase-a-payload.sh` | Historical wave-1 A/B (checks out HEAD sources; do not run on a dirty tree) |
| `deploy/scripts/bench-phase-a-payload-remasure.sh` | Current-code remasure: large GET + POST `/api/ingest`, Netty vs JDK outbound |
| `deploy/scripts/bench-phase-a-static-ab.sh` | Same-session A/B for static upstream snapshot; redo if Direct drifts >10% |

Example:

```bash
chmod +x deploy/scripts/bench-phase-a-payload-remasure.sh
SIZES="262144 1048576 4194304" CONCURRENCY=20 DURATION=20s ROUNDS=3 \
  ./deploy/scripts/bench-phase-a-payload-remasure.sh
```

See [Performance Report](./performance-report.md) §7 for how to read the results.

## 5. Layered tests

### 5.1 Direct demo

Call the upstream service directly:

```bash
hey -z 30s -c 50 http://127.0.0.1:8082/api/hello
```

This is the upstream baseline. If the demo service is already slow, do not attribute that latency to Gateway.

### 5.2 Gateway static minimal path

Use a static upstream and disable metrics, trace, rate limit, and request-level logging.  
Phase A configs (`rover-gateway-static.yml` / `rover-gateway-ns-min.yml`) already set `metrics.enabled=false`, `trace.enabled=false`, and `dispatchOnEventLoop=false`. When disabled, the hot path no longer calls `markPhase`, records inflight, or mints a trace UUID.

This measures the base proxy cost:

```text
Gateway base overhead = Gateway static latency - Direct demo latency
```

### 5.3 Gateway + Nameserver

Switch discovery to Nameserver while keeping other features disabled.

This measures discovery cache lookup, instance selection, and load-balancing overhead:

```text
Nameserver overhead = Gateway Nameserver latency - Gateway static latency
```

Gateway should not query Nameserver remotely on every request. The request path should primarily read local cached instances.

### 5.4 Feature overhead

Enable one feature at a time.

| Case | Purpose |
| --- | --- |
| metrics off/on | Metric collection cost |
| trace off/on | Trace collection cost |
| trace `sampleRate=1` | Full tracing cost |
| access log off/on | Request logging cost |
| rate limit off/on | Local rate-limit decision cost |
| empty Filter plugin | Filter framework cost |
| lightweight Filter plugin | Typical business plugin cost |
| custom LoadBalancer | Instance-selection algorithm cost |

Avoid DEBUG logs during throughput tests. Heavy console logging measures log I/O, not Gateway performance.

### 5.5 Payload, route count, upstream latency

| Variable | Suggested values | What it shows |
| --- | --- | --- |
| Payload size | 0B, 10KB, 100KB, 1MB | Aggregation, copy, and GC cost |
| Route count | 1, 10, 100, 1000 | Route matching scalability |
| Upstream delay | 10ms, 50ms, 100ms, 500ms, 2s | Queuing and connection behavior |
| Concurrency | 10, 25, 50, 100, 200, 400 | Throughput and tail-latency knees |

If larger payloads greatly increase P99, look at body aggregation, byte-array copies, and GC.

If more routes increase latency linearly, route matching likely needs a better index.

If CPU is low but P99 is high, look at connection pools, thread queues, and upstream waiting.

## 6. Result fields

Record the same fields for every case.

| Field | Meaning |
| --- | --- |
| case id | Benchmark case id |
| URL | Full request URL |
| HTTP method | GET / POST |
| concurrency | Concurrent clients |
| duration | Test duration |
| repeat | Run number |
| QPS | Requests/sec |
| average latency | Mean latency |
| P50 | Median latency |
| P95 | Tail latency |
| P99 | High-percentile tail latency |
| Max | Max latency |
| 2xx / 4xx / 5xx | Status distribution |
| timeout | Timeout count |
| error rate | Failed request ratio |
| Gateway CPU / memory / GC | Gateway resource usage |
| Nameserver CPU / memory | Required for Nameserver mode |
| Demo CPU / memory / GC | Upstream resource usage |
| config snapshot | YAML, Admin state, plugin state |
| raw output file | Original `hey` or `wrk` output |

For stable throughput, check error rate first, then P99, then QPS.

## 7. Plugin tests

Filters and LoadBalancers are both on the request path, so plugin benchmarks should record how the plugin is loaded and what it does.

| Field | Meaning |
| --- | --- |
| plugin type | Filter / LoadBalancer |
| plugin name | `getName()` or strategy name |
| class name | Fully qualified class name |
| JAR path | Example: `plugins/demo-plugin.jar` |
| loading mode | SPI / explicit class path config |
| YAML declaration | Whether explicit config is required |
| remote call | Whether the plugin calls another service |
| blocking I/O | Whether the plugin blocks the request path |
| request-level logging | Whether it logs every request |
| load verification | Gateway startup log |
| behavior verification | Request result or business log |

SPI loading is convenient when a plugin should be discovered automatically. Explicit class path config is more flexible when different environments need different plugins.

## 8. Nginx comparison

Nginx is a useful reverse-proxy baseline. It has a different goal from Rover Gateway, but the comparison helps identify whether Rover’s basic proxy path is heavy.

Compare at least:

| Case | Purpose |
| --- | --- |
| Direct demo | Upstream baseline |
| Nginx -> demo | Dedicated reverse-proxy baseline |
| Rover Gateway static path | Rover base proxy cost |
| Rover Gateway + Nameserver | Rover discovery path cost |

Nginx is expected to be faster in a pure proxy scenario. It is a mature dedicated proxy with a short hot path. Rover Gateway also includes Java Filter, discovery, routing, load balancing, metrics, and plugin extension.

The useful comparison is:

```text
Rover static path - Direct demo
Rover Nameserver path - Rover static path
Each feature enabled - Rover minimal path
```

## 9. Common bottleneck signals

| Symptom | Possible cause | Next step |
| --- | --- | --- |
| Direct demo is slow | Upstream or machine bottleneck | Fix upstream baseline first |
| Gateway static path is much slower | Proxying, thread hops, aggregation, allocation | Inspect Gateway hot path |
| Nameserver mode is much slower | Discovery cache, instance selection, LB | Check remote calls, list copies, locks |
| metrics increases P99 | Metric hot-path cost | Reduce labels and allocations |
| full trace is slow | Trace recording cost | Use sampling or slow-request tracing |
| access log is slow | Request-level log I/O | Use async logs or reduce log volume |
| larger payloads increase P99 | Aggregation, copy, GC | Limit sizes or evaluate streaming proxy |
| more routes increase latency linearly | Route matching structure | Precompile routes or add an index |
| low CPU but high latency | Queues, connections, upstream wait | Capture thread dumps and in-flight metrics |
| high CPU but low QPS | CPU hotspot or allocation pressure | Use a profiler or JFR |

## 10. Report structure

A public benchmark report should include:

```text
1. Environment
2. Topology
3. Method
4. Direct demo baseline
5. Gateway static path
6. Gateway + Nameserver
7. Feature overhead
8. Plugin overhead
9. Payload / route count / upstream latency
10. Nginx baseline
11. Bottleneck analysis
12. Planned optimizations
13. Raw data location
```

Keep raw outputs and configuration snapshots when publishing benchmark numbers. That makes the result easier to reproduce and easier for users to compare with their own environment.
