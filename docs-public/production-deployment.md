# Production Deployment

Rover-Suite currently targets lightweight single-node or small private-network
deployments. Keep business, registration and management ports inside explicit
network boundaries.

## Minimum security configuration

1. Use non-empty random protocol tokens for Gateway and Nameserver.
2. Use separate management tokens for Gateway and Nameserver.
3. Restrict management ports to the operations network; do not expose Admin publicly.
4. Put HTTP/TCP traffic behind TLS termination, an ACL or a VPN. Tokens do not encrypt traffic.
5. Disable unused HTTP registration and CORS endpoints.

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

See [`deploy/docker`](../deploy/docker/README.md) for the local Compose demo.
Replace tokens, image tags, port mappings and bind/ACL policies before using
any deployment outside a trusted development network.

## Upgrade checklist

- Back up route and runtime overlays.
- Verify the Admin API against the same Gateway/Nameserver versions.
- Keep protocol and management tokens stable during a rolling upgrade.
- Check `/api/overview`, route count, healthy instances and error rates after upgrading.
