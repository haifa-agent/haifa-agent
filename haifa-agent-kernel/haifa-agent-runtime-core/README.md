# Haifa Agent Runtime Core

## Human interaction lifetime

Runtime 生成的 Clarification 与 Tool Approval 默认不自动过期，并在 Interaction Store 中持久保持
Pending，直到响应或显式取消。Approval 响应仍重新校验精确 Tool Target 与操作者权限；通过后只签发
从批准时刻开始计算的短期授权证据，因此无限人工等待不会扩大执行权限。
Run 会持久化累计人工等待和当前等待起点；AgentLoop、模型重试窗口与恢复预算使用排除人工等待后的
活动执行时间。进程重启后继续等待或批准恢复，都不会把人工等待计入 `maxWallTimeMillis`。

## Tool outcome convergence

Journal retains dispatch evidence and a bounded pending result until the authoritative ToolCall result is saved.
COMPLETED is only a marker and clears the pending payload. Recovery reads ToolCall first; missing ToolCall/Step/message
facts can be repaired from an already saved result without dispatching a tool. Unknown outcomes terminate with
TOOL_OUTCOME_UNKNOWN. Runtime does not invoke a reconciliation provider or schedule a retry for them.

## Intentional continuation and interrupted execution

Checkpoint stores only Run ID, next iteration and forced-context-rebuild count at intentional pause/interaction
boundaries. Tool, Summary, Memory, Skill, model continuation, configuration and external capability state are no longer
copied. These facts remain in their authoritative stores and are read through current access and integrity boundaries.
Historical checkpoint selection, per-iteration capture and generic capability snapshot/restore participants are removed.

Before a model call, Runtime accounts for required middleware context in the Session budget. If the assembled local
window still does not fit, it first removes optional Memory and then performs the one allowed forced rebuild; only a
second local overflow terminates as context-too-long.
Runtime payload is 6.0; SQLite checkpoint codec is 2. Rebuild development databases after the cutover.

recover(runId) settles abandoned executing Runs as FAILED with RUNTIME_EXECUTION_INTERRUPTED (or TOOL_OUTCOME_UNKNOWN)
and marks the Attempt ABANDONED. It schedules no replacement. Currently owned execution is rejected. The next user turn
can use saved conversation facts and observe current conditions. Intentional pauses and approvals continue through
resume/respond across restart, retaining caller, frozen binding, exact target and budget checks. Missing or non-latest
pause state fails closed; normal continuation cannot select an earlier budget.

## Model-call client events

`FrozenModelInvoker` records each physical model attempt as durable `model.attempt.scheduled` and
`model.call.started` plus one terminal `model.call.succeeded` or `model.call.failed` event. Retry waiting and final
exhaustion add `model.attempt.retry-scheduled` / `model.attempt.exhausted`. `RuntimeClientEventProjector` exposes only
bounded provider-neutral `ModelLifecycle` and `ModelAttemptLifecycle` fields; model text, reasoning, Prompt and raw
provider payloads remain outside the durable client feed.

An HTTP-success response with no content, Tool Call, or structured output is normalized as retryable
`EMPTY_RESPONSE/empty_response`. Runtime defaults to the initial physical attempt plus at most three retries after 1,
3, and 6 seconds for this category. Other retryable model failures retain the default two-physical-attempt limit and
bounded exponential backoff with jitter. Runtime keeps one logical request identity and frozen binding, honors a
bounded `Retry-After` for non-empty failures, counts every physical call against the Run budget, and checks
cancellation/deadline throughout backoff. Authentication, invalid request, context-too-long and partial-output failures
are never replayed by the generic retry policy. Each empty occurrence adds only a safe `model.empty-response` event;
exhaustion terminates through the existing stable model failure path.

## 结构化完成纠偏

`CompletionPolicy` 返回 Provider-neutral 的 `CompletionPolicyResult`：包括稳定
`CompletionBlocker(code, safeMessage, recoverable, evidenceRequirement)` 与安全 Evidence Code。
Runtime Core 不依赖 Coding 产品类型，也不读取 Coding 表。Final 缺少证据时，AgentLoop 追加
`completion.deferred` 安全事件和固定顺序、Agent-visible 且用户不可见的纠偏 Session Message；次数只由 `CompletionRepairPolicy` 限制，
默认产品装配最多两次。纠偏计数保存在权威 Session Message metadata，正常暂停／交互继续时重建，
耗尽后以 `COMPLETION_REPAIR_EXHAUSTED` 失败，不能伪装为成功。

Client Event 投影只把结构化字段映射为 `DeliveryLifecycle`，用于中性的 Completion 延迟和
Budget Threshold 展示；Prompt、Host Path、stderr、Fingerprint 和 Tool 原始参数不进入公共投影。

测试源码中的 `RuntimeControlTraceReplay` 只验证当前 Completion、预算、交互及执行事件的安全控制字段；不包含旧进展/策略事件的兼容读取。

## 模型负责进展判断，Runtime 执行硬边界

Runtime 不再维护 Progress Ledger、失败指纹/失败簇、重复或 A-B 决策的停滞裁决，也不会在第三次普通
Tool 失败时自动终止或要求模型换策略。主模型从工具结果决定继续、换方法、请求帮助或完成；产品提示词
提供工作指导，Runtime 不增加额外的进展评审模型调用。不同调用身份的相同工具参数允许再次执行；
同一批次重复幂等键仍拒绝。

取消、ownership、冻结次数/时间/Token 限制、Tool Journal/unknown、当前 Policy/Approval、输入协议校验、
技术重试、Completion Repair、Context 压缩保持原边界。工具已确认的 unknown/cancelled 事实在执行边界
终止并取消未派发的同批工具，保留关联 Tool Result；unknown 的安全部分结果不会被伪装成成功。
普通工具失败不再生成 REPEATED_TOOL_FAILURE，资源耗尽继续使用明确资源原因及既有 partial/failed 规则。

Checkpoint 删除 decisionFingerprints 字段，当前最小载荷版本为 6.0，不提供旧策略数据的兼容或迁移，
恢复不重建策略计数。旧进展/策略事件投影、错误码及 Harness 读取均已删除。
50%、25%、10% 预算阈值仍按现有机制追加安全提示，恢复后不重复已跨越的阈值。Completion
纠偏次数不属于 `RunBudgetSnapshot`，工具参数／安全协议拒绝不消耗此次数，仍由输入校验和既有硬预算约束。

Runtime 不再为 Workspace 修改强制创建基线 Checkpoint，也不恢复外部工作区。工具执行继续通过
当前 Workspace 访问与精确授权边界；产品 Snapshot/Artifact 能力保留，不经通用 Runtime Participant 装配。

## Safe Tool argument repair

Input-schema rejection remains before Policy and Approval. Runtime returns the model a bounded repair hint derived
only from schema paths and known validation keywords; rejected values and arbitrary validator messages are never
included. Other `IllegalArgumentException` and `SecurityException` failures keep the generic rejection summary.

## Policy / Approval 原子边界

Approval Interaction request、exact target、Checkpoint、Run `WAITING_APPROVAL` 与
`policy.decision.made` / `approval.requested` Event-Outbox 在同一 Runtime UoW 中提交。`PolicyDecision`
只在当前求值中瞬态产生，不进入 Store。响应侧把可信 Caller、Authority/Target 验证结果、Interaction
response/application 和安全事件放入同一 UoW，再在提交后恢复 Run；Tool Resolution 只应用一次。

## Interaction、Steer 与 Client Event（Task 01～03）

内存 Runtime 在既有 `InteractionPort` 上维护 `PENDING -> RESPONDED -> APPLIED` 以及
`PENDING -> EXPIRED/CANCELLED/INVALIDATED` 的单一生命周期；同一 Run 同时最多一个阻塞式
Pending Interaction。新的 revision-aware Response 返回稳定收据，按可信 caller scope、
request 和幂等键去重；Approval 继续复用 Policy API 的 Authority/Target verification，不产生
Decision bearer、Authorization Evidence 或可复用 Grant。

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
`coveredThrough` 之后追加。语义压缩默认启用（`CompressionPolicy.defaults().semanticCompactionEnabled()`），
`SemanticCompactionCoordinator` 是自动生成下一代 Summary 的唯一写入者：它可在一次逻辑压缩中连续 Fold
多个有界批次，全部验证成功后只做一次 CAS；任一批次失败不提交中间状态。显式
`withSemanticCompactionEnabled(false)` 关闭后，输入 Token 阈值与强制重建回退到确定性压缩；
手动入口始终显式可用。
Tail 按 Token 预算从后向前选择，固定消息组数只作为安全上限，Tool Call/Result 原子组不会被拆开。
`compact(sessionId)` 是产品手动压缩复用的唯一入口，并与自动切换共用 Policy/version、CAS、Redaction
校验和原始 Message 保留语义。Context Trace 只记录窗口摘要、代次、触发原因和 Token 数，不记录正文。
本次默认启用把 Policy 窗口版本从 `session-window-v2` 提升到 `session-window-v3`；Checkpoint 兼容性要求
Policy 版本精确匹配，因此旧版本摘要不再被复用为 Checkpoint，并在下一次压缩中确定性重建，
源消息始终是权威事实。
Todo 与 governed Memory 等可变快照位于 append-only Session 前缀之后，其安全 provenance digest 参与
`windowGeneration` identity；变化表现为显式窗口边界，而不是静默改写未标识的前置内容。Tree/活动路径
延期期间不得把该入口解释为分支感知压缩。

Resume、Steer 和 Runtime Command 的 expected Run version 由 Runtime 校验；Resume/Command 的
校验位于 UoW 执行路径，实际状态写入仍服从 Store 的 optimistic locking。Transport 的 `If-Match`
不会成为第二份版本事实。

## Public Policy integration

Tool Pipeline 的权威策略结果是 `policy-api` 的瞬态 `PolicyDecision`。`ASK` 把 Requester、Challenge、
无秘密的 requirement equivalence 与精确 Tool target 写入既有 Runtime Interaction；可信 Caller 作为
Responder，经 `ApprovalVerificationService` 验证后应用该 Interaction。新 Attempt 恢复时重新计算并比较
当前 Policy/target，再检查 Capability、Schema 与 Tool Binding，然后才进入原 Journal、Credential 与
Provider 链路。Runtime Core 只依赖 Policy API，不持久化 Decision、Evidence 或 Grant。

产品可通过 `toolRequestCanonicalizer(...)` 在 ToolCall 首次持久化前生成唯一的 canonical request；
该请求随后统一用于 Schema、Policy resource digest、Approval target、Journal 记录与 Provider invocation。
规范化器只能修改 arguments，必须确定且幂等；默认实现保持原请求不变。

Tool Pipeline 只接受 `PublicToolPolicy` 产生的瞬态 `PolicyDecision`；产品装配通过
`publicToolPolicy(...)` 或显式 `PolicyRuleSet`／`PolicyDecisionService` 提供授权判定。

## Memory default assembly

Runtime 只在未配置 `MemoryRetriever` 时创建默认的内存 Store、Policy 和 Retriever；配置自定义
Retriever 时不会创建这些默认对象。`MemoryAuditSink` 属于 Memory Service 自身的写入审计边界，
不是 Runtime Builder 的装配输入。配置 `MemoryService` 时，消息 redaction 仍会使其来源的 Memory
失效。

## Provider continuation

When an assistant response contains both reasoning and Tool Calls, Runtime atomically associates a safe
continuation reference with the assistant Tool Call message and stores the reasoning through the configured
protector. `AES_GCM` provides confidentiality; explicit `NONE` is readable at rest and is intended only for trusted
local profiles.
The next model request resolves it only after provider, model, configuration digest, message, and tool correlation
validation. Checkpoints no longer duplicate continuation references; the continuation store remains authoritative.

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
- 每次 Start 或正常 Resume 创建新的 `AgentRunExecutionAttempt`；它记录 Worker、Heartbeat、错误和恢复 Checkpoint，同一逻辑 Run 同时最多一个活动 Attempt。异常中断只将旧 Attempt 标记 ABANDONED；`ExecutionOwnershipPort` 校验当前执行所有权，防止误收敛仍在执行的 Run。
- AgentLoop 固定执行控制检查、状态协调、预算/迭代 Guard、Context IR 构建、冻结模型调用、响应归一化、Decision 校验/执行、持久化和 Checkpoint；全部 Middleware 阶段及失败策略显式可测。模型、工具、交互、委派、Trace 和持久化均通过最小 Port 注入。
- Runtime 只接受带 `adapterType + adapterVersion` 的 `AgentChatModel` 注册。`FrozenModelInvoker` 按 Run 快照精确绑定 Adapter；缺失版本时确定性失败，不回退到当前版本，也不重新读取模型目录。
- `ModelMessageAssembler` 是 `AgentContext(PromptComponent/ContextItem)` 到供应商无关 `ModelMessage` 的唯一转换边界；Middleware 产生结构化 Context IR，不拼接共享 Prompt 字符串。跨 Run 的 Session 历史按每条消息所属 Run 解析权威 ToolCall，批准或拒绝工具后的下一轮仍可重建完整 Provider Tool 协议。跨模型历史继续投影为结构化 Tool Call/Result，同时剥离旧 continuation，并仅在模型请求内确定性重映射 Provider correlation；持久化事实不变。`SessionMessageSource` 正常丢弃未闭合历史组，Assembler 对绕过该筛选的跨模型历史防御性补充“结果未记录”的结构化 Tool Result；当前模型组不完整仍 fail closed。
- 大型 Tool Result 先归一化为有界内联事实，再尽力写入外部 Asset；Asset 写入失败不会覆盖已知 Tool Outcome，也不会阻断下一轮模型诊断。只有权威内联结果本身无法持久化时才以 `TOOL_RESULT_PERSISTENCE_FAILED` 终止。
- Run 配置按 alias 冻结精确 `FrozenSkillBinding`、Catalog digest 和 Resolution Policy reference；普通未启用 Skill 的 Profile 冻结空集合。
- 模型初始上下文只披露冻结 Skill 的有界元数据。`skill_load` 与 `skill_resource_read` 作为普通 Tool 经统一冻结、Policy、Schema、Journal 和调用管线执行；激活后的指令进入最弱 `PromptLayer.SKILL`，资源只可从当前 Run 已冻结、已激活且索引为可读文本的包中按需读取。未允许、未激活、未索引或非文本资源会返回结构化 Tool 失败供模型修正请求；调用者越权、内容摘要漂移等完整性故障仍 fail closed。
- Skill 激活是 Run-scope 的幂等事实，保存在 Skill 状态仓。继续执行时检查冻结 Binding 与内容访问，不复制到 Checkpoint。
- `ToolCall` 是工具调用的权威记录。`ToolCallPart`/`ToolResultPart` 只保存领域 `ToolCallId`、Provider correlation 等协议引用和有界摘要；组装下一轮模型请求时，从权威 `ToolCall.result()` 重建已归一化的 `structuredData` 与 `truncated`，Runtime idempotency key 不发送给模型。
- Session Context 的 Token 估算同样从权威 `ToolCall` 读取完整 arguments 与 structured result；Tool 执行和持久化 Trace 记录实际 AgentLoop iteration，不使用占位值。
- Provider 在 Tool dispatch 后抛出异常时，Runtime 会先把权威 `ToolCall` 和 Step 收敛为失败并追加
  使用同一 Provider correlation 的安全 `ToolResultPart`，再终止当前 Run。后续 Run 因此仍能组装
  完整的 Assistant Tool Call / Tool Result 协议；`OUTCOME_UNKNOWN` 只用于告知状态，不允许自动重放。
- 本阶段只允许 Asset 的派生文本、OCR、Transcript 进入 Context；原始 Asset Part 会被拒绝。
- ToolCall 默认顺序执行，并通过 Run 的 `FrozenToolBinding` 完成 alias、精确 SemVer、Schema identity、Capability、Policy、Approval、执行环境、结果归一化、Journal 和持久化；不从全局可变规格表重新解析。
- Tool 审批是可恢复协议：Policy 产生 typed Interaction 与 interaction Checkpoint，Attempt 进入 paused 并释放 Worker；批准或拒绝后新 Attempt 幂等应用精确响应并校验最小暂停记录。批准继续原 ToolCall 且不重复模型调用，拒绝向模型写入有界结果而不默认取消整个 Run。同一模型响应包含多个待处理 ToolCall 时，恢复始终按持久化 Step sequence 顺序推进；任一调用失败会把同批次尚未启动的兄弟 ToolCall 和 Step 收敛为 `CANCELLED`，不残留 `REQUESTED`。
- 产品可通过 `ToolApprovalPromptFormatter` 定制审批展示内容；审批安全目标仍由 Runtime 冻结的 run、toolCall、definition hash、完整 arguments digest 和 principal scope 绑定，展示文案不参与授权判断。
- Runtime 对公共 `InteractionView.safePrompt` 执行 2048 字符的防御性有界投影；这使升级前已经持久化的超长 Interaction 仍可查询和响应，而不会改变内部审批目标或授权摘要。
- Resume 会重新校验当前调用者授权，并通过 `ToolInvoker.validateBinding` 确认冻结 provider/definition 仍可用；缺失或 hash/provider 漂移时 fail closed，不自动换 Provider。
- Tool Journal 区分 intent、dispatched、acknowledged、pending-result、completed、failed 与 outcome-unknown；非幂等或未知副作用在 dispatch 后失联不会自动重放。
- 模型调用与工具调用使用独立 Retry Policy；仅非副作用 Tool 允许有界自动重试，副作用 Tool 失败后进入不确定性处置而不自动重放。
- Completion Guard 校验输出契约、产品 `CompletionPolicy`、Pending Tool/Child/Interaction、不确定工具执行和 Budget，并强制 `RUNNING -> COMPLETING -> COMPLETED`。计划 Todo 是模型的计划辅助，不构成通用完成门槛；产品需要的确定性验收在自己的 `CompletionPolicy` 内表达。
- `completion.deferred` 只表达“此次 Final 未满足完成要求”，`phase` 固定为中性 `COMPLETION`；Runtime 不按 blocker code 子串推断 `VERIFYING`／`RECOVERING` 等业务阶段。产品需要细分展示时，由产品自己的证据与投影决定。通用 repair 提示 `[COMPLETION_REPAIR]` 只携带 attempt、blocker code、evidence、missing 和剩余预算等事实以及产品 Policy 返回的有界 guidance，不写入任何产品交付策略。
- Runtime 在普通文本 Run 的模型、工具、子 Run、迭代或累计 Token/Cost 预算阻止继续工作时，不再把
  预期的资源停止伪装成软件故障：新的动作不会 dispatch，已完成结果和有界安全总结通过既有原子完成
  路径保存，Run 以 `COMPLETED + PARTIAL_SUCCESS` 收敛，并在 Result warning 与最终消息 metadata 中记录
  `BUDGET_LIMITED` 和具体资源。已经取得的最终模型回答即使使累计用量越过上限，也作为部分结果保留。
  要求结构化输出的 Run 继续 fail closed 为稳定 `RUN_BUDGET_EXCEEDED`，避免生成未经冻结 Schema 校验的
  伪结果；意外越界和无法受控收敛的内部路径同样保留该安全错误。
- `RunTransitionCoordinator` 在 Unit of Work 内提交 Run、Runtime Event 和 Outbox；线程安全内存实现提供乐观锁、Run 内事件序号、稳定命令幂等结果、Outbox 发布/消费幂等和单活动 Attempt 约束。Listener 在提交后通知，异常不影响已提交状态。
- `RuntimePersistencePorts` 显式组合 Session、Run、Attempt、Checkpoint、Runtime State、Event、Outbox、
  Idempotency、Unit of Work、Tool Journal、Interaction、Run Input、Summary、Tool Result Asset 与消息脱敏监听注册边界；
  `RuntimeCoreBuilder` 只接受该组合并提供默认内存组合，不依赖 SQLite、JDBC、Jackson 或 JSONL。
- Application 通过 `RuntimeCoreBuilder.persistence(...)` 与 `workerId(...)` 注入完整适配器装配。
  Runtime 不重放 Unit of Work；具体持久化适配器只能在用户事务工作开始前、尚未取得数据库写锁时做有限重试，
  对未知提交结果或已经变更的内存聚合必须 fail closed。
- `OutboxMessage` 保存与对应 `RuntimeEvent` 相同的 Run 内 `sequence` 和稳定 `schemaVersion`。本地
  `ExecutionOwnershipPort` 以当前进程实例 ID 精确匹配 Attempt `workerId`，进程重启后的旧 Attempt
  不再被误判为仍由本地持有。
- Runtime 使用可信 Run 身份检索 RUN/SESSION/USER Scope 的 ACTIVE Memory；授权和状态过滤先于排序，结果仍通过 `ContextItem` IR 和统一 Token 预算。同一执行内以最新 COMPLETED USER 消息为回合键缓存检索结果，并且每个回合只持久化一次 `RuntimeMemorySelection`；继续时重新检索授权且有效的 Memory。Memory selection 不再复制到 Checkpoint。
- Checkpoint 仅保存正常暂停／交互的最小续跑计数；SQLite 适配器保留有界持久化耗时指标。
- 模块不依赖 Spring、模型 Provider SDK、MCP、Docker、JPA、产品模块或管理端。

Completion 产品验收统一通过 `CompletionPolicy` 返回结构化阻塞与证据。Artifact 检查由产品的
`PublishedArtifactRequiredChecker` 实现该接口；Runtime 不再提供单独的 `RequiredArtifactChecker` 配置入口。

恢复来源直接读取持久 Attempt 的 `resumedFromCheckpointId`，不再经过进程内 Selector。已记录来源必须精确存在，
缺失、非最新的来源或缺失状态会拒绝继续；新 Run 无来源时从初始计数开始，不回退历史快照。
