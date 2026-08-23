# Rover-Admin User Guide

[Admin API](./admin-api.md) · [Documentation index](./README.md)

Rover-Admin is an optional same-origin static console. It does not store
Gateway or Nameserver business configuration. It reads management snapshots
and forwards route/runtime configuration changes to the components.

## Start

```bash
mvn -pl rover-admin spring-boot:run
```

Default URL: `http://127.0.0.1:9090/`. In production, copy
`rover-admin/src/main/resources/application.yml` to a protected external
configuration and set the Gateway/Nameserver management URLs and a non-empty
`rover.admin.admin-token`.

## Pages

| Page | Purpose |
| --- | --- |
| Dashboard | QPS, latency, status codes, in-flight requests, JVM and registry overview |
| Request tracing | Sampled Gateway request timelines |
| Routes | Create, update and delete routes |
| Instances | Registered Nameserver instances and health |
| Recent events | Registration, removal, health and push events |
| Configuration | Runtime Gateway/Nameserver settings |

## Screenshots and quick orientation

The following screenshots come from a local demo environment. Addresses,
service names, timestamps and traffic are demonstration data.

### Dashboard

![Admin dashboard overview](images/admin/01-dashboard-overview.png)

The dashboard separates **instant** values (the previous full second), **near
window** values (the selected 1m/5m window), and **cumulative/process** values
(JVM, CPU, threads, GC and uptime). The QPS axis follows the observed peak; it
is not a Gateway capacity limit.

![Admin process and environment](images/admin/02-dashboard-process.png)

Use the process section when latency rises without an obvious error-rate
increase. Check heap, old generation, CPU, threads and GC, then confirm the
Gateway port, discovery mode, load-balancer strategy and Nameserver settings.

### Request tracing

![Request tracing list](images/admin/03-traces-list.png)

Tracing contains only sampled requests. Filter by `traceId`, path or slow
requests. Higher sampling improves visibility but increases Gateway recording
and memory overhead.

![Request tracing phases](images/admin/04-traces-detail.png)

Expand a row to inspect decode, filters, route matching, discovery,
load-balancing, upstream processing and response writing. Start with the phase
that owns the largest share of total time.

### Routes

![Route list](images/admin/05-routes-list.png)

The route list shows `businessPrefix`, service name, static targets and
`stripPrefix`. Saving applies the update to Gateway and persists it. Check for
overlapping prefixes before changing a route.

![Route editor](images/admin/06-route-editor.png)

Dynamic discovery routes use `serviceName`; static routes use `targetUrl` or
`targetUrls`. `stripPrefix` controls which matched path is removed before the
request reaches the upstream.

### Instances

![Instances](images/admin/07-instances.png)

Check service name, instance address, group, health, ephemeral status, weight,
last heartbeat and idle time. When Gateway cannot find a service, verify this
page before debugging the route.

### Recent events

![Recent events](images/admin/08-events.png)

The ring buffer retains up to 200 recent events. The filter always contains
registration, unregistration, expiration eviction, unhealthy marking and
change push. High-frequency heartbeats and queries remain counters rather than
individual events so they do not hide lifecycle changes.

### Configuration

![Configuration overview](images/admin/09-configs-overview.png)

Green “hot reload” badges identify settings that can be applied immediately.
Save controls appear only after a value changes.

![Configuration details](images/admin/10-configs-detail.png)

`gateway.loadbalance.strategy` expects a strategy name such as `round_robin`.
`gateway.trace.sampleRate` accepts a decimal from 0 to 1; `0` records only slow
requests and `1` records all requests. Window values use seconds, while most
timeouts and health settings use milliseconds.

## Lightweight usage

- Live data is polled while the dashboard is visible; background tabs and other
  pages avoid the high-frequency live request.
- Admin talks to component management APIs, not business services.
- Restrict port `9090` to the operations network; do not expose Admin publicly.
