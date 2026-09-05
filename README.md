<div align="center">

![Rover-Suite](img/rover-logo.svg)

<br/>

[English](README.md) · [简体中文](README.zh-CN.md) · [Documentation](docs-public/README.md) · [Architecture](docs-public/architecture.md)

<br/>

![Java](https://img.shields.io/badge/Java-17-orange?style=flat-square)
![Netty](https://img.shields.io/badge/Netty-4.1-blue?style=flat-square)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2-green?style=flat-square)
![Maven](https://img.shields.io/badge/build-Maven-brightgreen?style=flat-square)
![License](https://img.shields.io/badge/License-Apache--2.0-blue?style=flat-square)
![Status](https://img.shields.io/badge/status-preview-0ea5e9?style=flat-square)

<br/>

[Introduction](#introduction) · [Architecture](#architecture) · [Quick Start](#quick-start) · [Integration](#integration-paths) · [Roadmap](#roadmap)

</div>

> **Project status:** `1.0.0-SNAPSHOT` single-node preview. Public documentation describes implemented behavior and calls out runtime boundaries explicitly.
>
> **License:** Apache License 2.0. Rover names, logos, and other brand identifiers are not granted as trademarks; see [NOTICE](NOTICE).

## Introduction

Rover-Suite is a standalone Gateway + Service Discovery combination for small teams running multiple monoliths or mixed-language services. Providers register with Nameserver, while Gateway uses a local instance cache for routing, load balancing, and reverse proxying instead of hardcoded backend addresses in every frontend or Nginx configuration.

| Request plane | Discovery plane | Management and extension plane |
| :--- | :--- | :--- |
| Netty Gateway: routing, filters, load balancing, reverse proxy | Nameserver: Java TCP, HTTP+JSON, health, snapshot push | Admin, Java SPI Filter/LoadBalancer, optional Nacos adapter |

## Architecture

The request data plane and discovery control plane are separate: Gateway reads a local instance cache on the request path, while Nameserver owns registration, health, and instance snapshots.

<p align="center">
  <img src="docs-public/assets/rover-suite-architecture.gif" alt="Rover-Suite animated system architecture" width="100%">
</p>

> Animated signals cover provider registration, discovery reconciliation, request processing, and Admin control paths. If your Markdown viewer does not animate GIFs, use the static preview or interactive diagram below.

<div align="center">

[View the static architecture preview](./docs-public/assets/rover-suite-architecture.png) · [Open the interactive architecture diagram](./docs-public/rover-suite-architecture-editorial.html) · [Read the architecture and trade-offs](./docs-public/architecture.md)

</div>

## Quick Start

The complete configuration, boot order, and first-run troubleshooting are in the [Quick Start guide](docs-public/quick-start.md). The shortest path is:

```bash
git clone https://gitee.com/zzl-java/roverSuite.git
cd roverSuite
mvn clean install -DskipTests
```

Copy `rover-gateway-bootstrap/src/main/resources/rover-gateway.yml` and
`rover-nameserver-bootstrap/src/main/resources/rover-nameserver.yml` into `config/`. Follow the guide to bind
the local Gateway to `127.0.0.1:8080`, then start the three processes:

```bash
java -jar rover-nameserver-bootstrap/target/rover-nameserver-bootstrap-1.0.0-SNAPSHOT.jar
java -jar rover-gateway-test/backend/target/rover-demo-1.0.0-SNAPSHOT.jar
java -jar rover-gateway-bootstrap/target/rover-gateway-bootstrap-1.0.0-SNAPSHOT.jar
curl -i http://127.0.0.1:8080/api/hello
```

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

## Current runtime boundaries

- Nameserver is currently an in-memory soft-state registry; clients re-register after a restart.
- The documented examples target a single-node ordinary HTTP path. WebSocket/SSE, grouped discovery, cold-start recovery, and clustered high availability are not presented as implemented capabilities.
- Defaults favor zero-config startup on a local or trusted network. Across a trust boundary, restrict bind addresses and configure protocol/admin tokens plus outer TLS/ACL controls.

## Roadmap

- Distributed tracing beyond the local Gateway request timeline.
- Broader traffic governance, including auth policies and distributed limiting.
- Nameserver cluster high availability while retaining lease-based soft state.

## Feedback and license

- Issues: [report a bug or request a feature](https://gitee.com/zzl-java/roverSuite/issues)
- Read the [Contribution Guide](CONTRIBUTING.md) and [Security Policy](SECURITY.md) before contributing.
- Rover-Suite is licensed under the [Apache License 2.0](LICENSE); third-party dependencies retain their own licenses (see [NOTICE](NOTICE)).
