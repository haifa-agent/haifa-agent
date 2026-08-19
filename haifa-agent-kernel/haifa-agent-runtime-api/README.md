# Haifa Agent Runtime API

## Authoritative plan view

`AgentRuntime.plan(runId)` exposes an immutable, caller-scoped `AgentPlanView` when the
Runtime has a persisted plan for the Run. The view includes the authoritative plan revision
and Todo lifecycle state; implementations that do not support plan queries fail fast through
the default method. It is a read-only projection and does not add a second plan state machine.

## Durable model-call lifecycle

The provider-neutral Run Event Feed includes bounded `model.call.started`,
`model.call.succeeded`, and `model.call.failed` lifecycle facts. Their typed
`ModelLifecycle` payload contains only the call identifier, provider/model identifiers,
iteration/attempt, safe status or reason codes, token counts, and finish reason. Prompt
content, assistant output, endpoints, credentials, and raw provider failures are excluded.

## Transient model output

`subscribeOutput(runId, cursor, listener)` 和 `outputEvents` 暴露 provider-neutral 的进程内模型输出通道。
它只包含 Assistant text delta 和 started/committed/failed/superseded 生命周期，不包含 reasoning、未校验
Tool 参数、Prompt、凭据或 Provider 原始响应。`RunOutputSubscription` 必须关闭；订阅按 Run 隔离，
Listener 失败不会中断 AgentLoop。

`RunOutputCursor` 只在当前进程、当前活动 Run 的有界内存缓冲内单调有效；它不是持久化 Cursor，不能在
进程重启后恢复未完成 Delta。Run 终态提交后缓冲与 Listener 会被清理，调用方应从权威
`session_message`/Turns 查询完整 Assistant Message。Assistant text delta 按 Provider chunk 原样保留；
纯空格、换行或制表符 chunk 是合法输出，不会被 display-text 规范化。旧的全局、不可注销
`addOutputListener` 已停止支持。

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
  Resume 与 Command 可携带 expected Run version，供 HTTP `If-Match` 映射；版本检查仍由
  Runtime 事实执行，Transport 不维护第二份版本。
- `recover(runId)` 只接管仍处于执行态、但物理 Attempt 已不属于当前 Runtime 实例的 Run；实现必须
  复用冻结配置、Checkpoint 和 Tool Journal，并拒绝重复接管仍被当前实例拥有的 Attempt。默认方法
  fail fast，既有第三方 Runtime 保持兼容。
- 公共命令只有 `PAUSE`、`CANCEL`、`TERMINATE_CHILDREN`；`InteractionResponse` 以 Request/Response ID 关联 Clarification 或 Approval，并从可信 Caller Context 获取操作者。Timeout/Lease Lost 等只属于 Runtime 内部 Control Signal。
- 新的 `InteractionView`、`InteractionResponseSubmission/Receipt` 使用稳定 Kind/Action/Input、
  revision 和错误码；旧 `InteractionResponse`/Snapshot 返回路径暂时保留为单向兼容层。新增入口使用
  fail-fast default method，既有第三方 `AgentRuntime` 实现保持源码兼容，支持新能力时再显式覆盖。
- `RunInputSubmission` 只表达活动 Run 的 Steer，拒绝 Tool 协议 Part；Follow-up 仍由产品层在同一
  Session 创建新 Run。
- `events`/`subscribe` 是 provider-neutral 的完整 Run Feed 接口。默认接口实现仍显式
  `UnsupportedOperationException`，便于第三方旧实现 fail fast；`DefaultAgentRuntime` 已提供
  排他 Cursor 范围读取和可关闭的 replay-then-tail 订阅。
- `RunEventCursor` 是嵌入式结构化 Cursor；远程 Adapter 使用 Runtime Core 提供的
  `OpaqueRunEventCursorCodec` 编解码不透明值。Cursor 绑定 Run、feed version 和最后已交付 sequence，
  不能与 `RunOutputCursor`、Outbox offset 或数据库 rowid 混用。
- `AgentRunEvent` 只携带 `RunEventPayloads` 的有界 typed payload。未知内部 Journal Event 不外发，
  但 Feed Cursor 会继续推进；`model.output.*` 不属于 durable Run Event Feed。
- `DeliveryLifecycle` 的预算阈值投影除稳定 reason code 和剩余百分比外，还携带枚举化限制资源、当前用量
  与冻结上限；旧六参数构造入口继续投影 `NONE/0/0`，调用方不需要从一个聚合百分比猜测是哪项资源。
- `AgentRunViewSnapshot` 组合可信 Session ID 与 Run Snapshot，供 Adapter 显式投影外部
  `RunView`；它不改变 Core Run 状态机。

## 两个错误平面

`RuntimeApiErrorCode` 只表达提交、查询、命令、Interaction、Cursor 和协议失败；异步执行中的
Run/Attempt/Step 失败继续使用 Core `AgentErrorCode`。失败 Run 的生命周期事件携带稳定执行
错误码、安全默认文案和同一个可选 `diagnosticId`，调用方不需要解析异常消息。
