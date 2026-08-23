# Troubleshooting and Performance Boundaries

| Symptom | First checks |
| --- | --- |
| Gateway cannot find an instance | Nameserver TCP address, protocol token, service name and heartbeat interval |
| Admin reports a component offline | Management URL, management token and network ACL |
| Configuration update fails | Field type, enum value, hot-reload flag and downstream logs |
| Route returns 404 | `businessPrefix`, `stripPrefix`, discovery mode and instance health |
| Instances are evicted repeatedly | Heartbeat timeout, network jitter and registered host/port reachability |
| HTTP registration returns 401 | `Authorization: Bearer` token matching Nameserver protocol token |
| Management returns 401 | `X-Rover-Admin-Token` matching the target component |

Rover-Suite does not claim a universal QPS limit. Throughput depends on route
count, upstream latency, connection reuse, JDK/CPU, metric windows, trace
sampling and network placement. Admin is an observation surface, not a load
generator; disable live polling during a load test or keep only low-frequency
overview checks.

For each release, record Gateway throughput and P50/P95/P99 latency, registry
cost at different instance counts, CPU/heap/GC with metrics and tracing
enabled or disabled, and Admin management request rate. Include CPU, memory,
JDK, operating system and load-tool versions. `wrk` or `hey` can generate
traffic while JVM, system, GC, network and `/api/live` data are collected.
