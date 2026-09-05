# Quick Start

[简体中文](./quick-start.zh-CN.md) · [Documentation index](./README.md)

This guide runs one complete request through Rover-Suite:

```text
curl :8080/api/hello
  → Rover-Gateway :8080
  → demo-service :8081

demo-service → Rover-Nameserver :8888 (TCP registration)
```

The HTTP Registration API on port `8889` is not required for this Java demo.

## 1. Prerequisites

- JDK 17 or later
- Maven 3.6 or later
- `curl`
- Three terminal windows

Node.js is only needed for the optional frontend demo.

## 2. Get and build the source

```bash
git clone https://gitee.com/zzl-java/roverSuite.git
cd roverSuite
mvn clean install -DskipTests
```

Use `install`, not only `package`: the project currently uses `1.0.0-SNAPSHOT`, which is not published to a
public Maven repository, and the demo/other local projects resolve the artifacts from your local Maven cache.

## 3. Configure local listeners and Gateway port 8080

The bundled configuration intentionally favors zero-config startup: listeners bind all interfaces and authentication
is empty, which is convenient on a local or trusted network. This guide copies both complete files and binds the
demo to loopback to avoid accidental exposure; a trusted deployment may deliberately keep the defaults.

```bash
mkdir -p config
cp rover-gateway-bootstrap/src/main/resources/rover-gateway.yml config/rover-gateway.yml
cp rover-nameserver-bootstrap/src/main/resources/rover-nameserver.yml config/rover-nameserver.yml
```

Ensure these values are present in `config/rover-nameserver.yml`:

```yaml
rover:
  nameserver:
    bindHost: 127.0.0.1
    manageBindHost: 127.0.0.1
```

Then set the Gateway to loopback and port `8080` in `config/rover-gateway.yml`:

```yaml
rover:
  gateway:
    port: 8080
    server:
      bindHost: 127.0.0.1
    discovery:
      type: nameserver
      nameserver:
        address: 127.0.0.1:8888
    routes:
      - id: demo-api
        businessPrefix: /api
        serviceName: demo-service
        stripPrefix: ""
```

Rover loads `./config/rover-*.yml` before the classpath files. Treat an external YAML file as the complete startup
configuration for that process; do not assume it is textually merged with the bundled YAML.

## 4. Start the three processes

Run each command from the repository root.

Terminal 1 — Nameserver:

```bash
java -jar rover-nameserver-bootstrap/target/rover-nameserver-bootstrap-1.0.0-SNAPSHOT.jar
```

Terminal 2 — demo business service:

```bash
java -jar rover-gateway-test/backend/target/rover-demo-1.0.0-SNAPSHOT.jar
```

Wait until the Spring Boot application reports that it has started. The Starter registers `demo-service` at
`127.0.0.1:8081` after the web server is ready.

Terminal 3 — Gateway:

```bash
java -jar rover-gateway-bootstrap/target/rover-gateway-bootstrap-1.0.0-SNAPSHOT.jar
```

## 5. Verify the request path

The bundled Nameserver configuration leaves `adminToken` empty for local compatibility, so these read-only
checks require no header:

```bash
curl -sS http://127.0.0.1:8889/_manage/status
curl -sS http://127.0.0.1:8889/_manage/instances
```

The instance list should contain `demo-service`. Then verify the public request path:

```bash
curl -i http://127.0.0.1:8080/api/hello
```

A successful `2xx` response from the demo proves that the provider registered, the Gateway discovered it, the
route matched, and the reverse proxy completed the request.

You can also inspect the process logs for:

- Nameserver listening on TCP `8888` and HTTP `8889`.
- `demo-service` registration succeeding after the application is ready.
- Gateway discovery connecting to `127.0.0.1:8888`.

## 6. Stop

Stop the demo with `Ctrl+C`. A normal Spring shutdown performs a best-effort deregistration. Then stop the
Gateway and Nameserver.
Nameserver removes the deregistered instance immediately. The current Gateway protects a last-instance empty push,
so its local cache is cleared no later than the next query reconciliation — up to `reconcileIntervalMs` (30 seconds)
with the default configuration.

## First-run troubleshooting

| Symptom | Check |
| :--- | :--- |
| Gateway cannot bind | Confirm that the external Gateway config uses `8080`, not the bundled default `80`. |
| Gateway returns no available instance | Start Nameserver before the demo, then check that both use `127.0.0.1:8888`. The Java provider retries every 5 seconds. If Gateway started first, the current version may wait until the next 30-second reconciliation; restarting Gateway also refreshes immediately. |
| Gateway route is not found | Check `businessPrefix: /api`, `serviceName: demo-service`, and that a stale `config/routes.overlay.json` is not overriding YAML routes. |
| Gateway gets connection refused from the provider | `rover.nameserver.host` must be reachable from the Gateway. `127.0.0.1` is only correct when all processes run on one machine. |
| Authentication fails | Nameserver, Starter, and Gateway discovery must use the same protocol token. See the [User Guide](./user-guide.md). |

Next steps:

- [User Guide](./user-guide.md)
- [Service Registration](./service-registration.md)
- [Architecture](./architecture.md)
- [Development Guide](./development-guide.md)
