<div align="center">

![Rover-Suite](img/rover-logo.svg)

<br/>

[English](README.md) · [简体中文](README.zh-CN.md) · [Documentation](docs-public/README.md) · [Architecture](docs-public/architecture.md)

<br/>

![GitHub stars](https://img.shields.io/github/stars/zzl-qz/Rover-Suite?style=flat-square)
![GitHub forks](https://img.shields.io/github/forks/zzl-qz/Rover-Suite?style=flat-square)
![GitHub issues](https://img.shields.io/github/issues/zzl-qz/Rover-Suite?style=flat-square)
![GitHub release](https://img.shields.io/github/v/release/zzl-qz/Rover-Suite?include_prereleases&style=flat-square)
![GitHub license](https://img.shields.io/github/license/zzl-qz/Rover-Suite?style=flat-square)

<br/>

![Java](https://img.shields.io/badge/Java-17-orange?style=flat-square)
![Netty](https://img.shields.io/badge/Netty-4.1-blue?style=flat-square)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2-green?style=flat-square)
![Maven](https://img.shields.io/badge/build-Maven-brightgreen?style=flat-square)
![Status](https://img.shields.io/badge/status-preview-0ea5e9?style=flat-square)

<br/>

[Quick Start](#quick-start) · [Highlights](#highlights) · [Screenshots](#screenshots) · [Integration](#integration-paths) · [Roadmap](#roadmap)

</div>

> Lightweight microservice infrastructure with built-in service discovery and gateway routing, so small teams do not have to hardcode backend addresses in every Nginx or frontend configuration.

## Quick Start

The fastest local demo uses Docker Compose. It starts Nameserver, Gateway, Admin, and a demo backend:

```bash
git clone https://github.com/zzl-qz/Rover-Suite.git roverSuite
cd roverSuite/deploy/docker
docker compose up --build
```

Verify the request path:

```bash
curl -i http://127.0.0.1:8080/api/hello
```

Open Admin at <http://127.0.0.1:9090>. See the [Docker Compose demo](deploy/docker/README.md) for cleanup, smoke tests, and production safety notes.

## Highlights

- **Gateway + service discovery in one small stack:** Gateway routes through a local instance cache while Nameserver owns registration, health, and snapshot push.
- **Mixed-language registration:** Java services can use the Starter; Node.js, Python, Go, PHP, and C++ can register through the HTTP+JSON API.
- **Runtime visibility:** Admin shows routes, instances, recent events, sampled request traces, metrics, and hot-reloadable settings.
- **Extension points without a heavy platform:** Java SPI filters and load-balancers cover common customization without introducing a full service mesh.

## What It Solves

Rover-Suite is for small teams running multiple monoliths, side services, or mixed-language apps that outgrow hardcoded upstream addresses. Instead of editing Nginx or every frontend whenever a backend moves, providers register with Nameserver and Gateway routes to healthy instances.

## Screenshots

<p align="center">
  <img src="docs-public/assets/rover-suite-architecture.gif" alt="Rover-Suite animated system architecture" width="100%">
</p>

<p align="center">
  <img src="docs-public/images/admin/01-dashboard-overview.png" alt="Rover-Admin dashboard overview" width="100%">
</p>

<div align="center">

[View the static architecture preview](./docs-public/assets/rover-suite-architecture.png) · [Open the interactive architecture diagram](./docs-public/rover-suite-architecture-editorial.html) · [Read the architecture and trade-offs](./docs-public/architecture.md)

</div>

## Current Runtime Boundaries

- Current version: `1.0.0-SNAPSHOT` single-node preview.
- Nameserver is an in-memory soft-state registry; clients re-register after restart.
- WebSocket/SSE, grouped discovery, cold-start recovery, and clustered high availability are not presented as implemented capabilities.
- Defaults favor local or trusted-network startup. Across a trust boundary, restrict bind addresses and configure protocol/admin tokens plus outer TLS/ACL controls.
- SDK artifacts are not published to Maven Central yet; build from source until the first public SDK release.

## Integration paths

| Scenario | Entry point |
| :--- | :--- |
| Java Spring Boot service | [Service Registration](docs-public/service-registration.md) · `rover-nameserver-starter` |
| Node.js / Python / Go / PHP / C++ service | [HTTP+JSON Registrar examples](examples/http-registration/README.md) |
| Static upstream or dynamic discovery route | [User Guide](docs-public/user-guide.md) |
| Runtime configuration and operations | [Admin User Guide](docs-public/admin-guide.md) · [Admin API](docs-public/admin-api.md) |
| Filter / LoadBalancer customization | [Plugin Development](docs-public/plugin-development.md) |

## Documentation map

| Goal | Recommended reading |
| :--- | :--- |
| Run the first end-to-end request | [Quick Start](docs-public/quick-start.md) |
| Understand runtime configuration and boundaries | [User Guide](docs-public/user-guide.md) · [Configuration Reference](docs-public/configuration-reference.md) |
| Understand the system design | [Architecture](docs-public/architecture.md) |
| Deploy, harden, and go live | [Production Deployment](docs-public/production-deployment.md) · [Small-team go-live](docs-public/small-team-go-live.md) · [Docker Compose](deploy/docker/README.md) |
| Troubleshoot and benchmark | [Troubleshooting](docs-public/troubleshooting.md) · [Benchmark Guide](docs-public/benchmark-guide.md) · [Performance Report](docs-public/performance-report.md) |
| Change source code or maintain docs | [Development Guide](docs-public/development-guide.md) · [Release Checklist](docs-public/release-checklist.md) |

See [docs-public/README.md](docs-public/README.md) for the complete English/Chinese index.

## Project structure

| Directory | Responsibility |
| :--- | :--- |
| `rover-common` | Protocol, codec, and shared capabilities |
| `rover-nameserver-*` | Nameserver core, process, client, and Starter |
| `rover-gateway-*` | Gateway core, process, and optional Nacos adapter |
| `rover-admin` | Optional runtime management console |
| `rover-gateway-test` | Demo backend and test frontend; verification only |
| `docs-public` | Public English/Chinese documentation shipped with the source |

## Roadmap

- Distributed tracing beyond the local Gateway request timeline.
- Broader traffic governance, including auth policies and distributed limiting.
- Nameserver cluster high availability while retaining lease-based soft state.

## Feedback and license

- Issues and pull requests: [report a bug, request a feature, or contribute on GitHub](https://github.com/zzl-qz/Rover-Suite/issues)
- Read the [Contribution Guide](CONTRIBUTING.md) and [Security Policy](SECURITY.md) before contributing.
- Rover-Suite is licensed under the [Apache License 2.0](LICENSE); third-party dependencies retain their own licenses (see [NOTICE](NOTICE)).
