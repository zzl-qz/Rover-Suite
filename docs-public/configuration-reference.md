# Configuration Reference

External files under `config/` take precedence over classpath defaults. Startup
configuration normally requires a restart. Admin runtime changes are written to
the corresponding `*-runtime.overlay.json`; the component decides whether they
can be applied immediately. The authoritative list of current values, defaults,
and `hotReloadable` flags is returned by `GET /api/configs`.

## Process-level security (not YAML)

| Switch | Meaning |
| --- | --- |
| Env `ROVER_STRICT_SECURITY=true` | Blank `adminToken` / Nameserver protocol `token` **refuse to start** |
| JVM `-Drover.strictSecurity=true` | Same |

See [`deploy/production/`](../deploy/production/) for copy-ready templates.

Health probe: `GET /_manage/health` → `{"status":"UP","component":"..."}`
(same admin-token rules as other manage APIs).

## Admin startup configuration

| Key | Default | Description | Apply |
| --- | --- | --- | --- |
| `server.port` | `9090` | Admin HTTP port | Restart |
| `rover.admin.gateway-url` | `http://127.0.0.1:80` | Gateway management base URL | Restart |
| `rover.admin.nameserver-manage-url` | `http://127.0.0.1:8889` | Nameserver management base URL | Restart |
| `rover.admin.admin-token` | empty | `X-Rover-Admin-Token` sent to downstream components | Restart |

Admin does not store business configuration and does not add authentication to
`/api/*` by itself. Protect `server.port` with network ACLs, a reverse proxy or
a VPN in production.

## Gateway startup configuration

| Key | Default | Description | Apply |
| --- | --- | --- | --- |
| `rover.gateway.port` | `80` | Business HTTP port | Restart |
| `rover.gateway.adminEnabled` | `true` | Enables `/_manage/**`, Admin route overlays, and runtime config overlays | Restart |
| `rover.gateway.adminToken` | empty | `/_manage/**` token; blank = no auth (startup WARN). Prefer non-empty or `ROVER_STRICT_SECURITY=true` | Restart |
| `rover.gateway.server.bindHost` | `0.0.0.0` | Business listener address | Restart |
| `rover.gateway.server.maxContentLengthBytes` | `1048576` | Request body limit (inbound pipe; 413 when exceeded). No `HttpObjectAggregator` | Restart |
| `rover.gateway.server.dispatchOnEventLoop` | `false` | Run the business handler on the EventLoop. Default is the biz pool (I/O and business stay split). Set `true` only when the path is non-blocking | Restart; or `-Drover.gateway.dispatchOnEventLoop` |
| `rover.gateway.server.ioTransport` | `auto` | Same I/O family for inbound EventLoops and outbound Channels. `auto` selects Epoll on Linux (typical in a Docker Linux container), KQueue when the process runs on macOS, and NIO if natives are missing. Pin `nio` / `epoll` / `kqueue` (falls back to NIO when unavailable) | Restart; or `-Drover.gateway.ioTransport` |
| `rover.gateway.metrics.enabled` | `true` | Startup metrics switch; `false` skips inflight/record on the hot path. 503 reject counters still increment | Restart; YAML-only when Admin is off |
| `rover.gateway.metrics.windowSeconds` | `300` | Metrics sliding window (seconds) | Restart / runtime |
| `rover.gateway.trace.enabled` | `true` | Startup timeline switch; `false` skips `markPhase` and minted trace IDs | Restart; YAML-only when Admin is off |
| `rover.gateway.trace.slowThresholdMillis` | `100` | Slow-request threshold | Restart / runtime |
| `rover.gateway.trace.sampleRate` | `0.0` | Sample rate `0..1` | Restart / runtime |
| `rover.gateway.proxy.outbound` | `netty` | Upstream HTTP client. Default `netty` forwards `http://` only. `jdk` is the first-generation JDK `HttpClient` (HTTP/1.1), kept for rollback and comparison; it includes TLS, so an `https://` upstream can use this switch. Also `-Drover.gateway.proxy.outbound` | Restart |
| `rover.gateway.proxy.connectTimeoutMillis` | `3000` | Upstream connection timeout | Restart |
| `rover.gateway.proxy.requestTimeoutMillis` | `30000` | Startup default for request timeout | Restart |
| `rover.gateway.discovery.type` | `STATIC` in code / `nameserver` in example | `static`, `nameserver`, or `nacos` | Restart |
| `rover.gateway.discovery.nameserver.address` | `127.0.0.1:8888` | Nameserver TCP address | Restart |
| `rover.gateway.discovery.nameserver.reconcileIntervalMs` | `30000` | Local-cache reconciliation interval | Restart |
| `rover.gateway.discovery.nameserver.token` | empty | Gateway-to-Nameserver protocol token | Restart |
| `rover.gateway.discovery.nacos.serverAddr` | `127.0.0.1:8848` | Nacos address; required when `discovery.type=nacos`. Build with `-Pnacos` or add the adapter yourself | Restart |
| `rover.gateway.discovery.nacos.namespace` | empty | Namespace ID. Leave empty for public; do not set `public` | Restart |
| `rover.gateway.discovery.nacos.username` | empty | Nacos username; leave empty when auth is off | Restart |
| `rover.gateway.discovery.nacos.password` | empty | Nacos password; leave empty when auth is off | Restart |
| `rover.gateway.discovery.nacos.timeoutMs` | `3000` | Naming request timeout in milliseconds | Restart |
| `rover.gateway.filters.enabled` | `true` | Startup filter switch | Restart; runtime key below |
| `rover.gateway.filters.pluginDir` | `plugins` | Filter/LoadBalancer JAR directory | Restart |
| `rover.gateway.filters.accessLog` | `true` | Assemble access-log filter; logs at debug | Restart |
| `rover.gateway.filters.classes` | `[]` | Explicit filter implementation FQCNs | Restart |
| `rover.gateway.rateLimit.enabled` | `false` | Per-Gateway local rate-limit switch | Restart |
| `rover.gateway.rateLimit.algorithm` | `token_bucket` | `token_bucket` or `sliding_window` | Restart |
| `rover.gateway.rateLimit.key` | `path` | `global` for one instance or `path` per request path | Restart |
| `rover.gateway.rateLimit.permitsPerSecond` | `1000` | Token-bucket refill rate | Restart |
| `rover.gateway.rateLimit.burst` | `2000` | Token-bucket capacity/burst limit | Restart |
| `rover.gateway.rateLimit.limit` | `1000` | Maximum requests in a sliding window | Restart |
| `rover.gateway.rateLimit.windowSeconds` | `1` | Sliding-window length, 1..3600 seconds | Restart |
| `rover.gateway.circuitBreaker.enabled` | `false` | Process-local circuit breaker; consecutive failures per upstream `host:port` | Restart |
| `rover.gateway.circuitBreaker.failureThreshold` | `5` | Consecutive failures before opening | Restart |
| `rover.gateway.circuitBreaker.openSeconds` | `10` | How long to stay open, 1..3600 seconds | Restart |
| `rover.gateway.circuitBreaker.recovery` | `all` | `all` = everyone may retry after the rest; `half` = one probe only | Restart |
| `rover.gateway.retry.enabled` | `false` | Retry the next instance on connect failure before the request is sent | Restart |
| `rover.gateway.rewrite.stripPrefix` | empty | Global rewrite prefix | Restart |
| `rover.gateway.cors.enabled` | `false` in code / `true` in example | CORS switch | Restart |
| `rover.gateway.cors.allowedOrigins` | `[]` | Allowed origins; use `*` only for development | Restart |
| `rover.gateway.cors.allowedMethods` | `[]` | Allowed HTTP methods | Restart |
| `rover.gateway.cors.allowedHeaders` | `[]` | Allowed headers; `*` means any | Restart |
| `rover.gateway.cors.credentials` | `false` | Allow credentials | Restart |
| `rover.gateway.cors.maxAgeSeconds` | `1800` | CORS preflight cache duration | Restart |

The local limiter keeps state inside each Gateway process and does not call Nameserver or an external store. In a multi-instance deployment each Gateway counts independently. For user-, tenant-, or globally coordinated limits, disable this switch and use a custom Filter plugin.

The process-local circuit breaker is also off by default. When enabled it counts consecutive failures per `host:port`: connect failure, timeout, and upstream 5xx increment; 2xx/4xx reset. Open instances are skipped at pick time; if none remain, Gateway returns `503` with `CIRCUIT_OPEN`. It is not a separate Filter. Gateway replicas do not share state.

Connect-fail retry is a separate switch, also off by default. When enabled, Gateway tries one other instance only if the request was never sent. Upstream 5xx, request timeout, a response already started, or a body already flushed to the first instance are not retried. Clients still retry business failures themselves. Counts are in `/_manage/metrics` `resources.retries.connect` and Prometheus `rover_gateway_retries_total{reason="connect"}`.

Use `rover.gateway.adminEnabled: false` when Rover-Admin is not deployed and YAML must be the sole configuration source. Gateway then skips `config/routes.overlay.json` and `config/gateway-runtime.overlay.json`, and `/_manage/**` returns `404`. This does not affect Nameserver discovery or business proxying. Existing overlay files are retained and take effect again if the flag is re-enabled.

A 503 response includes `X-Rover-Reject-Reason`: `INFLIGHT_LIMIT` (in-flight gate full; default `max(64, CPU×8)`, override with `-Drover.gateway.maxInflight`), `NO_UPSTREAM` (no usable upstream), or `CIRCUIT_OPEN` (every candidate is open). Logs print `inflight=used/max`. Counts are in `/_manage/metrics` `resources.rejects` and Prometheus `rover_gateway_rejects_total`. This work runs only on the reject path.

### Route fields

`rover.gateway.routes` is an array. Each item supports `id`, `businessPrefix`,
`serviceName`, `group`, `targetUrl`, `targetUrls`, and `stripPrefix`. Dynamic
discovery uses `serviceName` (and optionally `group`); a route may also use
`targetUrl` or `targetUrls` even when `discovery.type` is `nameserver` or
`nacos`. Do not set both kinds on one route. A `targetUrls` item may use
`http://host:port|weight`. Default `outbound=netty` accepts `http://` only.
Startup and hot-reload reject `https://` so a config cannot pass and then fail on the first request.
If the upstream is really HTTPS, set `proxy.outbound` to `jdk` and restart.
Admin-saved routes are written to
`config/routes.overlay.json` and replace the YAML route list as a whole.

## Gateway runtime configuration

| Key | Default | Description |
| --- | --- | --- |
| `gateway.loadbalance.strategy` | `round_robin` | Startup/plugin-mounting setting; built-in strategy, SPI `name()`, or implementation FQCN; not edited from Admin |
| `gateway.request.timeoutMillis` | `30000` | Gateway request timeout in milliseconds |
| `gateway.filter.enabled` | `true` | Filter chain switch; note the singular `filter` |
| `gateway.rateLimit.enabled` | `false` | Enable the built-in per-Gateway local limiter |
| `gateway.rateLimit.algorithm` | `token_bucket` | `token_bucket` or `sliding_window` |
| `gateway.rateLimit.key` | `path` | `global` for the whole Gateway, or `path` for each request path |
| `gateway.rateLimit.permitsPerSecond` | `1000` | Token bucket refill rate (requests/second) |
| `gateway.rateLimit.burst` | `2000` | Token bucket capacity (requests) |
| `gateway.rateLimit.limit` | `1000` | Maximum requests in one sliding window |
| `gateway.rateLimit.windowSeconds` | `1` | Sliding window length in seconds |
| `gateway.circuitBreaker.enabled` | `false` | Enable the process-local circuit breaker |
| `gateway.circuitBreaker.failureThreshold` | `5` | Consecutive failures before opening |
| `gateway.circuitBreaker.openSeconds` | `10` | How long to stay open |
| `gateway.circuitBreaker.recovery` | `all` | `all` = everyone may retry after the rest; `half` = one probe only |
| `gateway.retry.enabled` | `false` | Retry the next instance on connect failure |
| `gateway.metrics.enabled` | `true` | Metrics collection switch; `false` skips inflight/record on the hot path |
| `gateway.metrics.windowSeconds` | `300` | Metrics sliding window, capped at 300 seconds |
| `gateway.trace.enabled` | `true` | Request timeline switch; `false` skips `markPhase` and minted trace IDs |
| `gateway.trace.slowThresholdMillis` | `100` | Record a timeline when a request exceeds this value |
| `gateway.trace.sampleRate` | `0.0` | `0` slow requests only, `1` all requests, or a decimal in `0..1` |

Plugins that implement `ConfigurablePlugin` add their own hot-reloadable keys
under `gateway.plugin.<namespace>.<key>`. The actual keys, defaults, options,
and validation are declared by each plugin and appear in Admin only while that
plugin is loaded. Their values are persisted in the same overlay. This does
not make plugin JAR installation or replacement hot-reloadable.

Changing any built-in rate-limit setting rebuilds the filter chain atomically:
in-flight requests keep their original filter instance, while subsequent
requests use the new limiter. The limiter is local to each Gateway process;
it is not a distributed quota.

Toggling the circuit breaker rebuilds the filter chain so a disabled breaker
is not held on the pick path. Threshold, open window, and `recovery` mutate
the live settings object and keep existing open-state.

Except for `gateway.loadbalance.strategy`, these hot-update keys are persisted
to `config/gateway-runtime.overlay.json`. Adding or
replacing plugin JARs, or changing ports, bind addresses, tokens, discovery
type, plugin directories, or CORS requires a Gateway restart.

### Configuration precedence

With `adminEnabled: true`, startup precedence is **YAML baseline → Gateway overlay**. An Admin save makes the matching overlay value win after every restart; merely opening Admin does not write the file. The current overlay is a full snapshot, so one save can retain stale values for other keys. If a YAML change appears ignored, inspect `config/gateway-runtime.overlay.json`; for routes inspect `config/routes.overlay.json`, which replaces the complete YAML route list.

`gateway.loadbalance.strategy` is not edited from Admin and is not written to
`config/gateway-runtime.overlay.json`. Configure a custom LoadBalancer with an
SPI `name()` or implementation FQCN in `rover-gateway.yml`, then restart Gateway
so this startup decision remains owned by YAML.

## Nameserver startup configuration

| Key | Default | Description | Apply |
| --- | --- | --- | --- |
| `rover.nameserver.port` | `8888` | TCP registration/discovery/subscription port | Restart |
| `rover.nameserver.bindHost` | `0.0.0.0` | TCP bind address | Restart |
| `rover.nameserver.managePort` | `8889` | HTTP management and client API port | Restart |
| `rover.nameserver.manageBindHost` | `0.0.0.0` | HTTP bind address | Restart |
| `rover.nameserver.token` | empty | Protocol token; blank = no auth (startup WARN). Prefer non-empty or strict security | Restart |
| `rover.nameserver.adminToken` | empty | `/_manage/**` management token; same as above | Restart |
| `rover.nameserver.clientApiEnabled` | `false` | Enable `/v1/client/**` HTTP+JSON registration API | Restart |
| `rover.nameserver.writeAckMode` | `SINGLE` | `SINGLE`/`HALF`/`ALL`; single-node semantics today | Restart |
| `rover.nameserver.allowClientAckOverride` | `false` | Allow clients to override ACK strength | Restart |
| `rover.nameserver.cluster.enabled` | `false` | Reserved cluster switch; keep disabled today | Restart |
| `rover.nameserver.cluster.nodeId` | empty | Reserved node ID | Restart |
| `rover.nameserver.cluster.nodes` | `[]` | Reserved cluster node addresses | Restart |
| `rover.nameserver.cluster.replicationFactor` | `1` | Reserved replica count | Restart |

## Nameserver runtime configuration (Admin hot-update)

| Key | Default | Description |
| --- | --- | --- |
| `nameserver.health.checkIntervalMillis` | `5000` | Health scan interval |
| `nameserver.heartbeat.timeoutMillis` | `15000` | Mark an instance unhealthy after this timeout |
| `nameserver.instance.expireMillis` | `30000` | Expire and remove ephemeral instances after this duration |
| `nameserver.push.enabled` | `true` | Service-change push switch |

These keys are persisted to `config/nameserver-runtime.overlay.json` and remain
subject to component validation and instance state.

`managePort: 0` only disables the Nameserver HTTP management listener; it does not currently ignore an existing `config/nameserver-runtime.overlay.json`. Inspect or remove the applicable overlay value when YAML must regain ownership.
