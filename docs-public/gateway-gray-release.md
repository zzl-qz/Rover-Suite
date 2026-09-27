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
`group` fields were removed.

`group` is the registry's **general-purpose business group** (it can isolate
environments, tenants, data centres, …); it is not a version field invented for
canaries. Gray release simply **reuses that dimension**: the same service's
groups are treated as versions (`v1`, `v2`, …), so there is no separate version
field — the registry already filters by group and query responses already carry
`revision` / `epoch`. Consequence: gray groups and business groups share one
namespace.

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
weighted interval on a fixed scale**:

```text
slot  = floorMod(fnv1a64(stickyKey + "|" + routeKey), SCALE)  // SCALE is fixed, independent of the total weight
upper = SCALE × cumulativeWeight / totalWeight                // right boundary of the k-th target
```

The hash is first normalised onto a fixed-length slot in `[0, SCALE)`
(`SCALE = 10_000_000`); the interval is then carved up among the `targets` in
declaration order, in proportion to their weights. Whichever interval contains
`slot` wins. A `weight: 0` target adds no interval, so it never receives traffic.

The scale **must** be independent of the total weight: taking the modulo of the
total weight instead means changing one target's weight changes `total`, which
shifts every key's slot.

### Why a continuous interval, not a consistent-hash ring

Putting `weight` virtual nodes on a ring makes the node count equal to the total
weight (typically ~100), so the proportion error is bounded by the node count and
a small share such as 5% cannot be expressed precisely. A continuous interval on
a fixed scale is exact, is a pure `O(N)` single pass, and needs no precomputed
ring or a carrier object passed across layers. The cost is losing "membership
change remaps only 1/N" — this stage does not need it, because scale-out adjusts
weights gradually and the fixed scale guarantees each adjustment only moves
boundaries instead of reshuffling slots.

### Scale-out is monotonic

Moving weight from `v1` to the immediately following `v2` (e.g. `95/5 → 80/20`,
same total `100`) only shifts `v2`'s left boundary leftward; its right boundary
does not move. Keys already on `v2` therefore stay on `v2` — a user on the
canary is never pushed back to `v1`. This is what makes a canary experiment
continuous.

**Raising one version's weight alone** (e.g. `v1:95 / v2:20`, total growing from
`100` to `115`) is monotonic too: when a target's weight goes from `w` to `w+d`,
its left boundary `SCALE × previousCumulative / total` is non-increasing and its
right boundary `SCALE × ownCumulative / total` is non-decreasing, so its interval
only grows and it keeps every key it already held. Note that the actual share is
the **normalised** proportion: under `v1:95 / v2:20`, `v2` receives
`20/115 ≈ 17.4%`, not `20%`.

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
- A `503` caused by "no available upstream" or "every upstream circuit-open"
  still carries `routeId` and version `group` on the request context, so those
  rejections are attributed to the real route and version instead of the
  "unmatched route" bucket. Attaching them later makes a capacity problem look
  like a misconfigured route table.

## 3. Safe change interface

All writes go through one optimistic-lock protocol. Every write path carries
`revision`; there is no bypass.

| Endpoint | Method | Purpose |
| :--- | :--- | :--- |
| `/_manage/routes` | `GET` | Route table plus `revision` and `appliedOperationId` |
| `/_manage/routes` | `PUT` | Replace the whole table: body `{revision, operationId, routes}`, `routes` is mandatory |
| `/_manage/routes` | `POST` | Add or replace one route: body `{revision, operationId, route}` |
| `/_manage/routes?id=\|businessPrefix=` | `DELETE` | Delete one route: query `revision`, optional `operationId` |
| `/_manage/routes/preview` | `POST` | Per-route diff (`ADDED` / `REMOVED` / `MODIFIED`) and validation only; nothing is persisted or applied |
| `/_manage/routes/targets/weight` | `POST` | Raise or pause one version: `{revision, operationId, routeId, serviceName, group, weight}` |
| `/_manage/routes/rollback` | `POST` | Roll back: `{revision, operationId, toRevision}` |
| `/_manage/routes/operations/{operationId}` | `GET` | Confirm after a timeout whether a write was applied |

The `routes` field of a whole-table replace is parsed **strictly**: a missing
field, a misspelled key, a non-array value, or a non-object element inside the
array is rejected with `400`. Only an explicit `[]` counts as "clear the route
table". A lenient parse turns "the request body was wrong" into "wipe the table
and persist it" — the whole site 404s and a restart does not bring it back.

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
  apply": `APPLIED` / `CONFLICT` / `REJECTED` / `FAILED` / `UNKNOWN`, plus
  `currentRevision`.
- `FAILED` means **submitted but the disk write failed** (the change did not take
  effect); it differs from `REJECTED` as "could not be written" differs from
  "failed validation". Neither should be read as "never submitted". `UNKNOWN`
  means **no record** — it may never have been submitted, or it may have been
  evicted from the bounded operation table (most recent `200`). It does **not**
  mean "not applied".
- `operationId` is generated by the **caller** (the Admin route page does it), so
  a timed-out request can be reconciled with the same id: Admin exposes
  `GET /api/routes/operations/{operationId}` and the front-end queries it
  automatically after a timeout or network failure, reporting "applied / not
  applied (revision conflict) / not applied (validation failed) / not applied
  (disk write failed) / the Gateway has no such record".
- A downstream `409` is **passed through** unchanged to Admin: both the status and
  the "expected N, current M" message survive, instead of being flattened into
  `400` "bad parameters" — otherwise an operator just re-submits the same stale
  revision over and over.

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

### Discovery consistency guarantees

The read-only endpoints answer "what is true now"; these three guarantees answer
"will a change actually arrive":

- **Migrating a group notifies the old group too**: when the same `instanceId` is
  re-registered into another `group`, the old group's subscribers are pushed as
  well. The snapshot only carries the new group, so without that push the old
  group keeps a departed instance and keeps sending traffic to it. The cost is at
  most one extra "this group's list did not change" push per group.
- **An empty list is a valid push**: the server bumps `revision` on every real
  change, so "newer `revision` + empty list" must be accepted by clients — that
  is a group's last instance going away, or a whole service being emptied. Only
  an empty push that is **not newer** is dropped as an out-of-order packet
  (empty-push protection), so an old packet cannot wipe a newer list.
- **Keyed by `service+group`, with no fallback**: when a specific group has no
  cache of its own, the lookup returns empty and waits for that group's
  subscription snapshot; it never falls back to the whole-service cache. Such a
  fallback would route `v2` requests to `v1` instances, skewing the gray
  proportion, and it shows up only as "`v2`'s upstream is a `v1` address" — nearly
  impossible to spot at a glance.

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
