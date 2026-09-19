# 生产检查清单

安全默认 Starter 面向开发体验，而不是 Durable Production Operation。

如果应用真正依赖 Haifa Agent 上线，在部署前应至少确认下面这些边界。

## Identity

- Caller identity 必须来自可信宿主 Authentication Boundary。
- 不要从 Prompt Text 或不可信 Run Request Field 接收 Tenant / Principal。

## Persistence

- 如果产品承诺 Restart Continuity，应选择 Durable Persistence。
- SQLite Database 与外部 Artifact / Media Payload Directory 应设置合适 OS Permission，并建立 Backup Procedure。
- JSONL 只能作为 Projection，不能当 Recovery Database。

## Models

- 只注册经过审查的 Model / Provider Binding。
- Endpoint 与 CredentialRef 应由 Trusted Host Configuration 持有。
- 不要实现会静默改变 Run Frozen Model Semantics 的 Implicit Fallback。

## Credentials

- Secret 不进入 Source Code、ProductProfile、Prompt、Log 或 Browser Response。
- 根据 Deployment 环境选择 Environment / OS Secret Storage 或应用自有 Secret Manager Boundary。

## Tools 与 Execution

- 尽量缩小 Tool Allowlist。
- 只有真正无外部 Side Effect 的 Tool 才标记 pure。
- 为 Side Effect 配置适当 Policy / Approval。
- 牢记 host-guarded Execution 不是 Hostile-code Isolation。

## Recovery

- 明确哪些 User-visible Action 必须跨 Restart 存活。
- 测试 Intentional Interaction / Pause Recovery。
- 测试 Unknown Tool Outcome 与 Interrupted Execution，不要默认假设可以透明 Replay。

## Limits

- 根据产品设置 Run Budget、Timeout、Model Limit、Tool Limit 与 Storage Limit。
- 对支持 Approval 的产品，把 Human Waiting 与 Active Execution Time 分开理解。

## Observability

- 消费安全 Lifecycle / Event，不要通过 Log 记录完整 Prompt / Provider Body。
- 保留 Diagnostic ID 以支持 Incident Investigation。

## Release Verification

构建与测试规则见 [build-support/README.md](../../build-support/README.md)。

请验证你真正准备 Release 的同一个 Commit，而不是验证一个 Commit、发布另一个 Commit。
