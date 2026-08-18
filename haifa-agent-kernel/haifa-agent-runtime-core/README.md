# Haifa Agent Runtime Core

## Model-call client events

`FrozenModelInvoker` records each physical model attempt as durable
`model.call.started` plus one terminal `model.call.succeeded` or `model.call.failed`
event. `RuntimeClientEventProjector` exposes only the bounded provider-neutral
`ModelLifecycle` fields; model text and provider payloads remain outside the durable
client feed.

## 结构化完成纠偏

`CompletionPolicy` 返回 Provider-neutral 的 `CompletionPolicyResult`：包括稳定
`CompletionBlocker(code, safeMessage, recoverable, evidenceRequirement)` 与安全 Evidence Code。
Runtime Core 不依赖 Coding 产品类型，也不读取 Coding 表。Final 缺少证据时，AgentLoop 追加
`completion.deferred` 安全事件和固定顺序、Agent-visible 且用户不可见的纠偏 Session Message；次数由 `RepairRetryPolicy` 限制，
默认产品装配最多两次。纠偏计数保存在权威 Session Message metadata，Checkpoint/进程恢复时重建，
耗尽后以 `COMPLETION_REPAIR_EXHAUSTED` 失败，不能伪装为成功。

Client Event 投影只把结构化字段映射为 `DeliveryLifecycle`，用于 Recovering、Verifying 和 Budget
Threshold 展示；Prompt、Host Path、stderr、Fingerprint 和 Tool 原始参数不进入公共投影。

`RuntimeControlTraceReplay` 为这些生产控制事件提供确定性只读 Reducer。Replay 输入只接受安全事件
类型与白名单化结构字段，不读取 Prompt、Credential、Provider Response、命令、stderr 或 Host Path；
测试覆盖环境恢复、失败簇策略切换、结构化终止、Completion Repair、验证完成、outcome unknown、
Checkpoint 恢复、失败副作用和 Interaction/Approval 继续等十类控制序列。未知事件保持前向兼容，
非幂等 outcome unknown 只记录为不可重放，不触发执行。

## 自主恢复与有效进展

AgentLoop 按 Tool 坐标、操作族、语义失败类别、稳定错误码、资源类别和 Sandbox 摘要生成
SHA-256 Failure Fingerprint；随机路径、命令文本和原始 stderr 不进入身份。无有效进展的同一失败簇按
“诊断、改变策略、收敛、第 4 次结构化终止”推进；`OUTCOME_UNKNOWN`、取消和 Policy 拒绝继续服从
各自更严格的既有边界。完全重复 Decision、A-B Loop 和单批重复调用仍由原 Guard 独立处理。

重复 Tool 失败或 Outcome Unknown 触发结构化终止时，Run 仍保持 `FAILED`，但 Runtime 会在同一
Unit of Work 中持久化一条用户可见的部分完成总结和 Run output。总结只使用已持久化的安全事实：
成功 Tool 的有界 `purpose`、最后未完成的有界 `purpose`、稳定错误码和安全 Step 错误；不读取或
回显 Tool 参数正文、Prompt、stderr、Provider 原始响应或凭据。Conversation 刷新或进程重启后仍可
看到已完成事项、未完成事项和需要人工处理的下一步，且不会把部分结果伪装成成功。

有效进展只来自 Workspace/Artifact 变化、Todo 状态推进、成功的 Build/Test 验证、Blocker 移除、
Interaction 输入或 Child Result；Message 数与失败 Tool Call 数不算进展。最近 32 条安全摘要组成
有界 Ledger。通用无进展窗口在首次权威有效进展后才开始计数；初始只读侦察仍受完全重复 Decision、
A-B Loop、失败簇和硬预算约束，不会被误当成已停滞的交付。恢复时从权威 ToolCall、Plan、Child Run、
Interaction 与 Usage 重建控制状态；旧
Checkpoint 无需 Schema 升级。精确剩余模型、工具、迭代、时间和 Token 预算只写入安全 Trace，不再逐轮
改变模型请求；模型仅在失败恢复或 50%、25%、10% 阈值首次跨越时收到控制指导。动态指导作为新的
Agent-visible Session Message 追加，不替换或插入既有消息，因此正常请求可复用稳定 Prompt 与完整历史前缀。

## Safe Tool argument repair

Input-schema rejection remains before Policy and Approval. Runtime returns the model a bounded repair hint derived
only from schema paths and known validation keywords; rejected values and arbitrary validator messages are never
included. Other `IllegalArgumentException` and `SecurityException` failures keep the generic rejection summary.

## Policy / Approval 原子边界

Approval Request、Approval Metadata、Checkpoint、Run `WAITING_APPROVAL` 与 `policy.decision.made` / `approval.requested` Event-Outbox 在同一 Runtime UoW 中提交。响应侧把可信 Caller、验证结果、Authorization Evidence、响应消息和安全事件放入同一 UoW，再在提交后恢复 Run；Tool Resolution 只应用一次。

## Interaction、Steer 与 Client Event（Task 01～03）

内存 Runtime 在既有 `InteractionPort` 上维护 `PENDING -> RESPONDED -> APPLIED` 以及
`PENDING -> EXPIRED/CANCELLED/INVALIDATED` 的单一生命周期；同一 Run 同时最多一个阻塞式
Pending Interaction。新的 revision-aware Response 返回稳定收据，按可信 caller scope、
request 和幂等键去重；Approval 继续复用 Policy API 的 Authority/Target/Evidence 验证链。

`RunInputPort` 独立保存 Steer 的 `ACCEPTED/APPLIED` 状态。AgentLoop 只在
`BEFORE_ITERATION` safe point 将已接受输入追加为 Session 用户消息，并绑定 Attempt/Iteration，
不会异步修改正在构造的模型请求或 Tool 参数。内存与 SQLite 均实现该 Port；SQLite 使用条件更新、
canonical digest 和 Attempt/Iteration 外键实现重启后的 exactly-once state application。

`RuntimeEventFeed` 从权威 Journal 按排他 sequence 和固定 head 范围读取；`RuntimeClientEventProjector`
只输出 P0 typed 白名单，未知内部事件只推进 Cursor。`RuntimeEventSubscriptions` 先注册 Run-scoped
wake-up 再 drain 持久 Journal；当前单进程 Runtime 由提交后 wake-up 驱动，健康空闲订阅不轮询
Store。Listener 异常与 AgentLoop 隔离，并从未确认的持久 Cursor 延迟重试，不会静默永久关闭订阅。
`OpaqueRunEventCursorCodec` 为 Task 03 Adapter 提供带 HMAC 完整性校验的不透明 Cursor。

`RuntimeEventAppender` 同时提供 earliest/head 和受控 retention。模型 Delta 不再进入该 Journal：
`outputEvents` 读取当前进程中活动 Run 的有界内存缓冲，`subscribeOutput` 提供按 Run 隔离且可关闭的
replay-then-tail 订阅。Task 03 的 HTTP/SSE 参考 Adapter 位于 Integration 层，只通过 Runtime API
访问本模块。

`SessionMessageSource` 把有效 `ConversationSummary` 作为不可变 Context Window Checkpoint；普通消息只从
`coveredThrough` 之后追加，只有输入 Token 预算达到阈值、强制重建或手动压缩才生成下一代 Summary。
Tail 按 Token 预算从后向前选择，固定消息组数只作为安全上限，Tool Call/Result 原子组不会被拆开。
`compact(sessionId)` 是产品手动压缩复用的唯一入口，并与自动切换共用 Policy/version、CAS、Redaction
校验和原始 Message 保留语义。Context Trace 只记录窗口摘要、代次、触发原因和 Token 数，不记录正文。
Todo 与 governed Memory 等可变快照位于 append-only Session 前缀之后，其安全 provenance digest 参与
`windowGeneration` identity；变化表现为显式窗口边界，而不是静默改写未标识的前置内容。Tree/活动路径
延期期间不得把该入口解释为分支感知压缩。

Resume、Steer 和 Runtime Command 的 expected Run version 由 Runtime 校验；Resume/Command 的
校验位于 UoW 执行路径，实际状态写入仍服从 Store 的 optimistic locking。Transport 的 `If-Match`
不会成为第二份版本事实。

## Public Policy integration

Tool Pipeline 的权威策略结果是 `policy-api` 的 `PolicyDecision`。`ASK` 会创建关联 Decision、
Requester、Challenge 与精确 Target 的既有 Runtime Interaction；可信 Caller 作为 Responder，
经 `ApprovalVerificationService` 验证后只生成 challenge-satisfaction evidence。新 Attempt 恢复时
重新检查 Capability、Schema、Policy 与 Tool Binding，然后才进入原 Journal、Credential 与 Provider
链路。Runtime Core 只依赖 Policy API。

`ToolPolicy`、`ToolPolicyDecision` 与 `DefaultToolPolicy` 是待删除的单向源码兼容层；Pipeline
不会并行执行旧、新两套判断。新产品装配应使用 `publicToolPolicy(...)`。

## Provider continuation

When an assistant response contains both reasoning and Tool Calls, Runtime atomically associates a safe
continuation reference with the assistant Tool Call message and stores the reasoning through the configured
protector. `AES_GCM` provides confidentiality; explicit `NONE` is readable at rest and is intended only for trusted
local profiles.
The next model request resolves it only after provider, model, configuration digest, message, and tool correlation
validation. Checkpoints contain refs/digests/versions only and validate payload integrity before resume.

## Model stream

`FrozenModelInvoker` 消费 Provider-neutral `ModelStreamEvent`。Assistant content delta 只发送到
`RuntimeModelOutputPublisher` 的进程内通道，不调用 `RuntimeEventAppender`，也不进入 SQLite、Outbox、
Checkpoint 或 JSONL。通道按 Run 维护有界缓冲和 source-local cursor；订阅可关闭，Listener 失败不影响
AgentLoop，Run 终态后清理。有效模型决策仍由 `DecisionExecutor` 按 Final、Continue 或 Tool Call 的既有
领域语义写入 `session_message`；完整正文不复制到 `runtime_event`。Provider 要求 Tool reasoning 连续性时，
只有冻结 profile 显式声明后 adapter 才把 Tool Call reasoning 交给受保护 continuation。

Runtime `start` 的幂等绑定持久化 canonical request digest。相同 caller/key 只有在 Definition、Profile、
Session、Project、objective、input 和 overrides 完全一致时才返回原 Run；不同请求或缺少 digest 的旧
start 记录以 `IDEMPOTENCY_CONFLICT` fail closed。产品 Dispatcher 可据此安全恢复“Run 已启动但产品
binding 尚未提交”的 Saga 窗口。

Runtime 配置快照还可冻结 provider-neutral `modelRequestOptions`。该结构会递归复制并规范化 Map/List，
参与配置内容摘要，并由 `FrozenModelInvoker` 原样传给 `AgentChatRequest`；Run 启动后外部可变对象或后续
产品配置变化都不能改变该 Run 的结构化输出等模型调用语义。

纯 Java 的 Agent 执行内核，负责 Bootstrap、`AgentRunExecutionAttempt`、AgentLoop、工具管线、完成门禁、检查点、恢复、控制命令以及线程安全的内存存储实现。

- 依赖方向：`runtime-core -> context/model-api/runtime-api/tool-api/skill-api/credential-api -> core -> common`；Runtime 不依赖 Tool Core、Skill Core 或 Provider Integration。

## 错误分类与内部诊断

Model、Tool、预算和完成门禁在拥有语义的边界映射到稳定 `AgentErrorCode`。分类后的
`AgentError` 在 Step、Attempt、Run、Runtime Event 和 Trace 复用同一个 `diagnosticId`；
`RUNTIME_EXECUTION_FAILED` 只处理无法更精确分类的软件故障。可选 `FailureDiagnosticSink`
接收所有终止 Attempt 的原始 Throwable 与已分类安全上下文，由实现负责有界脱敏存储；Trace 或诊断 Sink 失败属于观测投影失败，不会改变已经确定的
Run/Attempt 事实。具有副作用且结果不确定的 Tool 仍映射为 `TOOL_OUTCOME_UNKNOWN` 并禁止盲目重放。
- Runtime 只调用 Core `AgentRun` 的受控行为，不复制生命周期合法性表。
- `start` 在 Run 持久化并提交执行后返回 `PENDING/QUEUED` 快照；等待完成由 `AgentRunHandle` 显式提供。
- 本地执行调度器按 Run 跟踪活动任务；取消 `RUNNING/SUSPENDING` Run 时会同时写入控制信号并尽力中断阻塞中的执行线程。模型边界把由该信号触发的中断收敛为 `CANCELLED`，不会误记为模型失败或 Run 失败。
- 每次 Start、Resume 或崩溃恢复都创建新的 `AgentRunExecutionAttempt`；它记录 Worker、Heartbeat、错误和恢复 Checkpoint，同一逻辑 Run 同时最多一个活动 Attempt。`ExecutionOwnershipPort` 为未来分布式 Lease 保留真实校验边界。
- AgentLoop 固定执行控制检查、状态协调、预算/循环 Guard、Context IR 构建、冻结模型调用、响应归一化、Decision 校验/执行、持久化和 Checkpoint；全部 Middleware 阶段及失败策略显式可测。模型、工具、交互、委派、Trace 和持久化均通过最小 Port 注入。
- Runtime 只接受带 `adapterType + adapterVersion` 的 `AgentChatModel` 注册。`FrozenModelInvoker` 按 Run 快照精确绑定 Adapter；缺失版本时确定性失败，不回退到当前版本，也不重新读取模型目录。
- `ModelMessageAssembler` 是 `AgentContext(PromptComponent/ContextItem)` 到供应商无关 `ModelMessage` 的唯一转换边界；Middleware 产生结构化 Context IR，不拼接共享 Prompt 字符串。跨 Run 的 Session 历史按每条消息所属 Run 解析权威 ToolCall，批准或拒绝工具后的下一轮仍可重建完整 Provider Tool 协议；若终态 Run 只留下 Assistant Tool Call 而没有全部对应 Tool Result，后续 Run 会从模型上下文中丢弃整个未完成协议组，避免发送供应商拒绝的孤立 Tool Call。
- 大型 Tool Result 先归一化为有界内联事实，再尽力写入外部 Asset；Asset 写入失败不会覆盖已知 Tool Outcome，也不会阻断下一轮模型诊断。只有权威内联结果本身无法持久化时才以 `TOOL_RESULT_PERSISTENCE_FAILED` 终止。
- Run 配置按 alias 冻结精确 `FrozenSkillBinding`、Catalog digest 和 Resolution Policy reference；普通未启用 Skill 的 Profile 冻结空集合。
- 模型初始上下文只披露冻结 Skill 的有界元数据。`skill.load` 与 `skill.resource.read` 作为普通 Tool 经统一冻结、Policy、Schema、Journal 和调用管线执行；激活后的指令进入最弱 `PromptLayer.SKILL`，资源只可从当前 Run 已冻结、已激活且索引为可读文本的包中按需读取。未允许、未激活、未索引或非文本资源会返回结构化 Tool 失败供模型修正请求；调用者越权、内容摘要漂移等完整性故障仍 fail closed。
- Skill 激活是 Run-scope、幂等且可检查点的状态。Checkpoint 保存精确 coordinate、registration digest 与激活时间；Resume 重新校验调用者和冻结内容摘要，缺失或漂移时 fail closed。
- `ToolCall` 是工具调用的权威记录。`ToolCallPart`/`ToolResultPart` 只保存领域 `ToolCallId`、Provider correlation 等协议引用和有界摘要；组装下一轮模型请求时，从权威 `ToolCall.result()` 重建已归一化的 `structuredData` 与 `truncated`，Runtime idempotency key 不发送给模型。
- Session Context 的 Token 估算同样从权威 `ToolCall` 读取完整 arguments 与 structured result；Tool 执行和持久化 Trace 记录实际 AgentLoop iteration，不使用占位值。
- Provider 在 Tool dispatch 后抛出异常时，Runtime 会先把权威 `ToolCall` 和 Step 收敛为失败并追加
  使用同一 Provider correlation 的安全 `ToolResultPart`，再终止当前 Run。后续 Run 因此仍能组装
  完整的 Assistant Tool Call / Tool Result 协议；`OUTCOME_UNKNOWN` 只用于告知状态，不允许自动重放。
- 本阶段只允许 Asset 的派生文本、OCR、Transcript 进入 Context；原始 Asset Part 会被拒绝。
- ToolCall 默认顺序执行，并通过 Run 的 `FrozenToolBinding` 完成 alias、精确 SemVer、Schema identity、Capability、Policy、Approval、执行环境、结果归一化、Journal 和持久化；不从全局可变规格表重新解析。
- Tool 审批是可恢复协议：Policy 产生 typed Interaction 与 interaction Checkpoint，Attempt 进入 paused 并释放 Worker；批准或拒绝后新 Attempt 先恢复并校验 Checkpoint，再幂等应用响应。批准继续原 ToolCall 且不重复模型调用，拒绝向模型写入有界结果而不默认取消整个 Run。同一模型响应包含多个待处理 ToolCall 时，恢复始终按持久化 Step sequence 顺序推进；任一调用失败会把同批次尚未启动的兄弟 ToolCall 和 Step 收敛为 `CANCELLED`，不残留 `REQUESTED`。
- 产品可通过 `ToolApprovalPromptFormatter` 定制审批展示内容；审批安全目标仍由 Runtime 冻结的 run、toolCall、definition hash、完整 arguments digest 和 principal scope 绑定，展示文案不参与授权判断。
- Runtime 对公共 `InteractionView.safePrompt` 执行 2048 字符的防御性有界投影；这使升级前已经持久化的超长 Interaction 仍可查询和响应，而不会改变内部审批目标或授权摘要。
- Resume 会重新校验当前调用者授权，并通过 `ToolInvoker.validateBinding` 确认冻结 provider/definition 仍可用；缺失或 hash/provider 漂移时 fail closed，不自动换 Provider。
- Tool Journal 区分 intent、dispatched、acknowledged、pending-result、completed、failed 与 outcome-unknown；非幂等或未知副作用在 dispatch 后失联不会自动重放。
- 模型调用与工具调用使用独立 Retry Policy；仅非副作用 Tool 允许有界自动重试，副作用 Tool 失败后进入不确定性处置而不自动重放。
- Completion Guard 校验输出契约、Artifact、Todo、Pending Tool/Child/Interaction、Policy 和 Budget，并强制 `RUNNING -> COMPLETING -> COMPLETED`。
- Runtime 硬预算在模型、工具或迭代执行期间耗尽时，Run、Attempt 与相关失败 Step 使用稳定的
  `RUN_BUDGET_EXCEEDED` 安全错误，不再降级为通用 `RUNTIME_EXECUTION_FAILED`。
- `RunTransitionCoordinator` 在 Unit of Work 内提交 Run、Runtime Event 和 Outbox；线程安全内存实现提供乐观锁、Run 内事件序号、稳定命令幂等结果、Outbox 发布/消费幂等和单活动 Attempt 约束。Listener 在提交后通知，异常不影响已提交状态。
- `RuntimePersistencePorts` 显式组合 Session、Run、Attempt、Checkpoint、Runtime State、Event、Outbox、
  Idempotency、Unit of Work、Tool Journal、Interaction、Run Input、Summary、Tool Result Asset 与消息脱敏监听注册边界；
  `RuntimeCoreBuilder` 只接受该组合并提供默认内存组合，不依赖 SQLite、JDBC、Jackson 或 JSONL。
- Application 可通过 `RuntimeCoreBuilder.persistence(...)`、`workerId(...)` 与
  `persistenceRetry(...)` 注入完整适配器装配。Runtime 的持久化重试每次重新加载聚合并重新执行事务；
  具体 Application 只能把“事务工作开始前未取得数据库写锁”这类安全失败列入有限重试，不能对未知提交
  结果或已经变更的内存聚合盲目重放。
- `OutboxMessage` 保存与对应 `RuntimeEvent` 相同的 Run 内 `sequence` 和稳定 `schemaVersion`。本地
  `ExecutionOwnershipPort` 以当前进程实例 ID 精确匹配 Attempt `workerId`，进程重启后的旧 Attempt
  不再被误判为仍由本地持有。
- Runtime 使用可信 Run 身份检索 RUN/SESSION/USER Scope 的 ACTIVE Memory；授权和状态过滤先于排序，结果仍通过 `ContextItem` IR 和统一 Token 预算。Checkpoint 只保存 Memory ID/Version、Scope、策略版本和查询摘要，Resume 会重新授权且不会恢复已失效或清除的正文。
- Checkpoint 创建通过 SLF4J 输出 `checkpoint.snapshot` 与 `checkpoint.capture` 结构化耗时日志，分别覆盖状态读取/组装/Hash，以及 latest 查询、Snapshot、持久化和 Event 发布阶段。日志只包含 Run/Checkpoint 标识、计数和毫秒耗时，不输出正文、Payload 或凭据。
- 模块不依赖 Spring、模型 Provider SDK、MCP、Docker、JPA、产品模块或管理端。

## Trusted Skill script policy

`TrustedSkillScriptPublicToolPolicy` runs before the ordinary `ALWAYS + ASK` branch. It produces an audited
`ALLOW` with reason `TRUSTED_SKILL_SCRIPT_AUTO_APPROVED` only when the current Run configuration contains one
unambiguous, active package/script grant pair and every frozen Skill, script, Tool, argument-policy,
runtime/profile/sandbox, capability, network, and caller-scope fact matches exactly. It never trusts model
arguments as provenance, never applies to generic `execution.run`, and never fabricates an Approval response.
Missing or drifted evidence delegates to the existing approval policy.
