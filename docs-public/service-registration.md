# Service Registration

[简体中文](./service-registration.zh-CN.md) · [Documentation index](./README.md)

Rover-Suite offers two provider registration paths that share one Nameserver registry:

| Provider | Recommended path | Capability |
| :--- | :--- | :--- |
| Spring Boot / Java | `rover-nameserver-starter` over TCP `8888` | Registration lifecycle plus the existing Java client capabilities |
| Node.js, Python, Go, long-running PHP, C++ | Small HTTP Registrar over HTTP `8889` | Provider register, heartbeat, and deregister only |

Choose the transport for the provider language. The Gateway always discovers instances through the existing TCP
query/subscription path; business traffic remains ordinary HTTP.

## 1. Nameserver prerequisites

Java registration works on the default TCP listener. HTTP registration is opt-in:

```yaml
rover:
  nameserver:
    port: 8888
    managePort: 8889
    manageBindHost: 10.0.0.10
    token: "replace-with-a-private-protocol-token"
    clientApiEnabled: true
```

`clientApiEnabled` is a startup setting, so restart Nameserver after changing it. The HTTP Registration API
(named Client API in internal configuration) and `/_manage/**` share port `8889`, but use different authorization
domains:

- `/v1/client/**`: `Authorization: Bearer <rover.nameserver.token>`
- `/_manage/**`: `X-Rover-Admin-Token: <rover.nameserver.adminToken>`

The bundled configuration leaves tokens empty for compatibility. When remote HTTP registration is enabled,
configure a non-empty protocol token, bind to a private address, and restrict the port at the network layer.

## 2. Java Spring Boot Starter

### 2.1 Install and add the dependency

The current `1.0.0-SNAPSHOT` is built from source. Install it locally first:

```bash
mvn clean install -DskipTests
```

Then add:

```xml
<dependency>
    <groupId>com.rover</groupId>
    <artifactId>rover-nameserver-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

No annotation or startup-class code is required. Spring Boot discovers the auto-configuration from the Starter.

### 2.2 Configure the provider

```yaml
spring:
  application:
    name: order-service

server:
  port: 8081

rover:
  nameserver:
    enabled: true
    address: 10.0.0.10:8888
    token: "replace-with-the-same-protocol-token"
    host: 10.0.1.20
    instance-id: order-pod-7c9f
    weight: 100
    ephemeral: true
    heartbeat-interval-ms: 5000
```

`service-name` falls back to `spring.application.name`. `port` falls back to the actual Web Server port.
`instance-id` falls back to the resolved `host:port`, but a Pod UID or deployment-instance ID is safer when
addresses may be reused. `host` must be reachable from Gateway; do not use loopback across hosts or Pods.

Common options:

| Key under `rover.nameserver` | Default | Purpose |
| :--- | :--- | :--- |
| `enabled` | `true` | Enable Starter lifecycle |
| `address` | `127.0.0.1:8888` | Nameserver TCP address |
| `service-name` | `spring.application.name` | Registered service name |
| `instance-id` | resolved `host:port` | Unique instance key within a service |
| `host`, `port` | auto-detected | Address advertised to Gateway |
| `group`, `zone`, `metadata` | empty | Placement and custom metadata |
| `weight` | `100` | Load-balancing weight |
| `ephemeral` | `true` | Remove after disconnect/expiry |
| `token` | empty | Nameserver protocol token |
| `connect-timeout-ms`, `request-timeout-ms` | `3000` | TCP operation timeouts |
| `heartbeat-interval-ms` | `5000` | Heartbeat fixed delay |
| `auto-reconnect`, `reconnect-interval-ms` | `true`, `3000` | Connection recovery |
| `register-retry-interval-ms` | `5000` | Initial registration fixed retry delay |

The lifecycle registers after `ApplicationReadyEvent`, retries while Nameserver is unavailable, replays state
after reconnect, and performs best-effort deregistration during normal shutdown.

## 3. HTTP+JSON registration

The HTTP v1 API intentionally covers only ephemeral provider lifecycle:

```text
business listener ready
  → register full instance data
  → heartbeat on a fixed schedule
  → deregister once during graceful shutdown
```

It is not a cross-language query/subscription SDK. There is no Agent, sidecar, extra JAR, or server-side active
probe. The Nameserver stores the lease in memory and removes an abnormally stopped instance after expiry.

### 3.1 Reference implementations

| Language | Implementation | Runtime note |
| :--- | :--- | :--- |
| Node.js | [Registrar](../examples/http-registration/node/rover_registrar.js) | Use one owner in cluster mode |
| Python | [Registrar](../examples/http-registration/python/rover_registrar.py) | Integrate with application lifespan |
| Go | [Registrar package](../examples/http-registration/go/README.md) | Standard library, Go 1.20+ |
| PHP | [Long-running process guide](../examples/http-registration/php/README.md) | CLI/Swoole/RoadRunner/Octane; not request-scoped PHP-FPM |
| C++ | [C++20/libcurl guide](../examples/http-registration/cpp/README.md) | C++20, libcurl, Threads |

The examples are intentionally copyable source references rather than separately published SDK packages. Keep
the state machine intact when adapting them to a framework. The complete overview is in
[`examples/http-registration/README.md`](../examples/http-registration/README.md).

### 3.2 Identity and ownership

- `serviceName`: route-facing service name.
- `instanceId`: unique reachable replica within that service; prefer Pod UID, container instance ID, or
  `host:port` when reuse is controlled.
- `sessionId`: UUID generated once per business process start and reused for all three operations.

The registry key is `serviceName + instanceId`. A new session registering the same key takes ownership
(last-register-wins). Heartbeat or deregistration from the old session receives `409 STALE_SESSION`. Session IDs
do not order concurrent starts, so every live replica must use a unique `instanceId`.

### 3.3 Lifecycle behavior

The supplied references use:

- Immediate register after the business port is ready.
- Fixed-delay register retry every 5 seconds; no exponential backoff.
- Fixed-delay heartbeat every 5 seconds.
- 3-second request timeout and at most one in-flight request.
- Network errors, `408`, `429`, and `5xx`: retry after the fixed delay.
- Only heartbeat `404` with exact code `INSTANCE_NOT_FOUND`: immediately submit the full register request.
- Other `4xx`, including generic `404 NOT_FOUND` and `409 STALE_SESSION`: stop and surface a permanent error.
- Graceful shutdown: stop scheduling and attempt deregistration once without blocking exit indefinitely.

Configurable reference implementations reject retry or heartbeat intervals above 8 seconds. With the default
30-second expiry and 5-second scan, an abnormal HTTP process exit is normally removed roughly 30–35 seconds after
its last accepted heartbeat.

### 3.4 Manual protocol smoke test

The commands below assume `rover.nameserver.token` is `rover-dev-token`. Use one UUID for the complete sequence
and start a real business listener before treating the registration as production traffic:

```bash
export ROVER_NAMESERVER_TOKEN='rover-dev-token'

curl -i -X POST http://127.0.0.1:8889/v1/client/instances/register \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer ${ROVER_NAMESERVER_TOKEN}" \
  --data '{"serviceName":"http-demo","instanceId":"local-18080","sessionId":"11f1b8a4-f588-4a68-a74d-34354013ac4d","host":"127.0.0.1","port":18080,"weight":100,"metadata":{"language":"curl"}}'

curl -i -X POST http://127.0.0.1:8889/v1/client/instances/heartbeat \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer ${ROVER_NAMESERVER_TOKEN}" \
  --data '{"serviceName":"http-demo","instanceId":"local-18080","sessionId":"11f1b8a4-f588-4a68-a74d-34354013ac4d"}'

curl -i -X POST http://127.0.0.1:8889/v1/client/instances/unregister \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer ${ROVER_NAMESERVER_TOKEN}" \
  --data '{"serviceName":"http-demo","instanceId":"local-18080","sessionId":"11f1b8a4-f588-4a68-a74d-34354013ac4d"}'
```

Success requires HTTP `200` and JSON `code: "OK"`. The complete field constraints, response schema, and stable
machine codes are defined by the [OpenAPI v1 contract](../rover-nameserver-core/src/main/resources/openapi/rover-registration-v1.yaml).

### 3.5 Multi-worker rule

One externally reachable endpoint or Pod must have one Registrar owner. Node cluster, Gunicorn/uWSGI,
RoadRunner, Octane, and similar workers must not all register the same `serviceName + instanceId`. Run the
Registrar in the master/container lifecycle, or register workers separately only when each worker owns a unique,
directly reachable port and instance ID.

Ordinary PHP-FPM request lifecycle cannot maintain a background heartbeat. Use the PHP reference only in a
long-running process; its `run()` loop blocks, while an existing event loop can call `start()`, then `tick()`
every 50–100 ms, and `close()` during shutdown.

## 4. Recovery model

- Nameserver keeps online registrations only in memory.
- Nameserver restart produces an empty registry and a new diagnostic `epoch`.
- Java clients reconnect and replay their locally remembered registration.
- HTTP clients discover the missing lease through heartbeat `INSTANCE_NOT_FOUND` and immediately re-register.
- Nameserver never restores an old address merely because it was registered before restart.
- Nameserver does not actively call provider health endpoints; client reports and TTL determine liveness.

This design accepts a short re-registration window to avoid restoring stale endpoints. See
[Architecture](./architecture.md#4-soft-state-leases-and-in-memory-recovery) for the trade-off.

## 5. Registration troubleshooting

| Result | Action |
| :--- | :--- |
| `401 UNAUTHORIZED` | Match the Bearer token to Nameserver `rover.nameserver.token`. |
| `404 NOT_FOUND` | Enable `clientApiEnabled`, confirm port/path, and restart Nameserver. Do not retry forever. |
| `404 INSTANCE_NOT_FOUND` on heartbeat | Re-register immediately with the same session and full instance data. |
| `409 STALE_SESSION` | Stop the old owner and fix duplicate `instanceId` usage. |
| Instance expires repeatedly | Keep heartbeat at the 5-second default, check request timeouts/connectivity, and avoid overlapping schedulers. |
| Gateway cannot reach the instance | Advertise a host and port reachable from the Gateway network. |

For implementation changes, see [Development Guide](./development-guide.md#6-registration-extensions).
