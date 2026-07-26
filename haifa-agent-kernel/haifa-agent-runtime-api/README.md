# Haifa Agent Runtime API

## Replayable model output

`outputEvents` and `addOutputListener` expose a replayable, transport-neutral projection of model output. Public
events contain only assistant text deltas and committed/failed/superseded lifecycle state. They never contain
reasoning, unvalidated tool arguments, prompts, credentials, or provider responses. Callers resume from a
`RunOutputCursor` whose sequence is monotonic within the Run.

定义 Runtime 的稳定入口、查询、恢复、命令、Interaction Response、Handle 和监听契约，不包含默认 AgentLoop 实现。

- 允许依赖：`haifa-agent-core`（传递依赖 `common`）和 JDK。
- 禁止依赖：Runtime 实现、产品、Spring、模型 Provider、MCP、Sandbox Provider、Admin。
- 所有公共类型位于 `io.haifa.agent.runtime.api`。
- API 调用是同步提交契约，执行是异步的：`start` 在持久化并提交执行后尽快返回 `PENDING/QUEUED` Snapshot。
- `AgentRunRequest` 携带幂等键、类型安全 Definition/Session ID、ProductProfile、Project、目标、输入和受控 `RuntimeOverrides`，不接受配置快照、Tenant 或 Principal。
- Runtime 根据可信 Caller Context 解析并冻结最终 Definition/Profile 版本，内部创建内容寻址配置快照。
- Session 与 Definition 没有固定绑定，同一 Session 可按每次请求选择不同的版本化 Definition。
- `AgentRunSnapshot` 是某一时点的运行视图；Core `AgentRunResult` 是最终结构化业务结果；`AgentRunHandle` 只是基于 Snapshot/Command 的便利等待层，等待超时不会取消 Run。
- Find、Resume 和 Command 均使用正式的 `AgentRunId`；Resume 可选择指定 Checkpoint。
- 公共命令只有 `PAUSE`、`CANCEL`、`TERMINATE_CHILDREN`；`InteractionResponse` 以 Request/Response ID 关联 Clarification 或 Approval，并从可信 Caller Context 获取操作者。Timeout/Lease Lost 等只属于 Runtime 内部 Control Signal。
- 新的 `InteractionView`、`InteractionResponseSubmission/Receipt` 使用稳定 Kind/Action/Input、
  revision 和错误码；旧 `InteractionResponse`/Snapshot 返回路径暂时保留为单向兼容层。
- `RunInputSubmission` 只表达活动 Run 的 Steer，拒绝 Tool 协议 Part；Follow-up 仍由产品层在同一
  Session 创建新 Run。
- `events`/`subscribe` 已冻结 provider-neutral 接口和可关闭订阅类型，但默认实现明确为
  `UnsupportedOperationException`；完整 Journal 投影、Cursor range read 和 replay-then-tail 属于
  Task 02，现有 `outputEvents` 不冒充完整 Run Feed。
