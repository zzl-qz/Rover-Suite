# Compatibility, Upgrades and Extensions

## Version policy

- Use SemVer: breaking protocol/API changes require a major version; compatible features use a minor version; fixes use a patch version.
- `SNAPSHOT` builds are development artifacts without upgrade guarantees.
- Tag every release and publish JARs, checksums, dependency trees and migration notes.
- Back up route/runtime overlays before upgrading and verify management API and protocol tokens afterwards.

## Compatibility boundaries

- Nameserver TCP registration/subscription and the HTTP Registration API are the primary cross-version contracts.
- Admin API is a same-release control-plane API; clients should not depend on undocumented fields.
- Adding configuration keys is normally compatible. Removing keys, changing types or changing defaults requires a changelog entry.
- `rover.gateway.proxy.outbound` and `rover.gateway.server.ioTransport` are chosen at startup. Hot reload does not switch the outbound client or I/O implementation. Restart Gateway after changing them. Confirm the current values from `outbound=` / `ioTransport=` in the startup log.

## Plugin development

Gateway supports Filter and LoadBalancer extensions. For the complete project
layout, SPI files, packaging, configuration, verification, and troubleshooting,
see [Plugin Development](./plugin-development.md).

```java
public final class ExampleLoadBalancer implements LoadBalancer {
    @Override public String name() { return "example"; }
    // Implement choose(...) according to the LoadBalancer interface.
}
```

Plugins run inside the Gateway process. Do not block Netty event loops, leak
threads/connections or log sensitive request data.

## Nacos adapter status

`rover-gateway-adapter-nacos` is currently a reserved adapter skeleton. It does
not provide a usable Nacos discovery runtime integration and must not be
advertised as “Nacos supported”. The supported discovery modes are the built-in
Nameserver and static upstreams; use an external adapter or implement the
`ServiceDiscovery` extension until the Nacos adapter is completed.
