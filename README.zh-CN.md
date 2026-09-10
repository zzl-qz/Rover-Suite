<div align="center">

![Rover-Suite](img/rover-logo.svg)

<br/>

[English](README.md) · [简体中文](README.zh-CN.md) · [公开文档](docs-public/README.md) · [架构](docs-public/architecture.zh-CN.md)

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

[快速开始](#快速开始) · [核心亮点](#核心亮点) · [截图](#截图) · [接入方式](#接入方式) · [路线图](#路线图)

</div>

> 轻量微服务基础设施，自带注册中心与网关，告别在每个 Nginx 或前端配置里手动维护后端地址。

## 快速开始

最快的本地演示使用 Docker Compose，一次拉起 Nameserver、Gateway、Admin 和 demo 后端：

```bash
git clone https://github.com/zzl-qz/Rover-Suite.git roverSuite
cd roverSuite/deploy/docker
docker compose up --build
```

验证请求链路：

```bash
curl -i http://127.0.0.1:8080/api/hello
```

Admin 地址：<http://127.0.0.1:9090>。停止、验收脚本和生产安全提醒见 [Docker Compose 本地一键启动](deploy/docker/README.md)。

## 核心亮点

- **网关和注册发现一套小栈解决：** Gateway 通过本地实例缓存转发请求，Nameserver 负责注册、健康检查和快照推送。
- **混合语言接入：** Java 服务可用 Starter，Node.js、Python、Go、PHP、C++ 可通过 HTTP+JSON API 注册。
- **运行态可观察：** Admin 提供路由、实例、最近事件、请求追踪、指标和可热更新配置视图。
- **轻量扩展：** Java SPI Filter / LoadBalancer 覆盖常见定制场景，不必一上来引入完整服务网格。

## 解决什么问题

Rover-Suite 面向多单体、旁路服务和混合语言服务逐渐变多的小团队。服务地址变化时，业务服务注册到 Nameserver，Gateway 只路由到健康实例，不再到处改 Nginx 或前端配置。

## 截图

<p align="center">
  <img src="docs-public/assets/rover-suite-architecture.gif" alt="Rover-Suite 动态系统架构图" width="100%">
</p>

<p align="center">
  <img src="docs-public/images/admin/01-dashboard-overview.png" alt="Rover-Admin 仪表盘概览" width="100%">
</p>

<div align="center">

[查看静态架构图](./docs-public/assets/rover-suite-architecture.png) · [打开可交互架构图](./docs-public/rover-suite-architecture-editorial.html) · [阅读架构与权衡](./docs-public/architecture.zh-CN.md)

</div>

## 当前运行边界

- 当前版本：`1.0.0-SNAPSHOT` 单机预览版。
- Nameserver 当前是纯内存软状态，重启后的实例由客户端重新注册。
- WebSocket/SSE、分组发现、冷启动恢复和集群高可用不应按已实现能力理解。
- 默认配置适合本地或可信网络零配置启动；跨越信任边界时，请收紧监听地址并配置协议、管理面 token 和外层 TLS/ACL。
- SDK 构件暂未发布到 Maven Central；第一个公开 SDK 版本发布前，请从源码构建。

## 接入方式

| 场景 | 入口 |
| :--- | :--- |
| Java Spring Boot 服务 | [服务注册指南](docs-public/service-registration.zh-CN.md) · `rover-nameserver-starter` |
| Node.js / Python / Go / PHP / C++ 服务 | [HTTP+JSON Registrar 示例](examples/http-registration/README.md) |
| 固定上游或动态服务发现路由 | [使用指南](docs-public/user-guide.zh-CN.md) |
| 查看或更新运行时配置 | [Admin 使用手册](docs-public/admin-guide.zh-CN.md) · [Admin API](docs-public/admin-api.zh-CN.md) |
| Filter / LoadBalancer 二次开发 | [插件开发与接入](docs-public/plugin-development.zh-CN.md) |

## 文档地图

| 目标 | 推荐阅读 |
| :--- | :--- |
| 第一次跑通请求 | [快速上手](docs-public/quick-start.zh-CN.md) |
| 理解运行方式和配置边界 | [使用指南](docs-public/user-guide.zh-CN.md) · [配置项参考](docs-public/configuration-reference.zh-CN.md) |
| 理解系统设计 | [架构与权衡](docs-public/architecture.zh-CN.md) |
| 部署、加固和上线 | [生产部署](docs-public/production-deployment.zh-CN.md) · [小团队上线 10 条](docs-public/small-team-go-live.zh-CN.md) · [Docker Compose](deploy/docker/README.md) |
| 排查问题和验证性能 | [故障排查](docs-public/troubleshooting.zh-CN.md) · [性能测试指南](docs-public/benchmark-guide.zh-CN.md) · [性能报告](docs-public/performance-report.zh-CN.md) |
| 修改源码或维护公开文档 | [二次开发指南](docs-public/development-guide.zh-CN.md) · [发布前检查清单](docs-public/release-checklist.zh-CN.md) |

完整的中英文索引见 [docs-public/README.md](docs-public/README.md)。

## 项目结构

| 目录 | 职责 |
| :--- | :--- |
| `rover-common` | 协议、编解码和公共能力 |
| `rover-nameserver-*` | Nameserver 核心、进程、客户端和 Starter |
| `rover-gateway-*` | Gateway 核心、进程和可选 Nacos 适配器 |
| `rover-admin` | 可选运行时管理控制台 |
| `rover-gateway-test` | Demo 后端与测试前端，仅用于验证 |
| `docs-public` | 随源码发布的中英文公开文档 |

## 路线图

- 分布式追踪集成，超出 Gateway 本地请求时间线。
- 更完整的流量治理能力，例如鉴权策略和分布式限流。
- Nameserver 集群高可用，继续保持租约软状态模型。

## 反馈与许可证

- Issue 与 Pull Request：[在 GitHub 提交缺陷反馈、功能建议或贡献代码](https://github.com/zzl-qz/Rover-Suite/issues)
- 贡献前请阅读[贡献指南](CONTRIBUTING.md)和[安全策略](SECURITY.md)。
- Rover-Suite 使用 [Apache License 2.0](LICENSE)，第三方依赖遵循各自许可证，汇总见 [NOTICE](NOTICE)。
