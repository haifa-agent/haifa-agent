# Haifa Agent Runtime Core

纯 Java 的 Agent 执行内核，负责 Bootstrap、`AgentRunExecutionAttempt`、AgentLoop、工具管线、完成门禁、检查点、恢复、控制命令以及线程安全的内存存储实现。

- 依赖方向：`runtime-core -> context/model-api/runtime-api/tool-api/credential-api -> core -> common`；Runtime 不依赖 Tool Core 或 Provider Integration。
- Runtime 只调用 Core `AgentRun` 的受控行为，不复制生命周期合法性表。
- `start` 在 Run 持久化并提交执行后返回 `PENDING/QUEUED` 快照；等待完成由 `AgentRunHandle` 显式提供。
- 每次 Start、Resume 或崩溃恢复都创建新的 `AgentRunExecutionAttempt`；它记录 Worker、Heartbeat、错误和恢复 Checkpoint，同一逻辑 Run 同时最多一个活动 Attempt。`ExecutionOwnershipPort` 为未来分布式 Lease 保留真实校验边界。
- AgentLoop 固定执行控制检查、状态协调、预算/循环 Guard、Context IR 构建、冻结模型调用、响应归一化、Decision 校验/执行、持久化和 Checkpoint；全部 Middleware 阶段及失败策略显式可测。模型、工具、交互、委派、Trace 和持久化均通过最小 Port 注入。
- Runtime 只接受带 `adapterType + adapterVersion` 的 `AgentChatModel` 注册。`FrozenModelInvoker` 按 Run 快照精确绑定 Adapter；缺失版本时确定性失败，不回退到当前版本，也不重新读取模型目录。
- `ModelMessageAssembler` 是 `AgentContext(PromptComponent/ContextItem)` 到供应商无关 `ModelMessage` 的唯一转换边界；Middleware 产生结构化 Context IR，不拼接共享 Prompt 字符串。
- `ToolCall` 是工具调用的权威记录。`ToolCallPart`/`ToolResultPart` 只保存领域 `ToolCallId`、Provider correlation 等协议引用和有界摘要；组装下一轮模型请求时，从权威 `ToolCall.result()` 重建已归一化的 `structuredData` 与 `truncated`，Runtime idempotency key 不发送给模型。
- 本阶段只允许 Asset 的派生文本、OCR、Transcript 进入 Context；原始 Asset Part 会被拒绝。
- ToolCall 默认顺序执行，并通过 Run 的 `FrozenToolBinding` 完成 alias、精确 SemVer、Schema identity、Capability、Policy、Approval、执行环境、结果归一化、Journal 和持久化；不从全局可变规格表重新解析。
- Tool 审批是可恢复协议：Policy 产生 typed Interaction 与 interaction Checkpoint，Attempt 进入 paused 并释放 Worker；批准或拒绝后新 Attempt 先恢复并校验 Checkpoint，再幂等应用响应。批准继续原 ToolCall 且不重复模型调用，拒绝向模型写入有界结果而不默认取消整个 Run。
- Resume 会重新校验当前调用者授权，并通过 `ToolInvoker.validateBinding` 确认冻结 provider/definition 仍可用；缺失或 hash/provider 漂移时 fail closed，不自动换 Provider。
- Tool Journal 区分 intent、dispatched、acknowledged、pending-result、completed、failed 与 outcome-unknown；非幂等或未知副作用在 dispatch 后失联不会自动重放。
- 模型调用与工具调用使用独立 Retry Policy；仅非副作用 Tool 允许有界自动重试，副作用 Tool 失败后进入不确定性处置而不自动重放。
- Completion Guard 校验输出契约、Artifact、Todo、Pending Tool/Child/Interaction、Policy 和 Budget，并强制 `RUNNING -> COMPLETING -> COMPLETED`。
- `RunTransitionCoordinator` 在 Unit of Work 内提交 Run、Runtime Event 和 Outbox；线程安全内存实现提供乐观锁、Run 内事件序号、稳定命令幂等结果、Outbox 发布/消费幂等和单活动 Attempt 约束。Listener 在提交后通知，异常不影响已提交状态。
- Runtime 使用可信 Run 身份检索 RUN/SESSION/USER Scope 的 ACTIVE Memory；授权和状态过滤先于排序，结果仍通过 `ContextItem` IR 和统一 Token 预算。Checkpoint 只保存 Memory ID/Version、Scope、策略版本和查询摘要，Resume 会重新授权且不会恢复已失效或清除的正文。
- 模块不依赖 Spring、模型 Provider SDK、MCP、Docker、JPA、产品模块或管理端。
