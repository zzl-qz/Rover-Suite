# Troubleshooting and Performance Boundaries

| Symptom | First checks |
| --- | --- |
| Gateway cannot find an instance | Nameserver TCP address, protocol token, service name and heartbeat interval |
| Admin reports a component offline | Management URL, token, ACL; curl `/_manage/health` first |
| Configuration update fails | Field type, enum value, hot-reload flag and downstream logs |
| Route returns 404 | `businessPrefix`, `stripPrefix`, discovery mode and instance health |
| Instances are evicted repeatedly | Heartbeat timeout, network jitter and registered host/port reachability |
| HTTP registration returns 401 | `Authorization: Bearer` token matching Nameserver protocol token |
| Management returns 401 | `X-Rover-Admin-Token` matching the target component |
| Log flood / disk growth | Access logs are DEBUG by default; set `filters.accessLog: false` if needed |

## Debug logging

Default **INFO**: startup, hot reload, auth failures, upstream/protocol errors.  
Access completion, route match, Nameserver TCP connect/disconnect are **DEBUG**.

## Performance notes

Rover-Suite does **not** claim a universal QPS ceiling. Throughput depends on route
count, upstream latency, connection reuse, JDK/CPU, metric windows, trace
sampling and network placement. Admin is an observation surface, not a load
generator—**do not start Admin** during a load test.

The section below documents a **reference benchmark** run on **2026-08-24** on
the author’s machine: Compose CPU/memory caps mimicking a small-team single node,
executed under **Docker Desktop for Mac**. These numbers show “modest resources
still work”; they are **not** a Linux bare-metal SLA and **not** full-host
scores.

---

### Test environment (read this first)

| Item | Value |
| --- | --- |
| **Host** | Apple Silicon Mac (**M5 Pro**), macOS |
| **Runtime** | **Docker Desktop for Mac** (not Linux bare metal, not Kubernetes) |
| **Docker Desktop RAM** | **16 GB** (Settings → Resources) |
| **Load tool** | **`hey`** on the **host** (`brew install hey`), hitting `127.0.0.1` |
| **Container base image** | `eclipse-temurin:17-jre` |
| **JVM heap** | `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75.0` per service |
| **Compose** | `docker-compose.yml` + `docker-compose.perf-small.yml` |
| **Script** | `./deploy/scripts/bench-small-team.sh` |
| **Date** | 2026-08-24 |

**Docker Desktop caveats:**

- macOS virtualization and networking differ from Linux production hosts; the
  same caps often yield **higher or steadier** numbers on Linux.
- Published rows are **“capped containers + Docker Desktop + local reference”**,
  not guaranteed throughput.
- The host is an M5 Pro, but Compose **intentionally caps** CPU/RAM—the story is
  **small≈4C8G**, not “flex the Mac”.

---

### Topology and resource caps (small profile)

**Three services only—Admin not started:**

| Service | Port | CPU cap | Memory cap | Load target |
| --- | --- | --- | --- | --- |
| Nameserver | 8888 / 8889 | 1.0 | 1 GB | not HTTP-load-tested |
| Gateway | 8080 | 2.0 | 2 GB | `GET /api/hello` |
| demo upstream | 8082 (host-mapped) | 0.5 | 768 MB | `GET /api/hello` (direct baseline) |

**Totals ~3.5 CPU / ~3.75 GB**, leaving headroom for Docker Desktop and macOS.

**Via Gateway:** discovery + routing + proxy + default metrics.  
**Direct:** host `127.0.0.1:8082`, bypassing Gateway and Nameserver.

A tighter **tiny≈2C4G** overlay exists (`docker-compose.perf-tiny.yml`); not
run in this session. Use `BENCH_PROFILE=tiny ./deploy/scripts/bench-small-team.sh`.

---

### Methodology

1. Build and start with `BENCH_KEEP=1 ./deploy/scripts/bench-small-team.sh`.
2. Wait for health + business path 200 before loading.
3. **Primary compare** (use these settings in public write-ups):
   - `hey -z 30s -c 50`
   - Gateway: `http://127.0.0.1:8080/api/hello`
   - Direct: `http://127.0.0.1:8082/api/hello`
4. **Repeatability:** three rounds each for the primary compare.
5. **Concurrency control:** direct `hey -z 30s -c 100` to separate Gateway 503s
   from upstream failure.
6. Do **not** treat Gateway `c=100` QPS as a published number.

---

### Measured results (small caps, no Admin)

#### Primary compare: hey 30s, c=50, three rounds

| Run | Path | QPS | P50 | P95 | P99 | Responses | Errors |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| #1 | Via Gateway | 2560 | 12.1 ms | 65.0 ms | 79.8 ms | 76812 | 0 |
| #2 | Via Gateway | 2353 | 13.2 ms | 67.2 ms | 85.8 ms | 70606 | 0 |
| #3 | Via Gateway | 2575 | 12.1 ms | 63.8 ms | 78.8 ms | 77279 | 0 |
| #1 | Direct demo | 3907 | 2.2 ms | 85.4 ms | 90.0 ms | 117432 | 0 |
| #2 | Direct demo | 4036 | 2.1 ms | 84.8 ms | 89.7 ms | 121133 | 0 |
| #3 | Direct demo | 4216 | 2.0 ms | 84.0 ms | 90.4 ms | 126565 | 0 |

#### Summary (3-run average)

| Path | QPS avg | QPS range | P50 avg | P95 avg | P99 avg |
| --- | ---: | --- | ---: | ---: | ---: |
| **Via Gateway** | **2496** | 2353–2575 | **12.5 ms** | **65 ms** | **82 ms** |
| **Direct demo** | **4053** | 3907–4216 | **2.1 ms** | **85 ms** | **90 ms** |

Gateway QPS varied ~**±9%** across rounds—normal for Docker Desktop smoke
tests. **All six runs returned HTTP 200 only.**

#### Concurrency control: hey 30s

| Path | c | QPS | P50 | P95 | P99 | Notes |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| Direct demo | **100** | **4245** | 3.6 ms | 93 ms | 101 ms | **127380× HTTP 200** |
| Via Gateway | 100 | — | — | — | — | **many 503** (Gateway CPU cap; not for publish) |

Direct stays healthy at c=100; Gateway 503s are a **Gateway cap** effect, not
upstream collapse.

---

### Interpretation

| Metric | Value | Meaning |
| --- | --- | --- |
| Throughput retention | **2496 ÷ 4053 ≈ 62%** | ~**2.5k QPS** through Gateway |
| Median delta | **+≈10 ms** P50 | discovery + proxy chain |
| Tail latency | GW 65/82 ms vs direct 85/90 ms P95/P99 | similar tails under caps |

**Suggested public wording:**

> On **Docker Desktop for Mac**, with Compose caps approximating a **~4C8G
> small-team box** (**Admin off**), `GET /api/hello` for 30s at concurrency 50
> (three repeats): via Gateway ~**2.5k QPS**, P50 **~12 ms**; direct demo
> ~**4k QPS**, P50 **~2 ms**. Gateway retains ~**62%** throughput with ~**10 ms**
> extra median latency. At c=100, direct stays all-200 while Gateway returns
> many 503s under its CPU cap. **Reference only—not a Linux production SLA.**

**Do not claim:** full M5 Pro scores, fixed SLA, or Gateway c=100 QPS alongside c=50.

---

### Reproduce

```bash
brew install hey
cd /path/to/rover-suite
BENCH_KEEP=1 ./deploy/scripts/bench-small-team.sh

for i in 1 2 3; do
  hey -z 30s -c 50 http://127.0.0.1:8080/api/hello
  hey -z 30s -c 50 http://127.0.0.1:8082/api/hello
done
hey -z 30s -c 100 http://127.0.0.1:8082/api/hello

cd deploy/docker
docker compose -f docker-compose.yml -f docker-compose.perf-small.yml down
```

Smoke (functional, not load): `./deploy/scripts/smoke-compose.sh`.  
Cap files and script notes: [`deploy/docker/README.md`](../../deploy/docker/README.md).
