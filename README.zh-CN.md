<div align="center">

![Rover-Suite](./img/rover-mark.png)

# Rover-Suite

轻量级微服务中间件套件

Java 17 + Netty · 注册中心 · HTTP 网关 · Spring Boot Starter

[English](README.md) · [简体中文](README.zh-CN.md) · [Architecture](docs-public/architecture.md)

![Java](https://img.shields.io/badge/Java-17-orange?style=flat-square)
![Netty](https://img.shields.io/badge/Netty-4.1-blue?style=flat-square)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2-green?style=flat-square)
![Maven](https://img.shields.io/badge/build-Maven-brightgreen?style=flat-square)
![License](https://img.shields.io/badge/license-Apache%202.0-blue?style=flat-square)

[简介](#-项目简介) · [特性](#-核心特性) · [架构](#-架构概览) · [快速开始](#-快速开始) · [接入](#-业务接入) · [路线图](#-路线图)

</div>

---

## 📖 项目简介

**Rover-Suite** 是一套可独立部署的轻量微服务基础设施，核心能力包括：

| 组件 | 说明 |
| :--- | :--- |
| **Rover-Nameserver** | 基于 TCP 的服务注册与发现 |
| **Rover-Gateway** | 基于 Netty 的 HTTP 网关（路由、发现、负载均衡、反向代理） |
| **Rover-Starter** | Spring Boot 接入，业务侧自动注册与优雅下线 |
| **Rover-Admin** | 可选管理控制台，支持运行时配置查看与更新 |

适合希望部署轻量、链路清晰、便于私有化与二次扩展的场景。

---

## ✨ 核心特性

| 特性 | 说明 |
| :--- | :--- |
| **自研核心组件** | Nameserver 与 Gateway 均基于 Netty 实现，核心不依赖 Spring Cloud |
| **独立进程部署** | 注册中心与网关均可单独打包运行 |
| **低侵入接入** | 引入 Starter 并完成 YAML 配置即可注册 |
| **静态 / 动态路由** | 支持固定上游与注册中心动态发现，共用负载均衡能力 |
| **可扩展** | Filter、负载均衡、服务发现等提供 SPI 扩展点 |
| **运行时管理** | Admin 可查看并更新网关 / 注册中心运行时配置 |

---

## 🗺️ 架构概览

```mermaid
flowchart LR
    subgraph Callers["调用方"]
        C1[客户端 / 浏览器]
        C2[外部系统]
    end

    subgraph GW["Rover-Gateway"]
        H[HTTP Server]
        F[FilterChain]
        D[服务发现]
        P[反向代理]
        H --> F --> P
        D --> P
    end

    subgraph NS["Rover-Nameserver"]
        R[注册 / 心跳 / 推送]
    end

    subgraph Biz["业务服务"]
        S1[Service + Starter]
        S2[Service + Starter]
    end

    C1 --> H
    C2 --> H
    P -->|HTTP| S1
    P -->|HTTP| S2
    S1 -->|TCP| R
    S2 -->|TCP| R
    R -.->|实例变更| D
```

模块依赖与主链路说明见 **[docs-public/architecture.md](./docs-public/architecture.md)**。

---

## 🏗️ 模块一览

| 模块 | 职责 |
| :--- | :--- |
| `rover-common` | 协议、编解码与公共能力 |
| `rover-nameserver-core` | 注册中心核心逻辑 |
| `rover-nameserver-server` | Nameserver 可执行进程 |
| `rover-nameserver-client` | 注册中心客户端 |
| `rover-nameserver-starter` | Spring Boot Starter |
| `rover-gateway-core` | 网关核心逻辑 |
| `rover-gateway-bootstrap` | Gateway 可执行进程 |
| `rover-gateway-adapter-nacos` | 外部注册中心适配扩展 |
| `rover-admin` | 管理控制台 |
| `rover-demo` | 示例业务服务 |

---

## 🚀 快速开始

### 环境要求

- JDK 17+
- Maven 3.6+

### 1. 构建

```bash
git clone https://gitee.com/zzl-java/roverSuite.git
cd roverSuite
mvn clean package -DskipTests
```

### 2. 启动 Nameserver

```bash
java -jar rover-nameserver-server/target/rover-nameserver-server-1.0.0-SNAPSHOT.jar
```

默认监听 TCP `8888`。

### 3. 启动 Gateway

```bash
java -jar rover-gateway-bootstrap/target/rover-gateway-bootstrap-1.0.0-SNAPSHOT.jar
```

默认端口见 `rover-gateway.yml`（本机可按需改为 `8080`）。

### 4. 启动示例服务

```bash
mvn -pl rover-demo spring-boot:run
```

### 5. 可选：Admin

```bash
mvn -pl rover-admin spring-boot:run
```

默认访问地址：`http://127.0.0.1:9090`

---

## 🔌 业务接入

### Maven 依赖

```xml
<dependency>
    <groupId>com.rover</groupId>
    <artifactId>rover-nameserver-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 配置示例

```yaml
spring:
  application:
    name: demo-service

server:
  port: 8081

rover:
  nameserver:
    enabled: true
    address: 127.0.0.1:8888
    host: 127.0.0.1
    weight: 100
    ephemeral: true
    heartbeat-interval-ms: 5000
```

Gateway 动态发现示例：

```yaml
rover:
  gateway:
    discovery:
      type: nameserver
      nameserver:
        address: 127.0.0.1:8888
    loadbalance:
      strategy: round_robin
    routes:
      - id: demo-api
        businessPrefix: /api/demo
        serviceName: demo-service
        stripPrefix: /api/demo
```

---

## 📊 定位对比

| 维度 | 传统 Spring Cloud 方案 | Rover-Suite |
| :--- | :--- | :--- |
| 部署形态 | 依赖较重的组件生态 | 核心链路以独立 Jar 运行 |
| 框架耦合 | 通常强依赖 Spring Cloud | 核心基于 Netty；仅 Starter 使用 Spring Boot |
| 注册中心 | 常见为 Nacos 等 | 自研 TCP Nameserver |
| 网关 | 常见为 Spring Cloud Gateway | 自研 Netty Gateway |
| 适用场景 | 大规模微服务治理 | 轻量部署、私有化与可定制扩展 |

---

## 🗺️ 路线图

- [x] Nameserver 注册 / 心跳 / 推送
- [x] Gateway 路由、发现、负载均衡与反向代理
- [x] Spring Boot Starter 接入
- [x] Admin 运行时管理
- [ ] 流量治理能力增强（限流、鉴权、熔断等）
- [ ] 外部注册中心适配完善
- [ ] 高可用与集群能力增强

---

## 📚 文档

- [公开文档索引](./docs-public/README.md)
- [架构说明](./docs-public/architecture.md)

---

## 🤝 贡献

欢迎通过 Issue 与 Pull Request 参与贡献。

---

## 📄 许可证

本项目基于 [Apache License 2.0](./LICENSE) 开源。

---

## 📮 联系

- 仓库：https://gitee.com/zzl-java/roverSuite
- Issue：请在仓库中提交

<p align="center">
  <sub>Rover-Suite — Lightweight microservice infrastructure.</sub>
</p>
