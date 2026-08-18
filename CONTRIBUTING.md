# Contributing to Rover-Suite / 参与 Rover-Suite

Thank you for improving Rover-Suite. Focused issues and pull requests are welcome. The project is maintained
with a small-team mindset: changes should be understandable, testable, and easy to revert.

感谢你参与 Rover-Suite。欢迎聚焦的 Issue 与 Pull Request。本项目按小团队方式维护：变更应当容易理解、可验证、可回滚。

## Before coding / 开始前

1. Search existing issues and pull requests.
2. For a behavior change or large refactor, open an issue first and explain the problem, scope, compatibility
   impact, and smallest viable design.
3. Read the [Architecture](./docs-public/architecture.md) and
   [Development Guide](./docs-public/development-guide.md) before changing a core contract.

1. 先搜索已有 Issue 与 Pull Request。
2. 如果要修改行为或大规模重构，请先提 Issue，说明问题、范围、兼容影响与最小可行方案。
3. 修改核心契约前，请阅读[架构说明](./docs-public/architecture.zh-CN.md)与
   [二次开发指南](./docs-public/development-guide.zh-CN.md)。

## Build and test / 构建与测试

```bash
mvn clean verify
```

For a focused Java change, use `mvn -pl <module> -am test`, then run the wider suite before submission. When
changing a cross-language Registrar, run the matching language checks listed in the
[Development Guide](./docs-public/development-guide.md#10-validation-and-contribution-checklist).

Java 定向变更可以先执行 `mvn -pl <module> -am test`，提交前再跑更完整的测试。修改跨语言 Registrar 时，请执行
[二次开发指南](./docs-public/development-guide.zh-CN.md#10-验证与贡献检查)中对应的语言检查。

## Change rules / 变更规则

- Keep each pull request focused on one problem.
- Add tests for bug fixes and user-visible behavior.
- Preserve registration ownership, idempotency, revision, push, and pure in-memory lease invariants unless the
  pull request explicitly proposes an architecture change.
- Do not bypass `RegistrationService` from a new registration transport.
- Do not advertise reserved or skeleton integrations as supported.
- Do not commit tokens, private endpoints, local `config/` files, IDE files, logs, or build artifacts.
- Do not include unrelated formatting or generated-file churn.

- 每个 Pull Request 只聚焦一个问题。
- Bug 修复与用户可见行为必须补测试。
- 除非 PR 明确提出架构变更，否则保留注册 owner、幂等、revision、push 与纯内存租约语义。
- 新的注册传输层不能绕过 `RegistrationService`。
- 不要把预留枚举或骨架模块宣传为已支持。
- 不要提交 token、私有地址、本地 `config/`、IDE 文件、日志或构建产物。
- 不要夹带无关格式化或生成文件噪音。

## Documentation / 文档

Public behavior is not complete until its documentation is updated. In the same pull request, update as needed:

- OpenAPI for HTTP contract changes.
- Reference implementations for lifecycle changes.
- Root English and Chinese READMEs for positioning or entry-point changes.
- English and Chinese public guides for configuration or usage changes.
- Architecture documentation for a changed invariant or trade-off.

公开行为只有在文档同步后才算完成。HTTP 契约、生命周期、项目入口、配置/使用方式或架构不变量变化时，请在同一 PR 中同步对应 OpenAPI、示例、中英文 README 与公开指南。

## Pull request description / PR 说明

Include:

- What problem is solved and what is deliberately out of scope.
- Compatibility and operational impact.
- Exact verification commands and results.
- Configuration, security, or migration notes.
- Known limitations and follow-up work.

PR 中请说明：解决什么问题、哪些内容不在范围内、兼容/运维影响、实际验证命令与结果、配置/安全/迁移注意事项，以及已知限制。

By contributing, you agree that your contribution is licensed under the repository's
[Non-Commercial License](./LICENSE).

提交贡献即表示你同意该贡献按本仓库的 [非商用许可](./LICENSE) 许可。
