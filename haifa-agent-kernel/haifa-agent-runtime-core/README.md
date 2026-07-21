# Haifa Agent Runtime Core

纯 Java 的 Agent 执行内核，负责 Bootstrap、`AgentRunExecutionAttempt`、AgentLoop、工具管线、完成门禁、检查点、恢复、控制命令以及线程安全的内存存储实现。

- 依赖方向：`runtime-core -> runtime-api -> core -> common`。
- Runtime 只调用 Core `AgentRun` 的受控行为，不复制生命周期合法性表。
- `start` 在 Run 持久化并提交执行后返回 `PENDING/QUEUED` 快照；等待完成由 `AgentRunHandle` 显式提供。
- 每次 Start、Resume 或崩溃恢复都创建新的 `AgentRunExecutionAttempt`；它记录 Worker、Heartbeat、错误和恢复 Checkpoint，同一逻辑 Run 同时最多一个活动 Attempt。`ExecutionOwnershipPort` 为未来分布式 Lease 保留真实校验边界。
- AgentLoop 固定执行控制检查、状态协调、预算/循环 Guard、上下文构建、模型选择/调用、响应归一化、Decision 校验/执行、持久化和 Checkpoint；全部 Middleware 阶段及失败策略显式可测。模型、工具、交互、委派、Trace 和持久化均通过最小 Port 注入。
- ToolCall 默认顺序执行，并经过存在性、Capability、Schema、Policy、Approval、执行环境、结果归一化、Journal 和持久化；不确定的非幂等副作用会阻止完成和盲目重放。
- 模型调用与工具调用使用独立 Retry Policy；仅非副作用 Tool 允许有界自动重试，副作用 Tool 失败后进入不确定性处置而不自动重放。
- Completion Guard 校验输出契约、Artifact、Todo、Pending Tool/Child/Interaction、Policy 和 Budget，并强制 `RUNNING -> COMPLETING -> COMPLETED`。
- `RunTransitionCoordinator` 在 Unit of Work 内提交 Run、Runtime Event 和 Outbox；线程安全内存实现提供乐观锁、Run 内事件序号、稳定命令幂等结果、Outbox 发布/消费幂等和单活动 Attempt 约束。Listener 在提交后通知，异常不影响已提交状态。
- 模块不依赖 Spring、模型 Provider SDK、MCP、Docker、JPA、产品模块或管理端。
