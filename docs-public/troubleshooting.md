# Troubleshooting

Use this guide to diagnose common Rover-Suite runtime issues.

## 1. Symptom table

| Symptom | First checks |
| --- | --- |
| Gateway cannot find an instance | Nameserver TCP address, protocol token, service name and heartbeat interval |
| Admin reports a component offline | Gateway/Nameserver management URL, token and port ACL |
| Configuration update fails | Field type, enum value, hot-reload flag and component logs |
| Route returns 404 | `businessPrefix`, `stripPrefix`, discovery mode and instance health |
| Route returns 502 | Upstream reachability, connection timeout and registration state |
| Route returns 503 | No healthy instance, Gateway resource pressure, rate limiting or plugin rejection |
| HTTP registration returns 401 | `Authorization: Bearer` must match the Nameserver protocol token |
| Management API returns 401 | `X-Rover-Admin-Token` must match the target component admin token |
| Instances are evicted repeatedly | Heartbeat timeout, network jitter and registered host/port reachability |
| Log volume grows quickly | Access log level and `filters.accessLog` |
| Startup or hot-reload says only http upstreams are supported | Default Netty outbound does not forward `https://`. See section 4.1 |
| Confirm the current I/O implementation | Read `ioTransport=` in the startup log. In a Docker Linux container, `auto` is usually epoll |

## 2. Check component health first

```bash
curl -H "X-Rover-Admin-Token: <gateway-admin-token>" \
  http://127.0.0.1:8080/_manage/health

curl -H "X-Rover-Admin-Token: <nameserver-admin-token>" \
  http://127.0.0.1:8889/_manage/health
```

If health checks fail, verify bind address, port mapping, firewall rules and tokens.

## 3. Gateway cannot find an instance

Check these items in order:

1. Nameserver is running at the address configured by Gateway.
2. The route `serviceName` matches the provider service name.
3. Java Starter or HTTP Registrar registered successfully.
4. Protocol tokens match.
5. The registered `host:port` is reachable from the Gateway process.

If Gateway starts before Nameserver, discovery may be completed by the next reconciliation round. The default delay can be about 30 seconds.

## 4. Route returns 404

404 usually means route matching or path rewriting did not match the request.

| Field | Check |
| --- | --- |
| `businessPrefix` | The request path starts with this prefix |
| `stripPrefix` | The upstream path is rewritten as expected |
| `serviceName` | Nameserver has a healthy service with this name |
| `targetUrl/targetUrls` | Static upstreams are reachable. Default outbound forwards `http://` only; see section 4.1 for `https://` |

If routes were changed from Admin, `config/routes.overlay.json` overrides the YAML route list.

### 4.1 Startup or hot-reload says only http upstreams are supported

Default outbound is Netty and forwards `http://` only. Validation rejects an unsupported upstream scheme at
startup or hot-reload so a config cannot pass and then fail on the first request.

Pick one:

- Change the upstream to `http://`. The usual pattern is to terminate caller HTTPS at Nginx, an SLB, or a CDN,
  and keep Gateway-to-service traffic as plain HTTP.
- Set `rover.gateway.proxy.outbound` to `jdk` and restart. That is the first-generation JDK client, kept for
  rollback and comparison; it includes TLS. Throughput then returns to the JDK-outbound band — see the
  [Performance Report](./performance-report.md).

## 5. Route returns 502 or 503

502 usually points to upstream connection or response failures. 503 usually means no healthy instance, resource pressure or plugin rejection.

Check Gateway logs for the matched route and selected upstream. Then verify upstream process state, port reachability, Nameserver health and custom Filter behavior.

## 6. Configuration changes do not take effect

Configuration precedence:

```text
classpath default YAML
  ↓
external config/rover-*.yml
  ↓
Admin runtime overlay
```

When `rover.gateway.adminEnabled: true`, runtime values saved by Admin are written to `config/gateway-runtime.overlay.json`. They override matching YAML fields on the next startup.

## 7. Debug logging

```xml
<logger name="com.rover.gateway.core.filter" level="DEBUG"/>
<logger name="com.rover.nameserver.core.server.NameserverServerHandler" level="DEBUG"/>
```

Restore the default level after diagnosis to avoid long-term disk and I/O overhead.
