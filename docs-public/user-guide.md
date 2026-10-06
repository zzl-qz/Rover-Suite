# User Guide

[简体中文](./user-guide.zh-CN.md) · [Documentation index](./README.md)

This guide covers day-to-day configuration and operation. Use the [Quick Start](./quick-start.md) first if you
have not run the demo. Provider lifecycle details are kept in [Service Registration](./service-registration.md).

## 1. Processes and ports

| Process or listener | Default | Used by |
| :--- | :--- | :--- |
| Nameserver TCP | `8888` | Java registration, Gateway query/subscribe, instance push |
| Nameserver HTTP | `8889` | `/_manage/**` and optional `/v1/client/**` registration |
| Gateway HTTP | `80` in the bundled YAML | Application traffic and Gateway management; use `8080` for local development |
| Demo backend | `8081` | Example `demo-service` |
| Admin | `9090` | Optional web console |

The Nameserver HTTP listener is not a separate component. `managePort: 0` disables both the management API and
the HTTP Registration API (named Client API in internal configuration).

## 2. Configuration loading

Standalone processes use this startup priority:

```text
working-directory config/<file>.yml
  > classpath <file>.yml
  > code defaults
```

The primary bundled files are:

- [`rover-nameserver.yml`](../rover-nameserver-bootstrap/src/main/resources/rover-nameserver.yml)
- [`rover-gateway.yml`](../rover-gateway-bootstrap/src/main/resources/rover-gateway.yml)

Copy the complete bundled file to `./config/` before changing it. The external file wins as a startup source;
it is not textually merged with the classpath file. The standalone YAML loader does not expand `${ENV_VAR}`.
Mount a protected configuration file or generate one in your deployment workflow, and never commit real tokens.

After YAML is loaded, supported runtime keys may be overlaid by:

- `config/nameserver-runtime.overlay.json`
- `config/gateway-runtime.overlay.json`
- `config/routes.overlay.json`

If `routes.overlay.json` exists, it replaces the YAML route list. Delete or update a stale overlay when a route
change in YAML appears to have no effect. Listener ports, bind hosts, tokens, discovery type, HTTP Registration API
enablement, plugin paths, and CORS are startup settings and require a restart.

When Admin is not deployed, set `rover.gateway.adminEnabled: false`. Gateway then skips its route/runtime overlays
and makes `/_manage/**` unavailable, without changing Nameserver discovery or business proxying. The flag does not
delete existing overlays; they apply again if Admin is re-enabled.

## 3. Configure Nameserver

A production-oriented baseline is:

```yaml
rover:
  nameserver:
    port: 8888
    bindHost: 10.0.0.10
    managePort: 8889
    manageBindHost: 10.0.0.10
    token: "replace-with-a-protocol-token"
    adminToken: "replace-with-a-different-admin-token"
    clientApiEnabled: false
    heartbeatTimeoutMillis: 15000
    healthCheckIntervalMillis: 5000
    instanceExpireMillis: 30000
    pushEnabled: true
```

Enable `clientApiEnabled` only when non-Java providers use HTTP registration. Online instances are lease-based,
in-memory soft state; the Nameserver does not actively probe provider ports or restore historical registrations.
See [Architecture](./architecture.md) for the rationale.

## 4. Configure Gateway discovery

### Nameserver discovery

```yaml
rover:
  gateway:
    port: 8080
    discovery:
      type: nameserver
      nameserver:
        address: 10.0.0.10:8888
        token: "replace-with-the-same-protocol-token"
        reconcileIntervalMs: 30000
    loadbalance:
      strategy: round_robin
```

The Gateway token key is `rover.gateway.discovery.nameserver.token`; its value must match
`rover.nameserver.token` on the Nameserver. The Gateway keeps a local instance cache, receives snapshot pushes,
and periodically queries for reconciliation. Requests do not synchronously query Nameserver.

### Static upstreams

Use static discovery when you do not need registration:

```yaml
rover:
  gateway:
    discovery:
      type: static
    routes:
      - id: static-orders
        businessPrefix: /orders
        targetUrls:
          - http://10.0.1.10:8080
          - http://10.0.1.11:8080|200
        stripPrefix: /orders
```

The optional `|200` suffix is the upstream weight. Static and Nameserver discovery use the same load-balancing
strategies: `round_robin`, `random`, `weighted_round_robin`, `ip_hash`, and `least_connections`.
Static upstreams currently use only the URL scheme, host, and port. Do not put a base path in `targetUrl` or
`targetUrls`; express path transformation with the route's `stripPrefix`.

The process uses one registry: `static`, `nameserver`, or `nacos`. After
`nameserver` or `nacos`, a route may still use only `targetUrls`. Do not set
both `targets` and `targetUrl`/`targetUrls` on the same route.

Nacos needs a `-Pnacos` build; see
[`deploy/docker/config/rover-gateway-nacos.yml`](../deploy/docker/config/rover-gateway-nacos.yml).
Whole-process static config is
[`deploy/docker/config/rover-gateway-static.yml`](../deploy/docker/config/rover-gateway-static.yml).

The default classpath `rover-gateway.yml` is one nameserver scene, not three
commented stacks. Leave global `rewrite.stripPrefix` empty and set rewrite on
each route so a new route does not inherit `/api`.

### Upstream HTTP and HTTPS

Default outbound is Netty and forwards `http://` upstreams only. Terminate client HTTPS at a reverse proxy in
front of Gateway; keep Gateway-to-service traffic as plain HTTP on the private network.

If the upstream URL must be `https://`, set `rover.gateway.proxy.outbound` to `jdk`. That is the first-generation
JDK `HttpClient` (HTTP/1.1), kept for rollback and comparison; it includes TLS. Throughput then returns to the
JDK-outbound band — see the [Performance Report](./performance-report.md).

With the default outbound, startup and hot-reload reject `https://` so a config cannot pass and then fail on the
first request.

## 5. Define routes

Nameserver-backed route (one service, two versions):

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

| Field | Meaning |
| :--- | :--- |
| `id` | Unique route identifier |
| `businessPrefix` | Incoming path prefix used for matching |
| `targets` | Versioned upstreams: `{serviceName, group, weight}`; reuses the registry's `group` (a general business group) as the gray dimension, `weight: 0` pauses a version |
| `stickyHeader` | Optional header used as the sticky key; falls back to the client IP |
| `targetUrl` / `targetUrls` | Fixed upstream list for a static route |
| `stripPrefix` | Prefix removed before proxying; use `""` to preserve the full path |

A route uses either `targets` (registry) or `targetUrl`/`targetUrls` (static) —
never both on the same route. The old flat `serviceName` / `group` fields were
removed. Dynamic mode may mix those route kinds across routes, but not on one
route. All targets on a route must share one `serviceName`; see
[Configuration Reference: Route fields](./configuration-reference.md#route-fields)
and [Gateway versioned gray release](./gateway-gray-release.md).

## 6. Register providers

- Spring Boot service: use the Starter; no startup-class code is required. See
  [Java Starter registration](./service-registration.md#2-java-spring-boot-starter).
- Node.js, Python, Go, long-running PHP, or C++: use the small HTTP Registrar references. See
  [HTTP registration](./service-registration.md#3-httpjson-registration).

The current Maven coordinates use `1.0.0-SNAPSHOT` and are not documented as publicly published. Run
`mvn clean install -DskipTests` from this source tree before resolving the Starter from another local project,
or replace the version once an official release repository is announced.

## 7. Management and Admin

With the bundled local Nameserver configuration, `adminToken` is empty and these endpoints are accessible
without a header:

```bash
curl http://127.0.0.1:8889/_manage/status
curl http://127.0.0.1:8889/_manage/instances
```

When `adminToken` is non-empty, management calls require:

```text
X-Rover-Admin-Token: <adminToken>
```

The Bearer protocol token does not authorize management calls. Conversely, the admin header does not authorize
`/v1/client/**`. When authentication domains need isolation, use different values for the two tokens.

Built-in management endpoints:

| Component | Path and method | Purpose |
| :--- | :--- | :--- |
| Gateway | `GET /_manage/status` | Listener, discovery, route, and runtime status |
| Gateway | `GET/PUT/POST/DELETE /_manage/routes` | Read the route table (with `revision` and `appliedOperationId`), or replace, add/update, or delete routes. Every write must carry `revision`; a stale value returns `409` with `currentRevision` |
| Gateway | `POST /_manage/routes/preview` | Per-route diff (`ADDED` / `REMOVED` / `MODIFIED`) and validation result only; nothing is persisted or applied |
| Gateway | `POST /_manage/routes/targets/weight` | Raise or pause one version's weight (`routeId`, `serviceName`, `group`, `weight`); internally still a full-table optimistic-lock change |
| Gateway | `POST /_manage/routes/rollback` | Roll back to a recent applied `toRevision`; the rollback itself is a new revision and cannot overwrite a concurrent change |
| Gateway | `GET /_manage/routes/operations/{operationId}` | Confirm whether a write was applied after a timeout: `APPLIED` / `CONFLICT` / `REJECTED` / `FAILED` (submitted but the disk write failed) / `UNKNOWN` (no record), plus `currentRevision` |
| Gateway | `GET /_manage/discovery/snapshot` | What the Gateway itself observes per `service@group`: `revision`, `epoch`, instance and healthy counts; `supported=false` under static discovery |
| Gateway | `GET/POST /_manage/configs` | List or update registered runtime settings |
| Gateway | `GET /_manage/metrics`, `/metrics/live`, `/metrics/selfcheck`, `/prometheus` | JSON metrics, slim live snapshot (`range=60/300`; no p99/upstream Top; route Top short-cached), self-check, and Prometheus text |
| Gateway | `GET /_manage/metrics/routes` | One route's per-upstream window observation plus declared `targets`, aggregated `byVersion`, and `versionCheck` (`routeId`, `range=60/300`); only upstreams that forwarded traffic in the window are listed, and with metrics collection off it returns `enabled=false` with an empty `rows` |
| Gateway | `GET /_manage/traces` | Bounded request timeline with `traceId`, `path`, `slow`, and `error` filters (`error` set to `1` or `true` returns only traces whose status code is ≥ 500) |
| Nameserver | `GET /_manage/status`, `/instances` | Runtime status and current in-memory instances |
| Nameserver | `GET /_manage/instances/snapshot` | Verifiable instance view grouped by `service+group`, with `revision`/`epoch` and per-instance id, host, port, group, weight, health, ephemeral flag, and last heartbeat |
| Nameserver | `GET/POST /_manage/configs` | List or update registered runtime settings |
| Nameserver | `GET /_manage/metrics`, `/metrics/live`, `/events` | Registration metrics, live snapshot, and recent events |

Version-level metrics reuse the existing `route × instance` dimension rather than
adding a second store: each forwarded instance carries its `group`, and
`/_manage/metrics/routes` rolls those rows up per version. It therefore still
honors the 5-minute sliding window and the `route_upstream_sum_equals_instance_sum`
invariant. `targets` is the declared version list from config (`serviceName`,
`group`, `weight`, `label`, `clusterKey`); `byVersion` reports per-version
`windowRequests`, `status5xx`, `connectFail`, `timeout`, `avgMillis`, `p95Millis`,
`sampleSize`, `sufficient`, `errorRate`, and `capacityProblem`; `versionCheck`
compares declared vs observed versions through `sampleThreshold` (`5`),
`declaredGroups`, `observedGroups`, `missingGroups`, and `unexpectedGroups`.

Read the time semantics carefully — they are deliberately mixed.
`windowRequests`, `status5xx`, `connectFail`, `timeout`, `avgMillis`, `p95Millis`,
`sampleSize`, `sufficient`, and `errorRate` are **window** values
(`range=60/300`). In contrast, `noUpstreamRejects` and `circuitOpenRejects` come
from the route's **cumulative** reject counters (the same counters as
`resources.rejects.noUpstream` / `circuitOpen`); they are not windowed, so that
no second per-version time-ring store is added. As a result `capacityProblem`
means "this version has hit a no-instance or all-circuit-open 503 at some point
since startup" — a conservative hint, not "the current window is broken". Judge
the current health of a version from `windowRequests` / `status5xx` /
`sampleSize`; a version with fewer than `sampleThreshold` (`5`) window samples is
not enough to draw a conclusion (`sufficient=false`). Note also that `p95Millis`
is the **maximum** of that version's instance p95 values (a conservative upper
bound); no cross-instance sample merge is done, so it is not the version's true
p95.

Rover-Admin is optional:

```bash
mvn -pl rover-admin spring-boot:run
```

Open `http://127.0.0.1:9090`. `/api/live` polls about once per second **only while the dashboard tab is visible**; leaving the page or hiding the browser tab stops live polling so idle Admin tabs do not tax Gateway/Nameserver. Component status refreshes about every 15 seconds via `/api/overview`. Gateway live snapshots are slimmed further (no p99 / upstream Top; route Top cached ~5s). Request traces honor Gateway
`gateway.trace.sampleRate`: `0` records only slow requests (default); set it to `1` in Admin config to
capture ordinary traffic without restart.

Open **指标诊断** for global P95/P99, all routes/upstreams, and cumulative reject/retry counters. A route row's
diagnosis action opens version and instance detail over 1/5 minutes. The global window follows Gateway configuration;
the route window is chosen separately. Missing samples do not prove health, and version P95 is the maximum instance
P95. See the [Admin guide](./admin-guide.md) for screenshots and metric semantics.

If the Gateway runs on a non-default port (bundled default is often `80`), point Rover-Admin's Gateway
base URL at that listener.

## 8. Optional deployment hardening

Rover intentionally defaults to all-interface listeners and empty tokens for zero-config startup on a local or
trusted network; it does not enforce one security policy. When a deployment crosses a trust boundary, select the
hardening measures it needs:

- Bind `8888` and `8889` to a private address and restrict both ports with network policy or a firewall.
- Configure non-empty, different protocol and admin tokens.
- Keep `/v1/client/**` disabled unless it is used.
- Gateway `/_manage/**` shares the public Gateway listener with business traffic. Configure a non-empty Gateway
  `adminToken` and block the management path at an outer proxy/ACL when it should not be remotely reachable.
- TCP `8888`, Nameserver HTTP, and Gateway HTTP do not provide built-in TLS. A token authenticates but does not
  encrypt traffic: keep TCP on a private network/VPN/TLS tunnel and terminate HTTPS at a trusted proxy for HTTP.
- Register an address reachable from the Gateway; do not use `127.0.0.1` across hosts or Pods.
- Give every concurrently reachable replica a unique `instanceId`.
- Start registration only after the business listener is ready and close it during graceful shutdown.
- Monitor registration failures, expiry removals, available-instance count, and Gateway upstream failures.

Rover-Suite currently targets single-node or trusted-network deployments for small teams rather than a tenant-aware
public control plane. The deployer chooses hardening when exposing control ports; see the
[Architecture non-goals](./architecture.md#7-explicit-non-goals).

## 9. Current runtime boundaries

- The current build is a single-node `1.0.0-SNAPSHOT`; it does not provide Nameserver HA or persistence-based
  restoration of online instances.
- Discovery is push-first with periodic query reconciliation, not strongly real-time. When the last instance is
  de-registered or expires, the server bumps `revision` and pushes an empty list; Gateway accepts that push and
  **clears the group immediately**, so requests turn into `503 NO_UPSTREAM` right away. Only an empty push that is
  *not newer* than the local cache is dropped as an out-of-order packet (empty-push protection); that case is the
  one that waits for the next reconciliation (up to about 30 seconds by default). This machine's earlier Compose run
  (`demo-fault.sh`) measured 502 immediately and `503 NO_UPSTREAM` after about 17 seconds — an artifact of the
  rejected empty push; that figure needs re-measuring against the new behavior.
- If Nameserver is unavailable when Gateway starts, the failed initial subscription may not be recovered until the
  next reconciliation, again up to about 30 seconds by default.
- `group` is the registry's general-purpose business group (environments, tenants, data centres); versioned routes
  (`targets`) merely **reuse it as the gray dimension**, so it is not a dedicated version field. Per-group routing
  works: Gateway subscribes and queries per `serviceName + group`, and multi-group pushes are keyed by
  `serviceName + group` with no fallback to the whole-service cache. It remains a business-group dimension rather
  than a hard tenant boundary. See
  [Service Registration](./service-registration.md#23-current-group-boundary).
- Gateway returns only healthy instances. When every instance of a service is unhealthy the candidate list is empty and the request gets `503 NO_UPSTREAM`; there is no fail-open fallback to the full cached list. A `group` (version) with no healthy instance is rejected the same way and is never silently diverted to another version, so "no instance to route to" and "upstream returned 5xx" stay distinguishable in metrics.
- Gateway targets ordinary HTTP/1.1: inbound headers start the proxy and the request body is piped; default Netty
  outbound writes the upstream response in chunks. WebSocket and SSE are not supported. The default request-body
  limit is 1 MiB and the hard response-body limit is 16 MiB.
- The Gateway process does not terminate client HTTPS, and the default outbound path does not speak TLS to upstreams.
- Static upstream URLs preserve only scheme, host, and port; URL base paths are not preserved.

These boundaries do not prevent the ordinary single-node HTTP API and empty-group use case, but should be evaluated
for strict removal consistency, group isolation, streaming protocols, or an internet-facing control plane.

## 10. Troubleshooting

| Symptom | Likely cause |
| :--- | :--- |
| Configuration edit is ignored | The process was launched from another working directory, a runtime overlay wins, or the setting requires restart. |
| Java provider cannot authenticate | `rover.nameserver.token` in the application does not match Nameserver. |
| Gateway cannot discover services | Check `rover.gateway.discovery.nameserver.address` and `.token`; these are Gateway keys, not Starter keys. |
| Nameserver is back but Gateway still has no instance | A failed initial watch may wait until the next reconciliation; wait for `reconcileIntervalMs` or restart Gateway. |
| Traffic briefly targets the last stopped instance | Check whether Gateway has received the group's new revision and empty snapshot. A newer empty push clears that group; missed pushes or disconnections are repaired by periodic reconciliation. |
| HTTP Registrar receives `404 NOT_FOUND` | `clientApiEnabled` is false, the path is wrong, or the HTTP listener is disabled. Enable the Registration API and restart. |
| HTTP Registrar receives `409 STALE_SESSION` | Another process registered the same `serviceName + instanceId`. Give replicas unique IDs and one owner per endpoint. |
| Provider is visible but unreachable | The registered host/port is not reachable from Gateway. |
| YAML route is ignored | `config/routes.overlay.json` is replacing the YAML route list. |
| Startup or hot-reload says only http upstreams are supported | Default Netty outbound does not forward `https://`. Use `http://`, or set `proxy.outbound: jdk`. |
| Confirm the current I/O implementation | Read `ioTransport=` in the startup log. In a Docker Linux container, `auto` is usually epoll. Host `java -jar` on macOS selects kqueue. |
| A custom LB is missing from Admin | Normal. `gateway.loadbalance.strategy` is a startup/plugin-mounting setting. Put the built-in strategy, SPI `name()`, or implementation FQCN in `rover-gateway.yml` and restart Gateway; Admin no longer edits it. |

The following is a historical comparison; its last-instance removal timing does not describe the current implementation.

To replay discovery faults on local Compose, run `./deploy/scripts/demo-fault.sh`. This machine's sample:
20 hellos split 10 / 10; `docker stop` and `docker kill` of one instance both avoided it immediately
(unregister or TCP-disconnect cleanup); `docker pause` avoided it after about 35 seconds (connection up,
heartbeats frozen); after Nameserver was stopped, forwarding continued within about 2 seconds; after the last
instance stopped, requests were 502 immediately and `503 NO_UPSTREAM` after about 17 seconds.
See the [Docker Compose README](../deploy/docker/README.md).

Further reading: [Service Registration](./service-registration.md), [Architecture](./architecture.md), and
[Development Guide](./development-guide.md).
