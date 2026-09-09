<div align="center">

![Rover-Suite](img/rover-logo.svg)

<br/>

[English](README.md) · [简体中文](README.zh-CN.md) · [公开文档](docs-public/README.md) · [架构](docs-public/architecture.zh-CN.md)

<br/>

![Java](https://img.shields.io/badge/Java-17-orange?style=flat-square)
![Netty](https://img.shields.io/badge/Netty-4.1-blue?style=flat-square)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2-green?style=flat-square)
![Maven](https://img.shields.io/badge/build-Maven-brightgreen?style=flat-square)
![License](https://img.shields.io/badge/License-Apache--2.0-blue?style=flat-square)
![Status](https://img.shields.io/badge/status-preview-0ea5e9?style=flat-square)

<br/>

[项目简介](#项目简介) · [架构概览](#架构概览) · [快速开始](#快速开始) · [接入方式](#接入方式) · [路线图](#路线图)

</div>

> **项目状态：** `1.0.0-SNAPSHOT` 单机预览版。公开文档只描述当前代码已经实现的能力，并明确标注运行边界。
>
> **许可：** Apache License 2.0。Rover 名称、Logo 等品牌标识不随 Apache-2.0 授权，详见 [NOTICE](NOTICE)。

## 项目简介

Rover-Suite 为多单体、混合语言的小团队提供一套可独立部署的 Gateway + Service Discovery 组合：业务服务启动后注册到 Nameserver，Gateway 通过本地实例缓存完成路由、负载均衡和反向代理，避免在每个前端或 Nginx 配置中维护后端地址。

| 请求数据面 | 注册发现面 | 管理与扩展面 |
| :--- | :--- | :--- |
| Netty Gateway：路由、Filter、负载均衡、反向代理 | Nameserver：Java TCP、HTTP+JSON、健康检查、快照推送 | Admin、Java SPI Filter/LoadBalancer、可选 Nacos 适配器 |

## 架构概览

请求数据面与注册发现控制面分离：请求进入 Gateway 后读取本地实例缓存；Nameserver 负责注册、健康检查和实例快照。

<p align="center">
  <img src="docs-public/assets/rover-suite-architecture.gif" alt="Rover-Suite 动态系统架构图" width="100%">
</p>

> 动态信号覆盖服务注册、发现对账、请求处理和 Admin 管理链路。如果你的 Markdown 阅读器不播放 GIF，请使用下方的静态预览或可交互架构图。

<div align="center">

[查看静态架构图](./docs-public/assets/rover-suite-architecture.png) · [打开可交互架构图](./docs-public/rover-suite-architecture-editorial.html) · [阅读架构与权衡](./docs-public/architecture.zh-CN.md)

</div>

## 快速开始

完整配置、启动顺序和首次排障见[快速上手](docs-public/quick-start.zh-CN.md)。最短路径如下：

```bash
git clone https://github.com/zzl-qz/Rover-Suite.git roverSuite
cd roverSuite
mvn clean install -DskipTests
```

复制 `rover-gateway-bootstrap/src/main/resources/rover-gateway.yml` 和
`rover-nameserver-bootstrap/src/main/resources/rover-nameserver.yml` 到 `config/`，按指南将本地 Gateway
设置为 `127.0.0.1:8080`，再分别启动三个进程：

```bash
java -jar rover-nameserver-bootstrap/target/rover-nameserver-bootstrap-1.0.0-SNAPSHOT.jar
java -jar rover-gateway-test/backend/target/rover-demo-1.0.0-SNAPSHOT.jar
java -jar rover-gateway-bootstrap/target/rover-gateway-bootstrap-1.0.0-SNAPSHOT.jar
curl -i http://127.0.0.1:8080/api/hello
```

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

## 当前运行边界

- Nameserver 当前是纯内存软状态，重启后的实例由客户端重新注册。
- 当前文档和示例面向单机普通 HTTP 链路；WebSocket/SSE、分组发现、冷启动恢复和集群高可用不应按已实现能力理解。
- 默认配置适合本地或可信网络的零配置启动；跨越信任边界时，请收紧监听地址并配置协议、管理面 token 和外层 TLS/ACL。

## 路线图

- 分布式追踪集成，超出 Gateway 本地请求时间线。
- 更完整的流量治理能力，例如鉴权策略和分布式限流。
- Nameserver 集群高可用，继续保持租约软状态模型。

## 反馈与许可证

- Issue 与 Pull Request：[在 GitHub 提交缺陷反馈、功能建议或贡献代码](https://github.com/zzl-qz/Rover-Suite/issues)
- 贡献前请阅读[贡献指南](CONTRIBUTING.md)和[安全策略](SECURITY.md)。
- Rover-Suite 使用 [Apache License 2.0](LICENSE)，第三方依赖遵循各自许可证，汇总见 [NOTICE](NOTICE)。
