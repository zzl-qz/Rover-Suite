# Performance Report and Benchmark Method

This document explains how Rover-Suite was benchmarked and how to interpret the current reference numbers.

## 1. Read this first

Rover-Suite does not publish a universal QPS limit. Throughput depends on CPU, JDK, container limits, route count, upstream latency, connection reuse, network placement, metric windows and trace sampling.

The numbers below are a **local Docker Desktop reference sample**. They show the cost of the Gateway path under capped resources. They are not a Linux production SLA.

For capacity planning, run the same commands on your target machine and keep the raw output.

## 2. Why the sample may look counter-intuitive

Two parts of the current sample need careful reading:

1. **The direct demo P95/P99 is higher than the Gateway P95/P99.**  
   This usually means tail latency was affected by Docker Desktop scheduling, host activity or the demo container. It does not mean Gateway improves upstream tail latency.

2. **Gateway concurrency 100 is recorded as many 503s.**  
   That run already hit the Gateway CPU cap. It is useful for identifying the bottleneck, but it should not be mixed into stable throughput calculations.

For a stronger report, keep raw `hey` output, CPU curves, GC logs and container metrics.

## 3. Test environment

| Item | Value |
| --- | --- |
| Date | 2026-08-24 |
| Host | Apple Silicon Mac, macOS |
| Runtime | Docker Desktop for Mac |
| Docker Desktop RAM | 16 GB |
| Load tool | Host-side `hey`, hitting `127.0.0.1` |
| Base image | `eclipse-temurin:17-jre` |
| JVM heap policy | `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75.0` |
| Compose files | `docker-compose.yml` + `docker-compose.perf-small.yml` |
| Script | `./deploy/scripts/bench-small-team.sh` |

Docker Desktop on macOS has different CPU scheduling and networking from Linux production hosts. Treat these numbers as local reference data.

## 4. Topology

Admin was not started.

| Component | Port | CPU cap | Memory cap | Role |
| --- | --- | ---: | ---: | --- |
| Nameserver | 8888 / 8889 | 1.0 | 1 GB | Service discovery |
| Gateway | 8080 | 2.0 | 2 GB | Proxy `GET /api/hello` |
| demo upstream | 8082 | 0.5 | 768 MB | Direct baseline |

The Gateway path includes routing, discovery cache lookup, load balancing, HTTP proxying and default metrics. The direct path hits `127.0.0.1:8082/api/hello` and bypasses Gateway and Nameserver.

## 5. Method

```bash
brew install hey

cd /path/to/rover-suite
BENCH_KEEP=1 ./deploy/scripts/bench-small-team.sh

for i in 1 2 3; do
  hey -z 30s -c 50 http://127.0.0.1:8080/api/hello
  hey -z 30s -c 50 http://127.0.0.1:8082/api/hello
done

hey -z 30s -c 100 http://127.0.0.1:8082/api/hello
```

## 6. Reference results

### Primary comparison: hey 30s, c=50

| Run | Path | QPS | P50 | P95 | P99 | Responses | Errors |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| #1 | Via Gateway | 2560 | 12.1 ms | 65.0 ms | 79.8 ms | 76812 | 0 |
| #2 | Via Gateway | 2353 | 13.2 ms | 67.2 ms | 85.8 ms | 70606 | 0 |
| #3 | Via Gateway | 2575 | 12.1 ms | 63.8 ms | 78.8 ms | 77279 | 0 |
| #1 | Direct demo | 3907 | 2.2 ms | 85.4 ms | 90.0 ms | 117432 | 0 |
| #2 | Direct demo | 4036 | 2.1 ms | 84.8 ms | 89.7 ms | 121133 | 0 |
| #3 | Direct demo | 4216 | 2.0 ms | 84.0 ms | 90.4 ms | 126565 | 0 |

### Summary

| Path | QPS avg | QPS range | P50 avg | P95 avg | P99 avg |
| --- | ---: | --- | ---: | ---: | ---: |
| Via Gateway | 2496 | 2353–2575 | 12.5 ms | 65 ms | 82 ms |
| Direct demo | 4053 | 3907–4216 | 2.1 ms | 85 ms | 90 ms |

In this local sample, Gateway retained about 62% of direct throughput and added about 10 ms to median latency.

## 7. How to produce a more credible report

For release notes, evaluation or capacity planning, add:

1. Raw `hey` output for every run.
2. At least 5–10 rounds with average, min, max and standard deviation.
3. CPU, memory, GC and thread metrics for Gateway, Nameserver and upstream.
4. Whether Admin was off or whether live polling was open.
5. Fixed route count, payload size, connection reuse and log level.
6. A Linux target-machine run instead of relying only on Docker Desktop.
7. A separate section for failed runs, instead of mixing them into successful throughput.
