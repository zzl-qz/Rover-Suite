# Small-team go-live (10 checks)

Shortest checklist for **single-node / small private-network** deploys. Details: [Production Deployment](./production-deployment.md). Templates: [`deploy/production`](../deploy/production/README.md).

## Boundaries (read first)

| Yes | No |
| --- | --- |
| Small teams, private network, one or a few nodes | Nameserver cluster HA / multi-DC registry |
| In-memory registry + push/reconcile | Silent registry restore after process death (apps must re-register) |
| Token + network isolation for manage plane | Full auth / circuit-breaking platform out of the box |
| Overlay hot-update with backup script | Drop-in replacement for APISIX / Nacos stacks |

## Ten checks

1. **Use production samples, not demo tokens.** Start from `deploy/production/*.example.yml`.
2. **Strict security on.** `ROVER_STRICT_SECURITY=true` (or `-Drover.strictSecurity=true`) so blank tokens fail startup.
3. **Split manage vs data plane.** Restrict Nameserver protocol/manage, Gateway data, and Admin by network; keep Admin off the public internet.
4. **Distinct tokens.** Gateway admin, Nameserver admin, and protocol tokens must differ.
5. **Disable unused surfaces.** e.g. `clientApiEnabled: false`, CORS off unless required.
6. **Boot order.** Nameserver → Gateway → apps / Admin; see `deploy/systemd/`.
7. **Health in monitoring.** `GET /_manage/health` with `X-Rover-Admin-Token`; Compose smoke: `deploy/scripts/smoke-compose.sh`.
8. **Backup overlays.** Run `deploy/scripts/backup-overlays.sh` on a schedule; do not commit `backups/`.
9. **Keep logs sane.** Access log is debug + `filters.accessLog`; raise DEBUG only while debugging.
10. **Own the memory boundary.** If Nameserver dies, the registry is gone until apps re-register — not silent HA.

Still stuck on 404/502/registration: [Troubleshooting](./troubleshooting-performance.md). Before a public release: [Release checklist](./release-checklist.md).
