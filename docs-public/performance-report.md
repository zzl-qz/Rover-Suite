# Performance Report and Benchmark Method

This document explains the current local reference numbers. If you want to benchmark Rover-Suite in your own environment, read the [Benchmark Guide](./benchmark-guide.md) first and use this report as a result format reference.

## 1. Read this first

Rover-Suite does not publish a universal QPS limit. Throughput depends on CPU, JDK, container limits, route count, upstream latency, connection reuse, network placement, metric windows and trace sampling.

The numbers below are **local Docker Desktop reference samples** (updated 2026-08-25 with streaming proxy before/after comparisons). They show the cost of the Gateway path under capped resources. They are not a Linux production SLA.

For capacity planning, run the same commands on your target machine and keep the raw output.

### 1.1 What each change did, by scenario

Same host, Docker Desktop, `perf-fair` (Gateway **2 CPU / 2 GB**). Empty cells were **not** re-measured.

**Small JSON: `GET /api/hello`**

| Wave | Change | Steady c=50 (success QPS / errors) | High load c=100 (success QPS / errors) | In one line |
| --- | --- | --- | --- | --- |
| 1 | Stream upstream responses (JDK outbound still) | About **2576→2088 (−19%)**, all 200 | About **1795→2347 (+31%)**; 503 **~90%→2%** | No steady-QPS win; fewer 503s under load |
| 2 | Snapshot static upstreams at config time | Retention **11.97%→12.13%** (~2025→2215) | Not the point of this change | **No QPS lift**; correctness for hot reload |
| 3 | Metrics/trace off + hop-by-hop constants; EventLoop was an experiment | Retention **12.13%→12.53%** (~2215→2138) | EventLoop experiment: 503 **~2%→12%** | **Almost no lift**; EventLoop stays off by default |
| 4 | Netty outbound (JDK path kept) | About **2100→6800 (~3.2×)**; vs direct **12%→40%**; all 200 | Success ~**8830**; 503 ~**5.6%** | **The only small-body QPS jump** |
| 5 | Inbound no Aggregator; body piped | Remeasure ~**6610**, all 200; same band as 6800 | Remeasure success ~**8300–8400**; 503 ~**4%** | **GET hello did not rise**; POST can overlap connect |

**Large responses: `GET /api/payload?size=N` (c=20)**

| Scenario | Wave 1 streaming vs buffered | Current remasure (after waves 4–5, `184143`) |
| --- | --- | --- |
| QPS at 256 KB / 1 MB / 4 MB | **About the same (±5%)** | vs wave-1 streaming: about **918 / 228 / 70** (**−5% / −14% / flat**). **No QPS lift** |
| Gateway memory | About **−55% to −65%** (4 MB: ~1.2 GiB → ~440 MiB) | Lower still: about **170 / 189 / 278 MiB** (4 MB about **−37%** vs wave-1 streaming) |

**Large request bodies: `POST /api/ingest` (the wave-5 case; first numbers, c=20)**

| Size | Gateway success QPS (median) | Same-run direct | vs direct | Gateway memory |
| --- | ---: | ---: | ---: | ---: |
| 256 KB | about **808** | about 1232 | **66%** | about 267 MiB |
| 1 MB | about **239** | about 285 | **84%** | about 267 MiB |
| 4 MB | about **66** | about 84 | **78%** | about 302 MiB |

Waves 2–3 still have no isolated large-body A/B (those changes do not touch the body path). The JDK-outbound GET half had Direct collapse; do **not** use that absolute QPS. Details: §8.3.

**Cumulative (steady small JSON):** JDK-outbound era ~**2100 QPS / 12% of direct** → now ~**6800 QPS / 40% of direct**. Almost all of that jump is wave 4. Wave 1 bought high-load success and large-body memory. Later waves did not raise large-body QPS; they cut more memory.

## 2. Why the sample may look counter-intuitive

1. **Direct demo P95/P99 can be higher than Gateway P95/P99.**  
   This usually reflects Docker Desktop scheduling or demo container jitter, not Gateway improving upstream tail latency.

2. **Gateway concurrency 100 produced many 503s on the old buffered proxy.**  
   Streaming write-back improves successful throughput under that load (see §7). Do not mix overload runs into stable throughput math.

Treat these numbers as reference samples, not a formal benchmark.

## 3. Test environment

| Item | Value |
| --- | --- |
| Dates | 2026-08-24 (small-team sample); 2026-08-25 (Phase A + streaming) |
| Host | Apple Silicon Mac, macOS |
| Runtime | Docker Desktop for Mac |
| Load tool | Host-side `hey` against `127.0.0.1` |
| Compose | `docker-compose.yml` + `perf-fair.yml` (Phase A) or `perf-small.yml` (early sample) |
| Scripts | `bench-phase-a.sh`, `bench-phase-a-payload.sh` |

Docker Desktop on macOS differs from Linux production hosts in CPU scheduling and networking.

## 4. Topology (Phase A / streaming comparison)

Admin was not started.

| Component | Port | CPU cap | Memory cap |
| --- | --- | ---: | ---: |
| Nameserver | 8888 / 8889 | 1.0 | 1 GB |
| Gateway | 8080 | 2.0 | 2 GB |
| demo upstream | 8082 | 2.0 | 1 GB |

Config: `accessLog=false`, `rateLimit=false`. Paths: `GET /api/hello`, `GET /api/payload?size=N`, or `POST /api/ingest`.

## 5. Method

```bash
brew install hey
cd /path/to/rover-suite

./deploy/scripts/bench-phase-a.sh

chmod +x deploy/scripts/bench-phase-a-payload-remasure.sh
SIZES="262144 1048576 4194304" CONCURRENCY=20 DURATION=20s ROUNDS=3 \
  ./deploy/scripts/bench-phase-a-payload-remasure.sh
```

Early small-team numbers use `./deploy/scripts/bench-small-team.sh` (see §6).

## 6. Early reference results (perf-small, 2026-08-24)

| Path | QPS (mean, c=50) | P50 (mean) |
| --- | ---: | ---: |
| Via Gateway | 2496 | 12.5 ms |
| Direct demo | 4053 | 2.1 ms |

Gateway at c=100 hit the 2-CPU cap with many 503s.

## 7. Streaming proxy write-back (2026-08-25)

### What changed

- **Before**: upstream response aggregated to `byte[]`, then written as one `FullHttpResponse`.
- **After**: `HttpResponse` + `HttpContent` streamed to the client.
- **Not changed at that time**: inbound `FullHttpRequest` aggregation; outbound was still JDK `HttpClient` (kept; see §8.2).

### Summary

| Scenario | Change | Notes |
| --- | --- | --- |
| Small JSON (`/api/hello`, c=50) | successful RPS **~−19%** | framing overhead on tiny bodies |
| Small JSON (c=100 overload) | successful RPS **+31% to +36%**; 503 **~90% → ~2%** | stability, not P50 |
| Large body (256 KB–4 MB, c=20) | successful RPS **within ±5%** of buffered | bandwidth/upstream bound |
| Gateway memory under load | **~−55% to −65%** | 4 MB: ~1.2 GiB buffered → ~440 MiB streamed |

Streaming is not a universal QPS win. It mainly reduces **memory peaks** and **503 storms** under pressure.

### Capacity (2 CPU / 2 GB, local Docker Desktop)

These numbers describe the knee of the **minimal proxy + tiny JSON** path. They are not an SLA and are **not for Nginx / Envoy QPS comparisons**.

| Band | Concurrency (`hey -c`) | Successful throughput (approx.) | Errors | How to read |
| --- | ---: | --- | --- | --- |
| Steady (JDK outbound era) | 50 | ~2100 QPS, P50 ~14 ms | 0 | Historical band while upstream was JDK `HttpClient` |
| Knee (JDK + biz pool) | 100 | ~2300 successful QPS | ~2% 503 | Historical; do not mix 503s into the score |
| Knee (JDK + EventLoop experiment) | 100 | ~2280 successful QPS | ~12% 503 | Historical; default is still the biz pool |

Those rows are the **JDK outbound** era. After the Netty outbound change, steady QPS is about **6800** and about **40%** of same-run Direct. See §8.2.

### When to use

- **Good fit**: downloads, exports, large JSON, unknown body sizes, tight memory, high concurrency proxying.
- **Do not rely on streaming alone for**: tiny JSON latency; filters that must read the full request body (bounded buffering still required).

### Trade-offs

| | Buffered | Streaming (current default) |
| --- | --- | --- |
| Complexity | lower | higher |
| Small response QPS | slightly higher | slightly lower (local sample) |
| Success rate under overload | many 503s | much higher success RPS |
| Large response memory | high, near container limit | much lower |
| Large response QPS (low concurrency) | similar to streaming | similar to buffered |

Raw runs (private backup): `bench-results/2026-08-25-103836-phase-a`, `2026-08-25-115329-phase-a-stream-clean`, `2026-08-25-123108-phase-a-payload-ab`.

## 8. Static upstream snapshot (2026-08-25)

Static routes no longer parse URLs on every request. `rebuild` runs at startup and on `applyRoutes`.

A cross-run comparison is **invalid** if Direct itself moved. The accepted method is a same-session A/B (no-cache then cache), each side starting with Direct. **Qualify only if Direct c=50 medians differ by ≤ 10%.**

Qualified: `2026-08-25-132633-phase-a-static-ab` (Direct drift **7.8%**).  
Discarded: `2026-08-25-130701-phase-a-static-cache` (compared against an older Direct that had already dropped ~12%).

| Mode | Direct c=50 | GW static c=50 | vs same-run Direct |
| --- | ---: | ---: | ---: |
| No snapshot | 16949 | 2025 | **11.97%** |
| Snapshot | 18271 | 2215 | **12.13%** |

Absolute QPS followed the host. After normalizing to the same-run Direct, Gateway retention is unchanged. **Do not publish a QPS win.** That era's ceiling was still JDK outbound (~12%). Current numbers are §8.2.

## 8.1 Observability off and EventLoop (2026-08-25)

That Phase A run turned metrics/trace off, used constant hop-by-hop compares, and put the handler on the EventLoop (an experiment).  
Run: `2026-08-25-141900-phase-a-obs-off`. Direct was 6.6% below the last qualified run (within the 10% gate). The current default is `dispatchOnEventLoop=false` again.

| | Direct c=50 | GW static c=50 | vs same-run Direct | c=100 503 |
| --- | ---: | ---: | ---: | ---: |
| Snapshot qualified (obs on + biz pool) | 18271 | 2215 | 12.13% | ~2% |
| This run | 17057 | 2138 | **12.53%** | **~12%** |

Turning observability off and skipping `toLowerCase` does **not** move throughput. EventLoop dispatch did not raise steady QPS and increased overload rejections. The three changes were measured together.

## 8.2 Netty outbound (2026-08-25)

Default upstream client is now Netty (`rover.gateway.proxy.outbound: netty`). The first-generation JDK `HttpClient` HTTP/1.1 path is still in the tree (`outbound: jdk`) so the compatibility work is not deleted.

Run: `2026-08-25-152000-phase-a-netty-outbound`. Same `perf-fair` / Phase A script / biz pool / metrics and trace off. Direct c=50 median **16939** is **−0.7%** vs `141900` and **−7.3%** vs `132633` (both within the 10% gate).

| Era | Direct c=50 | GW static c=50 | vs same-run Direct | GW P50 | c=100 successful QPS | c=100 503 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Snapshot (JDK) | 18271 | 2215 | 12.13% | 13.0 ms | ~2460 | ~2% |
| Obs-off (JDK + EventLoop experiment) | 17057 | 2138 | 12.53% | 15.5 ms | ~2280 | ~12% |
| **Netty outbound (current)** | **16939** | **6795** | **40.1%** | **5.6 ms** | **~8830** | **~5.6%** |

Nameserver path c=50 median **7266** (42.9% of same-run Direct), all 200. That is the same band as static, not a discovery tax.

**How to read this**

- Steady QPS is about **3.1×–3.2×** the JDK-outbound qualified runs. This is the first Phase A change that actually moved the ~12% retention ceiling.
- c=50 is still the only band we treat as stable (all 200).
- c=100 still rejects; quote **successful** QPS (~8830) and the ~5.6% 503 rate. Do not publish the mixed total (~9360).
- Inbound no longer uses `HttpObjectAggregator` (headers start the proxy; body is piped). Phase A remasure: GET `/api/hello` did **not** rise (about 6610 vs 6800 here). **Capacity numbers stay at the Netty-outbound row above.**

Current 2 CPU / 2 GB local capacity: **c=50 / ~6800 QPS / all 200 / ~40% of Direct**.

Same-session A/B (`2026-08-25-154500-phase-a-outbound-ab`): one image, YAML-only switch, startup logs checked (`JDK HttpClient HTTP/1.1` vs `Netty`). Direct c=50 drift **3.3%**. JDK half: GW **1865** / 12.0% of Direct. Netty half: GW **6615** / **43.8%** of Direct. About **3.5×** on the same jar. c=50 all 200 on both sides.

## 8.3 Large-body remasure (2026-08-25, `184143`)

Current stack (streaming responses + Netty outbound + inbound pipe), `perf-fair`, c=20, 20s, 3-round median. Script: `bench-phase-a-payload-remasure.sh`. Baseline: wave-1 streaming run `123108`.

**GET `/api/payload` (Netty outbound, all 200)**

| Size | Wave-1 stream QPS | This run QPS | Change | Wave-1 stream memory | This run memory |
| --- | ---: | ---: | ---: | ---: | ---: |
| 256 KB | 971 | **918** | **−5%** | 352 MiB | **170 MiB** |
| 1 MB | 266 | **228** | **−14%** | 355 MiB | **189 MiB** |
| 4 MB | 69 | **70** | **flat** | 439 MiB | **278 MiB** |

This-run Direct: 1161 / 294 / 81 vs wave-1 stream Direct 1189 / 288 / 76 — same band, comparison stands.  
**Large-response QPS still does not rise.** Memory dropped again; 4 MB is about **440→280 MiB**.

**POST `/api/ingest` (first numbers, Netty outbound, all 200)**

| Size | Gateway QPS | Direct | vs direct | Gateway memory |
| --- | ---: | ---: | ---: | ---: |
| 256 KB | **808** | 1232 | **66%** | 267 MiB |
| 1 MB | **239** | 285 | **84%** | 267 MiB |
| 4 MB | **66** | 84 | **78%** | 302 MiB |

There is no old-Aggregator A/B. This is a baseline, not a “how much faster than before”. Same-session 4 MB POST with JDK outbound is about 62 QPS (Direct 84 on both halves) — **QPS is the same**; JDK outbound memory is about **790 MiB** vs Netty **300 MiB**.

The JDK-outbound GET half had Direct collapse (256 KB 1161→663). That absolute QPS is **void**.

## 9. Making results more credible

1. Keep raw `hey` output for every round.
2. Run 5–10 rounds; record min/max/mean/stddev.
3. Record CPU, memory, GC and thread counts for Gateway, Nameserver and upstream.
4. State whether Admin live polling was enabled.
5. Fix route count, body size, connection reuse and log level.
6. Re-run on target Linux hosts; do not treat Docker Desktop numbers as production SLA.
7. Do not mix error status codes into successful throughput.

## 10. How to use this document

Use these numbers as a local development reference. They are not performance guarantees and are not meant for formal cross-product Gateway benchmarks.

Copy the commands to your environment, keep raw output, and build your own report with your routes, upstream latency and resource limits.
