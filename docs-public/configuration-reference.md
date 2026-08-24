# Configuration Reference

External files under `config/` take precedence over classpath defaults. Startup
configuration normally requires a restart. Admin runtime changes are written to
the corresponding `*-runtime.overlay.json`; the component decides whether they
can be applied immediately. The authoritative list of current values, defaults,
and `hotReloadable` flags is returned by `GET /api/configs`.

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
| `rover.gateway.adminToken` | empty | Token for `/_manage/**` | Restart |
| `rover.gateway.server.bindHost` | `0.0.0.0` | Business listener address | Restart |
| `rover.gateway.server.maxContentLengthBytes` | `1048576` | Request body limit | Restart |
| `rover.gateway.proxy.connectTimeoutMillis` | `3000` | Upstream connection timeout | Restart |
| `rover.gateway.proxy.requestTimeoutMillis` | `30000` | Startup default for request timeout | Restart |
| `rover.gateway.discovery.type` | `STATIC` in code / `nameserver` in example | `static` or `nameserver` | Restart |
| `rover.gateway.discovery.nameserver.address` | `127.0.0.1:8888` | Nameserver TCP address | Restart |
| `rover.gateway.discovery.nameserver.reconcileIntervalMs` | `30000` | Local-cache reconciliation interval | Restart |
| `rover.gateway.discovery.nameserver.token` | empty | Gateway-to-Nameserver protocol token | Restart |
| `rover.gateway.filters.enabled` | `true` | Startup filter switch | Restart; runtime key below |
| `rover.gateway.filters.pluginDir` | `plugins` | Filter/LoadBalancer JAR directory | Restart |
| `rover.gateway.filters.classes` | `[]` | Explicit filter implementation FQCNs | Restart |
| `rover.gateway.rateLimit.enabled` | `false` | Per-Gateway local rate-limit switch | Restart |
| `rover.gateway.rateLimit.algorithm` | `token_bucket` | `token_bucket` or `sliding_window` | Restart |
| `rover.gateway.rateLimit.key` | `path` | `global` for one instance or `path` per request path | Restart |
| `rover.gateway.rateLimit.permitsPerSecond` | `1000` | Token-bucket refill rate | Restart |
| `rover.gateway.rateLimit.burst` | `2000` | Token-bucket capacity/burst limit | Restart |
| `rover.gateway.rateLimit.limit` | `1000` | Maximum requests in a sliding window | Restart |
| `rover.gateway.rateLimit.windowSeconds` | `1` | Sliding-window length, 1..3600 seconds | Restart |
| `rover.gateway.rewrite.stripPrefix` | empty | Global rewrite prefix | Restart |
| `rover.gateway.cors.enabled` | `false` in code / `true` in example | CORS switch | Restart |
| `rover.gateway.cors.allowedOrigins` | `[]` | Allowed origins; use `*` only for development | Restart |
| `rover.gateway.cors.allowedMethods` | `[]` | Allowed HTTP methods | Restart |
| `rover.gateway.cors.allowedHeaders` | `[]` | Allowed headers; `*` means any | Restart |
| `rover.gateway.cors.credentials` | `false` | Allow credentials | Restart |
| `rover.gateway.cors.maxAgeSeconds` | `1800` | CORS preflight cache duration | Restart |

The local limiter keeps state inside each Gateway process and does not call Nameserver or an external store. In a multi-instance deployment each Gateway counts independently. For user-, tenant-, or globally coordinated limits, disable this switch and use a custom Filter plugin.

Use `rover.gateway.adminEnabled: false` when Rover-Admin is not deployed and YAML must be the sole configuration source. Gateway then skips `config/routes.overlay.json` and `config/gateway-runtime.overlay.json`, and `/_manage/**` returns `404`. This does not affect Nameserver discovery or business proxying. Existing overlay files are retained and take effect again if the flag is re-enabled.

### Route fields

`rover.gateway.routes` is an array. Each item supports `id`, `businessPrefix`,
`serviceName`, `group`, `targetUrl`, `targetUrls`, and `stripPrefix`. Dynamic
discovery uses `serviceName` (and optionally `group`); static routes use
`targetUrl` or `targetUrls`. A `targetUrls` item may use
`http://host:port|weight`. Admin-saved routes are written to
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
| `gateway.metrics.enabled` | `true` | Metrics collection switch |
| `gateway.metrics.windowSeconds` | `300` | Metrics sliding window, capped at 300 seconds |
| `gateway.trace.enabled` | `true` | Request timeline switch |
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

Except for `gateway.loadbalance.strategy`, these hot-update keys are persisted
to `config/gateway-runtime.overlay.json`. Adding or
replacing plugin JARs, or changing ports, bind addresses, tokens, discovery
type, plugin directories, or CORS requires a Gateway restart.

### Precedence and common pitfalls

With `adminEnabled: true`, startup precedence is **YAML baseline → Gateway overlay**. An Admin save makes the matching overlay value win after every restart; merely opening Admin does not write the file. The current overlay is a full snapshot, so one save can retain stale values for other keys. If a YAML change appears ignored, inspect `config/gateway-runtime.overlay.json`; for routes inspect `config/routes.overlay.json`, which replaces the complete YAML route list.

`gateway.loadbalance.strategy` is not edited from Admin and is not written to
`config/gateway-runtime.overlay.json`. Configure a custom LoadBalancer with an
SPI `name()` or implementation FQCN in `rover-gateway.yml`, then restart Gateway
so Admin cannot overwrite the plugin-mounting decision.

## Nameserver startup configuration

| Key | Default | Description | Apply |
| --- | --- | --- | --- |
| `rover.nameserver.port` | `8888` | TCP registration/discovery/subscription port | Restart |
| `rover.nameserver.bindHost` | `0.0.0.0` | TCP bind address | Restart |
| `rover.nameserver.managePort` | `8889` | HTTP management and client API port | Restart |
| `rover.nameserver.manageBindHost` | `0.0.0.0` | HTTP bind address | Restart |
| `rover.nameserver.token` | empty | Registration/subscription protocol token | Restart |
| `rover.nameserver.adminToken` | empty | `/_manage/**` management token | Restart |
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
