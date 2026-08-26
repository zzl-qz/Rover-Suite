# Production Deployment

Rover-Suite currently targets lightweight single-node or small private-network
deployments. Keep business, registration and management ports inside explicit
network boundaries.

Shortest small-team checklist (with yes/no boundaries): [Small-team go-live](./small-team-go-live.md).

## Copy-ready templates

- [`deploy/production/README.md`](../deploy/production/README.md)
- [`deploy/production/rover-nameserver.example.yml`](../deploy/production/rover-nameserver.example.yml)
- [`deploy/production/rover-gateway.example.yml`](../deploy/production/rover-gateway.example.yml)
- [`deploy/production/admin-application.example.yml`](../deploy/production/admin-application.example.yml)

[`deploy/docker`](../deploy/docker/README.md) is a **local demo** only. Do not ship its
fixed tokens to the public internet.

Single-node systemd units: [`deploy/systemd/README.md`](../deploy/systemd/README.md).

## Minimum security configuration

1. Use non-empty random protocol tokens for Gateway and Nameserver.
2. Use separate management tokens for Gateway and Nameserver.
3. Restrict management ports to the operations network; do not expose Admin publicly.
4. Put HTTP/TCP traffic behind TLS termination, an ACL or a VPN. Tokens do not encrypt traffic. Gateway-to-upstream traffic is plain HTTP by default. Terminate caller HTTPS at the reverse proxy. If the upstream URL must be `https://`, set `rover.gateway.proxy.outbound` to `jdk` and restart; throughput then returns to the JDK-outbound band.
5. Disable unused HTTP registration and CORS endpoints.
6. On real hosts, enable strict security so blank tokens refuse to start:

```bash
export ROVER_STRICT_SECURITY=true
# or: java -Drover.strictSecurity=true -jar ...
```

Without strict mode, blank tokens still start with a loud WARN (convenient for local debug).

```yaml
rover:
  nameserver:
    bindHost: 10.0.10.11
    manageBindHost: 10.0.10.11
    token: "replace-with-a-long-protocol-token"
    adminToken: "replace-with-a-different-admin-token"
    clientApiEnabled: false
  gateway:
    server:
      bindHost: 10.0.10.12
    adminToken: "replace-with-the-gateway-admin-token"
    cors:
      enabled: false
```

## Boot order and failure window

1. Start **Nameserver first**, then Gateway, then business services / Admin.
2. While Nameserver is briefly down, Gateway can still proxy using its **local instance cache**;
   push/reconcile catch up when Nameserver returns.
3. If Nameserver **crashes**, the in-memory registry is gone — providers must re-register.
   That is an intentional single-node boundary, not silent HA.

## Health probes

| Endpoint | Purpose |
| --- | --- |
| `GET /_manage/health` | Lightweight `UP` (same admin token as other manage APIs) |
| `GET /_manage/status` | Richer status |

```bash
curl -fsS -H "X-Rover-Admin-Token: YOUR_TOKEN" http://NS_HOST:8889/_manage/health
curl -fsS -H "X-Rover-Admin-Token: YOUR_TOKEN" http://GW_HOST:80/_manage/health
```

## Logging for troubleshooting

| Level | Default | When debugging |
| --- | --- | --- |
| INFO | Startup, hot reload, auth failures, upstream/protocol errors | Keep |
| DEBUG | Access completion, route match, Nameserver connect/disconnect | Turn on temporarily |

Disable the access-log filter with `rover.gateway.filters.accessLog: false`.

## Upgrade checklist

- Run `./deploy/scripts/backup-overlays.sh` (or point it at your config dir).
- Back up route and runtime overlays.
- Verify `_manage/health` / Admin API on the same versions.
- Keep protocol and management tokens stable during a rolling upgrade.
- Check `/api/overview`, route count, healthy instances and error rates after upgrading.
- Smoke: `./deploy/scripts/smoke-compose.sh` (requires Docker).
