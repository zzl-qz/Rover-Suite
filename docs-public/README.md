# Rover-Suite Documentation / Rover-Suite 公开文档

This directory contains the documentation shipped with the source repository. Start with the quick start, then
choose the guide that matches your task.

本目录中的文档会随源码仓库一起发布。建议先跑通快速上手，再根据实际任务选择使用或二开文档。

## Start here / 开始使用

| Topic | English | 简体中文 |
| :--- | :--- | :--- |
| Run the first end-to-end request | [Quick Start](./quick-start.md) | [快速上手](./quick-start.zh-CN.md) |
| Configure and operate Rover-Suite | [User Guide](./user-guide.md) | [使用指南](./user-guide.zh-CN.md) |
| Register Java and non-Java providers | [Service Registration](./service-registration.md) | [服务注册指南](./service-registration.zh-CN.md) |

## Understand and extend / 理解与二开

| Topic | English | 简体中文 |
| :--- | :--- | :--- |
| Architecture and trade-offs | [Architecture](./architecture.md) | [架构与权衡](./architecture.zh-CN.md) |
| Local development and extension points | [Development Guide](./development-guide.md) | [二次开发指南](./development-guide.zh-CN.md) |
| Cross-language Registrar source examples | [Node.js / Python / Go / PHP / C++](../examples/http-registration/README.md) | 示例目录内文档以中文为主 |
| HTTP Registration API contract | [OpenAPI v1](../rover-nameserver-core/src/main/resources/openapi/rover-registration-v1.yaml) | 同一份可机读契约 |

## Project entry points / 项目入口

- [English README](../README.md)
- [中文 README](../README.zh-CN.md)
- [Gateway demo and test suite](../rover-gateway-test/demo/README.md)
- [Issue feedback policy](../CONTRIBUTING.md)
- [Open an Issue / 提交 Issue](https://gitee.com/zzl-java/roverSuite/issues)
- [Apache 2.0 License](../LICENSE)

Current source status: JDK 17+, Maven build, version `1.0.0-SNAPSHOT`. Snapshot artifacts are built from source
and are not documented as published to a public Maven repository yet.

当前源码状态：JDK 17+、Maven 构建、版本 `1.0.0-SNAPSHOT`。SNAPSHOT 构件需要从源码构建，尚不能按已发布到公共 Maven 仓库使用。

This is currently a single-node preview. It is complete enough for the documented ordinary HTTP and empty-group
workflow, while operational boundaries such as last-instance reconciliation, grouped discovery, cold-start recovery,
and buffered proxying are listed in [User Guide: Current runtime boundaries](./user-guide.md#9-current-runtime-boundaries).

当前定位是单机预览版，已覆盖文档中的普通 HTTP 与默认空分组链路。最后实例对账、分组发现、冷启动恢复和整包代理等
运行边界统一记录在[使用指南：当前运行边界](./user-guide.zh-CN.md#9-当前运行边界)，避免 README 与真实代码能力不一致。

Documentation should describe released code, not planned behavior. When a public contract, configuration key, or
extension boundary changes, update the corresponding guide and both root READMEs in the same change.

公开文档只描述当前代码已实现的行为。修改公开契约、配置项或扩展边界时，请在同一次变更中同步更新对应指南与中英文 README。
