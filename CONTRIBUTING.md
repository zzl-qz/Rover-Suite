# Issue Guide / Issue 反馈指南

Rover-Suite is open source under Apache License 2.0 and is maintained with a small-team workflow. Public
collaboration happens through **Issues only**. The repository does not accept external pull requests or merge
requests; maintainers evaluate Issues and implement accepted changes in the main repository.

Rover-Suite 按 Apache License 2.0 开源，并采用小团队维护方式。公开协作入口**仅开放 Issue**，不接收外部
Pull Request 或 Merge Request。维护者会评估 Issue，并在主仓库中实现采纳的修改。

[Open an Issue / 提交 Issue](https://gitee.com/zzl-java/roverSuite/issues)

## Before opening an Issue / 提交前

1. Search existing Issues to avoid duplicates.
2. Check the [Quick Start](./docs-public/quick-start.md), [User Guide](./docs-public/user-guide.md), and
   [current runtime boundaries](./docs-public/user-guide.md#9-current-runtime-boundaries).
3. Reproduce the problem with the smallest configuration possible.
4. Remove tokens, private addresses, personal data, and business payloads from logs and screenshots.

1. 先搜索已有 Issue，避免重复提交。
2. 阅读[快速上手](./docs-public/quick-start.zh-CN.md)、[使用指南](./docs-public/user-guide.zh-CN.md)与
   [当前运行边界](./docs-public/user-guide.zh-CN.md#9-当前运行边界)。
3. 尽量使用最小配置稳定复现问题。
4. 提交日志和截图前，移除 token、私有地址、个人信息与业务数据。

## Bug report / 缺陷反馈

Please include:

- Rover-Suite version, tag, or commit ID.
- JDK, Maven, operating system, and deployment mode.
- Affected component: Gateway, Nameserver, Starter, Admin, or a Registrar example.
- Minimal configuration with secrets removed.
- Exact reproduction steps, expected behavior, and actual behavior.
- Relevant logs, HTTP status/body, stack trace, and whether the issue is reproducible.

请提供：

- Rover-Suite 版本、Tag 或 Commit ID。
- JDK、Maven、操作系统与部署方式。
- 受影响组件：Gateway、Nameserver、Starter、Admin 或 Registrar 示例。
- 已删除密钥的最小配置。
- 完整复现步骤、预期行为与实际行为。
- 相关日志、HTTP 状态与响应、异常栈，以及问题是否能稳定复现。

## Feature request / 功能建议

Describe the real use case first, then explain the current limitation and the smallest useful outcome. Include
compatibility, deployment, maintenance, and performance considerations when relevant. Large frameworks or new
dependencies should explain why existing extension points or a small source-level adapter are insufficient.

请先说明真实使用场景，再描述当前限制与最小可用目标。涉及兼容、部署、维护成本或性能时一并说明。建议引入大型框架
或新依赖时，应解释为什么现有扩展点或小型源码适配层无法解决问题。

## Security and privacy / 安全与隐私

Do not publish real credentials, exploitable private endpoints, or personal data in an Issue. This repository does
not currently provide a private vulnerability-reporting channel; submit only sanitized information that is safe to
disclose publicly. Tokens authenticate requests but do not encrypt traffic.

不要在 Issue 中公开真实凭据、可直接利用的私有地址或个人信息。仓库目前不提供私密漏洞报告通道，请仅提交可公开的
脱敏信息。Token 只提供鉴权，不负责链路加密。

## Maintainers and private forks / 维护者与私有分支

Teams maintaining a private fork can follow the [Development Guide](./docs-public/development-guide.md) or
[二次开发指南](./docs-public/development-guide.zh-CN.md). A behavior change should update tests, OpenAPI when
applicable, examples, both root READMEs, and the corresponding public guides in the same change.

维护私有分支的团队可参考[二次开发指南](./docs-public/development-guide.zh-CN.md)。用户可见行为发生变化时，应在
同一次变更中同步测试、必要的 OpenAPI、示例、中英文 README 与对应公开文档。
