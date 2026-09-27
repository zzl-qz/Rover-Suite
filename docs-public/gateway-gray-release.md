# Gateway versioned gray release

[简体中文](./gateway-gray-release.zh-CN.md) · [Documentation index](./README.md)

This page covers versioned routing (`targets`), the safe change interface, and
the version-level metrics that support a canary. Route syntax and validation are
also summarized in
[Configuration Reference: Route fields](./configuration-reference.md#route-fields);
the management endpoints are listed in
[User Guide: Management and Admin](./user-guide.md#7-management-and-admin).

## 1. Route model

A route chooses one upstream kind:

- **Static** — `targetUrl` or `targetUrls`.
- **Versioned** — `targets: [{serviceName, group, weight}]`.

The two are mutually exclusive on one route, and the old flat `serviceName` /
`group` fields were removed. `group` is the version label (`v1`, `v2`, …); there
is no separate version field, because the registry already filters by group and
query responses already carry `revision` / `epoch`.

```yaml
routes:
  - id: order-api
    businessPrefix: /api/orders
    targets:
      - serviceName: order-service
        group: v1
        weight: 95
      - serviceName: order-service
        group: v2
        weight: 5
    stripPrefix: /api
```

Validation (see `RouteValidator`):

- every target needs a non-empty `serviceName`;
- all targets on one route must share the same `serviceName` — a versioned route
  is "one service, several groups", not a general multi-service aggregation, so
  per-version metrics and rollback keep a single meaning;
- `weight` is `0..10000`; `weight: 0` means "registered but receives no
  traffic", which is how you pause a version without deleting it;
- `(serviceName, group)` must not repeat;
- the total weight must be greater than 0.

The optional `stickyHeader` must be a legal HTTP header name (`[A-Za-z0-9-]+`).

## 2. How a version is chosen

For a versioned route the Gateway first picks a version, then picks an instance
within that version (two-level selection). Version selection is a **continuous
weighted interval**:

```text
slot = floorMod(fnv1a64(stickyKey + "|" + routeKey), totalWeight)
```

Walk `targets` in declaration order, accumulate weights, and return the target
whose interval contains `slot`. A `weight: 0` target adds no interval, so it
never receives traffic.

### Why a continuous interval, not a consistent-hash ring

Putting `weight` virtual nodes on a ring makes the node count equal to the total
weight (typically ~100), so the proportion error is bounded by the node count and
a small share such as 5% cannot be expressed precisely. The continuous interval
is exact, is a pure `O(N)` single pass, and needs no precomputed ring or a
carrier object passed across layers. The cost is losing "membership change
remaps only 1/N" — this stage does not need it, because scale-out adjusts
weights gradually and the total weight normally stays constant.

### Scale-out is monotonic

Moving weight from `v1` to the immediately following `v2` (e.g. `95/5 → 80/20`,
same total `100`) only shifts `v2`'s left boundary leftward; its right boundary
does not move. Keys already on `v2` therefore stay on `v2` — a user on the
canary is never pushed back to `v1`. This is what makes a canary experiment
continuous.

### Hash and sticky key

Version selection uses **FNV-1a 64-bit**, not `String.hashCode()`. The latter
clusters badly: `"k#0"` and `"k#1"` differ by a single value, so using it to split
traffic would distort the intended proportion. The hash also mixes in a
`routeKey` salt so the same user is not pinned to the gray version across every
service at once.

The sticky key is the configured `stickyHeader` request header; when it is
missing the client IP is used, and when both are empty (rare) the request is
routed by weight at random.

### Failures do not cross versions

- Retry and circuit-breaker failover stay **within the same version**; a retry
  never jumps to another version, which would pollute the gray proportion.
- If a version's `group` has **no healthy instance**, the request gets `503`
  (`REJECT_NO_UPSTREAM`); it is never diverted to another version. Discovery was
  tightened to return only `isHealthy()` instances, and the old "all unhealthy →
  fall back to the full cache" branch was removed.
- `weight: 0` means "not selected by weight", not "no instance"; it does **not**
  produce a 503, so "received no share" and "has nothing to serve" stay
  distinguishable in metrics.

## 3. Safe change interface

All writes go through one optimistic-lock protocol. Every write path carries
`revision`; there is no bypass.

| Endpoint | Method | Purpose |
| :--- | :--- | :--- |
| `/_manage/routes` | `GET` | Route table plus `revision` and `appliedOperationId` |
| `/_manage/routes` | `PUT` | Replace the whole table: body `{revision, operationId, routes}` |
| `/_manage/routes` | `POST` | Add or replace one route: body `{revision, operationId, route}` |
| `/_manage/routes?id=\|businessPrefix=` | `DELETE` | Delete one route: query `revision`, optional `operationId` |
| `/_manage/routes/preview` | `POST` | Per-route diff (`ADDED` / `REMOVED` / `MODIFIED`) and validation only; nothing is persisted or applied |
| `/_manage/routes/targets/weight` | `POST` | Raise or pause one version: `{revision, operationId, routeId, serviceName, group, weight}` |
| `/_manage/routes/rollback` | `POST` | Roll back: `{revision, operationId, toRevision}` |
| `/_manage/routes/operations/{operationId}` | `GET` | Confirm after a timeout whether a write was applied |

### Apply protocol (write-first, pre-built)

```
1. operationId already APPLIED  → return the recorded result (idempotent replay)
2. expectedRevision != revision → record CONFLICT, throw 409 (details carry currentRevision)
3. validate the candidate table → on failure record REJECTED, throw 400
4. pre-build the runnable state (new RouteMatcher, new filters)
5. persist first (including nextRevision / operationId)
6. then atomically swap the in-memory references
7. bump revision, record APPLIED
```

Every step that can fail happens **before** the write to disk; after the write
there is only non-failing atomic reference replacement. The window "disk written
but memory not swapped" is therefore structurally removed, not covered by a
try/catch. If memory were changed first and the disk write failed, memory would
be new while disk stays old, and a restart would silently roll behavior back.

### Persistence

Routes are persisted to `config/routes.overlay.json` as
`{revision, appliedOperationId, appliedAtMillis, routes:[...]}`, written with a
same-directory `.tmp` file plus an atomic move. At startup the `revision` /
`appliedOperationId` are read back into memory, so the revision reported after a
restart is the one that was last confirmed.

### Conflicts, idempotency, rollback, and confirmation

- A stale `revision` returns HTTP **409** with `currentRevision` in the body; the
  caller refreshes and retries. The Admin route page does this automatically.
- Re-submitting an `operationId` that is already `APPLIED` returns the recorded
  result without applying again.
- Rollback takes `toRevision` and runs the **same** protocol, producing a new
  revision. A rollback therefore cannot overwrite a concurrent change; the
  optimistic lock guarantees it. Only the most recent `5` applied snapshots are
  kept, and a target outside that window is rejected with `400`.
- `GET /_manage/routes/operations/{operationId}` answers "did my timed-out write
  apply": `APPLIED` / `CONFLICT` / `REJECTED` / `UNKNOWN`, plus `currentRevision`.
  **`UNKNOWN` means "no record"** — it may never have been submitted, or it may
  have been evicted from the bounded operation table (most recent `200`). It does
  **not** mean "not applied".

## 4. Version visibility

Two read-only endpoints let a caller reconcile versions without guessing:

- Nameserver `GET /_manage/instances/snapshot` — grouped by `service+group`, with
  `revision` / `epoch` and, per instance, `instanceId`, `host`, `port`, `group`,
  `weight`, `healthy`, `ephemeral`, `lastHeartbeatMillis`.
- Gateway `GET /_manage/discovery/snapshot` — what the Gateway **itself observed**
  per `(serviceName, group, revision, epoch, instanceCount, healthyCount)`. Under
  static discovery it returns `supported=false` with an empty list rather than
  failing, so "no such capability" and "read failed" stay distinguishable.

Comparing the two answers "did I change the config but the Gateway has not
received the push yet".

## 5. Version-level metrics

`GET /_manage/metrics/routes?routeId=..&range=60|300` adds:

- `targets` — the declared versions from config (`serviceName`, `group`,
  `weight`, `label`, `clusterKey`).
- `byVersion` — per version: `windowRequests`, `status5xx`, `connectFail`,
  `timeout`, `avgMillis`, `p95Millis`, `noUpstreamRejects`,
  `circuitOpenRejects`, `sampleSize`, `sufficient`, `errorRate`,
  `capacityProblem`.
- `versionCheck` — `sampleThreshold`, `declaredGroups`, `observedGroups`,
  `missingGroups`, `unexpectedGroups`.

Version metrics **add no storage**: they reuse the existing "route × upstream
instance" dimension, giving each forwarded instance its `group` and rolling those
rows up per version. The 5-minute sliding window and the
`route_upstream_sum_equals_instance_sum` invariant still hold.

Read the time semantics carefully — they are deliberately mixed:

- `windowRequests`, `status5xx`, `connectFail`, `timeout`, `avgMillis`,
  `p95Millis`, `sampleSize`, `sufficient`, `errorRate` are **window** values
  (`range=60/300`).
- `noUpstreamRejects` / `circuitOpenRejects` come from the route's **cumulative**
  reject counters (the same counters as `resources.rejects.noUpstream` /
  `circuitOpen`); they are not windowed, so no second per-version time-ring store
  is added.
- Therefore `capacityProblem` means "this version has hit a no-instance or
  all-circuit-open 503 at some point since startup" — a conservative hint, not
  "the current window is broken". Judge the current window from `windowRequests`
  / `status5xx` / `sampleSize`.
- A version with fewer than `sampleThreshold` (`MIN_VERSION_SAMPLE = 5`) window
  samples is not enough to judge (`sufficient=false`).
- `p95Millis` is the **maximum** of the version's instance p95 values (a
  conservative upper bound); no cross-instance sample merge is done, so it is not
  the version's true p95.

## 6. Boundaries (not done in this stage)

- **Automatic stop on anomalies** (auto-lowering weights on a 5xx rate) is not
  done. It needs a "driver"; the decision logic belongs in a testable Java
  service called by the next-stage Agent. This stage ships the primitives — set a
  version's weight to `0` for one-step stop, plus trustworthy per-version metrics
  and a sample threshold.
- Agent-side write operations remain off (`executable` is always `false`; no
  approval, audit, or task persistence). The write loop is the next stage.
- No multi-node per-node apply acknowledgement; Admin currently targets a single
  `gatewayUrl`.
- No Gateway-side automatic guardrail beyond the primitive above.
- No separate Nameserver "pause / resume serving" state; version routing is
  achieved through `group`.
- No audit persistence beyond `revision`.
- The front-end only changed the route editor (preview plus conflict prompt); no
  new release console.
