# Haifa Coding Agent

## Product client API

`io.haifa.agent.application.project.product.coding.client.CodingSessionClient` 是 Coding Agent 的稳定产品
API；`LocalCodingSessionClient` 是当前进程内实现。Terminal、其他 UI 和测试只依赖该契约，不从
Terminal 模块反向取得产品能力，也不直接访问 Runtime Store。独立产品的具体 Runtime、模型、Tool、
Persistence 装配仍由最高层应用模块负责。`CodingSessionView.activeRun` 只表示当前活动 Run；需要等待或
核验终态的客户端必须保存 `runId`，并通过 `CodingSessionClient.findRun` 查询作用域内的权威 Snapshot。
同一产品 API 包中的 `CodingAgentClient`、`CodingAgentClientFactory` 和 `CodingAgentClientMetadata`
定义标准客户端与公开装配契约，不包含 Provider 分支；具体工厂实现继续位于最高层独立产品装配模块。

## Shared model profile readiness

Coding model preferences remain product-owned and this phase does not add `/thinking`, change the Session Store, or
change Terminal UI. A compile-time architecture test imports the public `ModelBindingProfile` and
`DefaultModelParameterResolver` contracts and verifies that Model API/Core have no Personal Assistant dependency.
Future Coding controls can therefore reuse the common validation and snapshot semantics without copying PA DTOs or
provider dialect logic.

## Prompt-first 自主交付

Coding Agent 的基础工作方法由产品拥有的版本化资源
`META-INF/haifa-agent/prompts/coding-agent-v1.txt` 提供。资源具有稳定版本和 SHA-256 身份，CLI
只负责装配，不再维护按评测 Case 累积的长 Prompt。基础 Prompt 保持通用；Tool 专属路径、
`operationFamily` 和验证/Diff 用法由对应 Tool Definition 描述；`task-planning` 与
`result-verification` 继续通过 Skill 渐进披露。

交付约束位于版本化基础 Prompt。精确剩余预算和完整交付状态留在权威控制面与 Trace，不再通过每轮
重建的尾部 `[CODING_RUN_STATE]` 改写模型请求。预算阈值、恢复策略和完成门禁纠偏只在状态转换时作为
Agent-visible、用户不可见的 Session 消息追加；旧请求因此保持为新请求的完整历史前缀。追加消息不包含
Case/Fixture 信息、宿主路径、原始 Tool 输出或模型自报的语义覆盖。

`CodingWorkProjectionService` 从既有 ToolCall、Plan、Interaction、Usage、ChangeSet 和交付证据确定性
重建 `ORIENT/PLAN/CHANGE/VERIFY/REVIEW/DELIVER/BLOCKED` 派生阶段；它不是新的 Core Run 状态或事实源。
投影只保留有界摘要、稳定错误簇和路径/版本/变更/产物的 SHA-256 引用；Middleware 仅在阶段、缺失证据
或交付预留变化时追加 Agent-visible 控制消息和安全 Client Event，保持模型请求历史前缀只追加不改写。
ANALYZE/REVIEW 不会被强制进入 CHANGE；仅修改工作区也
不会隐式产生 commit、push 或 PR 意图。旧 Checkpoint 不增加 Codec 或 Migration，Resume 直接从权威
记录重建投影。

`execution.run` 1.5 按可信有效操作族限制每通道输出：INSPECT 使用模型输出预算 1×、DIFF 4×，
TEST/BUILD/MUTATE/UNKNOWN 8×，同时受硬上限约束。Diff 结果提供观察到的文件/分块数、计数是否完整和
可选 Artifact Ref；截断后必须使用返回引用或更窄的分页命令，不能把观察计数当作完整 Diff。

## 自主交付模式与完成证据

Coding 产品只接受可信调用方元数据提供的 `CHANGE/CREATE/ANALYZE/REVIEW` 模式；没有可信模式时保持
`UNKNOWN`，不从普通用户文本的关键词推断意图。模型消息不能改变模式，也不能制造交付证据。

`CodingDeliveryEvidenceLedger` 只从权威 ToolCall、AgentStep、ChangeSet 和 Execution 状态
引用重建工作区修改、Diff、验证、只读检查、阻塞和有证据的 No-change 事实。模型自由文本不构成
修改或验证通过证据。`CodingCompletionPolicy` 对 CHANGE/CREATE 默认要求修改或受限
No-change、验证尝试和 Diff；ANALYZE/REVIEW 要求只读证据且拒绝意外修改。UNKNOWN 用于普通交互：
没有权威 Workspace 修改时允许文本回答正常结束，不触发完成修复；一旦观察到 Workspace 修改，
仍必须满足完整的修改、验证和 Diff 证据。需要硬性交付保证的调用方必须提供可信任务模式。

可信宿主还可在创建 Session 或提交新 Turn 时冻结 `WORKTREE_ONLY/LOCAL_COMMIT/REMOTE_PUSH/PULL_REQUEST`
交付意图；默认是 `WORKTREE_ONLY`，普通模型文本和“继续”不会升级它。Commit、Push、PR 仍通过唯一的
`execution.run` 调用系统 `git`/`gh`，但在 Broker Dispatch 前受产品交付事务门禁约束：先确认仓库根、
分支、Upstream、状态、HEAD、Diff 和验证，再精确路径 Stage、检查 staged diff、Commit 后重查 HEAD、精确分支
Push 后读取 remote ref，最后可创建并查看以 `dev` 为 Base 的 PR。权威 Tool 结果按顺序记录证据；旧
HEAD/remote ref 不能满足新动作后的确认，Outcome Unknown 必须先只读核验再决定是否重放。宽泛
`git add .`、意图越级、复合交付动作和 `gh pr merge` 在任何普通审批阈值下都不会 Dispatch。交付证据
同时绑定脱敏的 workspace-relative Repository Scope Digest；根仓、`docs/`、`test-config/` 等独立仓库
不能互相复用拓扑、Diff、验证或结果确认事实。

默认冻结交付预留为剩余 Model Call 20%、Tool Call 25%、Wall Time 20%。预留只作为控制面事实，
不增加 Runtime 的总预算或时限，也不逐轮进入模型 Prompt。缺少完成证据时 Runtime 最多执行两次结构化纠偏，恢复后
从持久消息重建次数，耗尽后以 `COMPLETION_REPAIR_EXHAUSTED` 稳定失败。

生产控制面不维护 Verification Plan/Dimension/Evidence，也不接受模型自报的验证标签。外部
Evaluation/Trace Replay 继续独立使用隐藏验收、Workspace 快照与 Scratch 清理事实，不与生产完成门禁
共享模型声明。

## Policy 持久化装配

`ProjectPersistenceAssembly.policy()` 是应用级 Policy 权威 Store 组合：内存模式共享同一 `InMemoryPolicyStore`，SQLite 模式复用 `SqliteStoreFoundation` 的 Snapshot、Decision、Evidence、Grant 与 Trust Store。Coding Agent 重启时复用内容一致的固定 Policy Snapshot；不在应用层实现企业组织或审批工作流。

## Policy assembly

`CodingAgentPolicyAssembly` 是产品装配边界：创建 Coding Agent 默认规则 Snapshot、内存
Decision/Approval evidence Store 和本地同主体 Verifier。它不包含组织、审批路由、待办或业务
状态机。`ProjectExecutionToolOperations` 只接受上游 Tool Pipeline 传入的真实
`policyDecisionRef`；缺少引用时 fail closed，Broker 复核同一 Decision 而不再次询问用户。

组合 Project Index、Context Source、既有 Runtime Tool Pipeline 与 Project-only 产品外观。普通产品请求只携带 ProjectId 和消息；默认 Workspace、Profile、Context Source 与 Tool disclosure 从可信版本化配置解析。

本模块承载 Project 产品的内建 Workspace 文件/Execution Tool，以及默认关闭的 Web
Search/Fetch Tool。Web 的 Provider-neutral Java 接口、Tool adapter、URL Policy 和具体 HTTP Provider
由公共 `haifa-agent-web` Integration 模块提供；本模块只负责 Coding Agent 的 Provider、Credential
和 Tool alias 装配。
本模块不建立第二套 Context、Tool Registry、Policy、Credential Broker 或 Session 聚合。

## 持久化装配

`ProjectPersistenceAssembly` 是产品层唯一持久化装配入口，只接受三种显式模式：

- `MEMORY`：默认模式，使用 Runtime 内存 Port；
- `SQLITE`：SQLite 是 Session、Run、Attempt、Checkpoint、Runtime State、Event/Outbox 与产品会话映射的唯一事实源；
- `SQLITE_WITH_JSONL`：在 `SQLITE` 基础上，把已提交 Outbox 的安全事件投影为可删除的 JSONL。

SQLite 模式要求数据库文件绝对路径，并显式选择 `NONE` 或 `AES_GCM` payload protection；后者还要求
`env://` 形式的稳定 continuation protector 引用。JSONL 模式还要求已存在、可写、非符号链接的受控
绝对目录。Application 在一次 checksum 校验中组合 Runtime Migration 与自己
拥有的 `V1000 project_product_session`、`V1001 coding_session_*`、
`V1002 coding_session_event_cursor` 与 `V1003 coding_session_management` Migration，不修改
Runtime Schema。每次进程启动生成新的 worker ID，
并把完整 `RuntimePersistencePorts`、worker ID 和仅针对安全 `SQLITE_BUSY/LOCKED` 获取失败的有界重试策略
注入 `RuntimeCoreBuilder`。

Core `AgentSession` 与 `ProjectProductSession` 使用同一个 `AgentSessionId`。产品映射显式保存
tenant、principal、project、workspace、配置 ID/版本/digest 和 product profile；每次读取都与 Core
Session 重新核对，漂移时 fail closed。JSONL projector 只在 Runtime 提交后触发；关闭时先停止上层新请求，
再冲刷投影，最后关闭 SQLite 连接。

Application 自有的 Product/Coding 表通过 MyBatis Mapper XML 接入
`SqliteRuntimeUnitOfWork`，与 Runtime/Policy 共用同一个 `BEGIN IMMEDIATE` 事务边界；应用层 Store
不直接使用 JDBC。Mapper 仍经过 SQLite Foundation 的静态 XML 校验，禁止 `${...}` 动态 SQL。

## Coding Session 产品闭环

`CodingSessionService` 是 Coding Agent 的产品 façade，提供 Session 创建、稳定分页/搜索、打开、
CAS 重命名、Core 权威归档、逻辑删除、手动线性历史 Compaction、新 Turn、活动 Run steer、持久
Follow-up、恢复编辑、已消费事件 Cursor 确认和取消活动 Run。`CodingSessionId` 与
`AgentSessionId` 一对一；Run 生命周期仍以 Runtime Snapshot 为权威，产品表只保存活动 Run/dispatch
引用、观察版本、稳定显示名、队列计数所需事实和 revision。

同一 Session 最多保留一个活动 Run 或待恢复 dispatch。新 Turn 与 Follow-up 在调用 Runtime `start`
前先持久化调用者作用域幂等事实及稳定 dispatch key；进程在 Runtime 提交前后退出时，显式
reconciliation 使用同一 key 收敛到同一 Run。SQLite 中尚未投递的消息与附件引用通过配置的 continuation
protector 编码并校验 digest，不进入 JSONL 或普通日志；`NONE` 明文可读且不提供保密性，`AES_GCM`
提供加密与完整性保护。`MEMORY` 与 `SQLITE` 通过同一
`CodingSessionStore` 端口提供相同行为。

`CodingSessionHistoryService` 是 Resume 使用的最小只读产品边界。它先通过 `CodingSessionService`
重新执行调用方与 Project 作用域校验，再从 Runtime `SessionMessageRepository` 读取有界消息窗口，
仅投影 `USER_VISIBLE` 的 User/Assistant 正文并执行凭据脱敏；无 Assistant 结果的失败 Run 只提供安全
状态摘要。默认最多扫描 2,000 条、返回最近 100 条，JSONL 不参与查询或恢复。

tui4j Terminal UI 与富 Tool/Execution/Resource 客户端事件已进入独立
`haifa-agent-coding-terminal` 模块，避免产品 façade 依赖终端实现。`CodingShellService` 与
`CodingSessionExportService` 只定义产品边界；CLI 生产装配分别复用既有
Policy/Approval/ExecutionBroker/Sandbox 和 Runtime Message Store。Session Tree/Fork/Clone、PTY、
后台 Job、模型登录和动态目录仍未实现。静态可信模型目录、Session 模型偏好及 SQLite 恢复已经
实现：偏好保存内部 Model ID 和独立 revision，只允许在无活动 Run/dispatch 时切换，下一新 Run
冻结对应快照；配置中已删除的模型要求重选，不静默回退。

`ProjectToolCatalog` 将 `file.list/stat/read/search/create/write/delete/move/diff/patch`、`execution.run` 与
`execution.request_permissions` 共 12 个能力注册到唯一 Tool Catalog。模型目录不再披露 `git.*` 或
`github.*` Tool；Git/GitHub 操作由
`execution.run` 直接调用系统 `git` / `gh`。每个定义均包含 Draft 2020-12 输入/输出 Schema、风险、
幂等性、副作用、资源和审批元数据；普通 Chat、无有效 capability 或模型不支持 Tool 时冻结集合为空。
Catalog 保留 `file.search` 供显式配置兼容，但 Coding CLI 默认不冻结该能力；大型仓库的文件发现和内容
搜索使用通用 `execution.run`，由模型根据冻结 Shell 与 `PATH` 选择 `rg`、`rg --files` 或平台适配的
替代命令。应用不增加搜索专用 Executor、不解析搜索意图，也不在 Java 中拼接命令选项。
普通手工源码更新优先使用 `file.patch` 1.1：它接受 Codex 风格的上下文 Patch，覆盖新增、删除、更新、
移动和多文件调用；本地实现流式转换大文件并通过同目录临时文件与提交前哈希复核完成原子替换。
`file.write` 保留给有意整体替换的小文件，生成代码和机械批量修改继续通过通用 CLI/生成器完成。
文件 Mutation 现在以 Runtime idempotency key 作为 operation ID，并把权威 Tool Call ID 写入 ChangeSet。
`file.create`/`file.write` 的只读 reconcile 同时核对 terminal ChangeSet、Tool Call 关联和目标内容摘要；任一
证据缺失或漂移都保持 outcome-unknown。`execution.run` 会在进程启动时记录 execution ID、PID 和工作目录
摘要；已得到终态进程结果可直接对账，timeout/cancel 只有在观察到本地 ChangeSet 时才收敛为“不重放的
已知失败”，否则保持 outcome-unknown。
`execution.run` 不再使用通用 `project-safe` 标识：产品装配必须提供冻结 `SandboxProfile`，
Catalog、Policy Resource、Execution Request 和 Broker 解析都使用同一精确 Profile Ref/version。
Provider、网络或受信配置变化会改变 Definition/Binding 的安全身份，旧 Decision/Approval 不能用于
新 Profile；模型可见 Schema 包含 command、逻辑 workdir、有界 timeout、安全描述和可选
`operationFamily`。操作族只允许 `BUILD/TEST/INSPECT/DIFF/MUTATE/UNKNOWN`，仅作为交付和诊断 Hint；
省略时使用 `UNKNOWN`。可信 `SystemGitCliCommandClassifier` 独立解析直接 `git`/`gh` 命令并产出风险事实；Coding
`ToolPolicyRequestAdapter` 在 Policy 决策前把本地读、写、网络读、外部写和未知形式映射为调用级风险及副作用，
并把 Resolver 结果冻结进安全配置摘要。复合命令、未知 wrapper/alias 至少为 HIGH，但继续交给系统 Shell；
认证环境覆盖、Credential 命令/配置和仓库路径逃逸在 Policy 与执行边界硬拒绝。模型自报的操作族不能覆盖
可信分类、风险、审批或输出预算；直接 Git/GH 和复合形式都不因 Hint 缺失或不匹配被拒绝。结果通过
稳定的 `riskResolutionCode`、`operationHintCode`、`failureActionCode` 和 `commandOutcomeCode` 区分
风险提升、Hint 被忽略、可恢复失败和预期非零退出，恢复逻辑不解析 stderr 或依赖自然语言描述。

Coding 审批使用 `LOW/MEDIUM/HIGH/NEVER` 阈值；风险事实先由可信解析器写入调用级 Policy Request，
再由用户配置的阈值决定是否 ASK。兼容 `ask` 映射 LOW，`auto` 映射 NEVER，`deny` 移除通用执行能力。
`NEVER` 自动执行所有非硬拒绝的 LOW/MEDIUM/HIGH 普通命令；可信分类硬拒绝仍 DENY，Credential 重认证
和一次性 Host 权限升级仍是托管 ASK，不受普通风险阈值自动批准。

Git/GH 只保留基础分级：`status/diff/log/show/grep/ls-files/rev-parse` 等本地读取为 LOW；本地写入及
`fetch/pull`、GH 远端读取为 MEDIUM；Push、远端写入、破坏性操作、`gh api`、未知子命令和任意复合/
Wrapper 形式为 HIGH。HIGH 继续进入用户阈值，不是分类失败；产品不维护完整 Git/GH 参数 DSL。

`execution.request_permissions -> request_permissions` 不是通用 Sandbox 绕过入口，也不授予可复用权限。
它只允许引用同一 Run 中一次以 `NETWORK_PERMISSION_REQUIRED`（兼容读取旧
`NETWORK_UNAVAILABLE`）、`HOST_AUTHENTICATION_UNAVAILABLE`、
`GIT_AUTHENTICATION_UNAVAILABLE` 或 `GH_AUTHENTICATION_UNAVAILABLE` 失败的 `execution.run`，并要求逐字段复用该结果
返回的 `toolCallId`、完整 command、逻辑 workdir 和 timeout；operationFamily 仅是可选诊断 Hint，不参与
精确授权绑定。Runtime 为该托管权限升级创建独立 Policy Decision 与审批 Checkpoint；批准后只用受信 Host 配置及其系统
`git` / `gh` 登录环境的
`host-guarded + network allow` Profile 执行这一次调用。只有直接、非破坏性的系统 `git` / `gh` 命令
可申请；未知、复合、包装、凭据覆盖、路径逃逸、破坏性或结果未知的命令不可升级；Agent 不能创建
Profile、改变 Policy Decision 或批准自己的申请。

`ProjectSkillPlatform` 从受信 Discovery/Visibility Context 组装 Skill Catalog 与精确内容 Loader。它提供
`task-planning`、`result-verification`、共享 `git`/`github` 与 Coding `git-delivery` Classpath Skill，
并允许上层 Application 显式加入
绑定当前可信 tenant/principal 的只读 `USER` Scope 本地目录 Source。目录 root 不来自模型或 Run 请求，
Application 必须在扫描前验证绝对路径、可读性和 symlink 边界。普通旧装配路径不隐式加入 Skill，只有产品
Profile 显式 allowlist 后，`skill.load` / `skill.resource.read` 才作为
`SkillToolCatalogContribution` 写入同一个 `ProjectToolCatalog`。

显式启用的 `web.search` / `web.fetch` 也写入同一个 `ToolCatalogBuilder`。Search 可精确选择 Aliyun、
Brave 或 Tavily，Fetch 可选择 Aliyun、Browserless 或 Tavily。具体 Provider、endpoint、非秘密配置和 Fetch URL Policy
进入冻结 binding；Provider 不读取环境变量、不保存 Credential、不执行 fallback。

配置、权限和精确 Tool 身份继续使用点号命名；模型披露使用 Provider-safe Alias，例如
`file.read -> file_read`、`execution.run -> execution_run` 和
`execution.request_permissions -> request_permissions`。Alias 只影响模型协议，不改变 Provider
执行时收到的精确 Tool 名称。历史 frozen Run 中旧 `git.*` identity 仅用于读取持久化交付证据，不能进入
新 Run 的 Tool Catalog。

经审查启用的 MCP Tool 由 `McpToolCatalogContribution` 写入同一个 `ToolCatalogBuilder`，不会建立 MCP 专用 Registry。每个 MCP server 使用独立 `mcp.<serverId>` Provider；本地 definition hash 与远端 definition digest 分别冻结，Runtime 只通过 `FrozenToolBinding.providerBindingReference` 恢复精确 binding。

`ProjectToolExecutor` 是 Tool Provider adapter，只接收最小化 `ToolInvocationRequest`，并在委派前重新解析 Run Workspace、Principal 和 capability。文件操作继续走 `ProjectToolOperations`；`ProjectExecutionToolOperations` 把
`command/workdir/timeoutMillis/description` 及可选 `operationFamily`
映射为可信 `ExecutionRequest` 并调用 `ExecutionBroker`。`execution.run` 使用配置 Shell 的通用命令文本，不包含命令
目录、参数 DSL 或 Maven/npm/Python 等逐命令生产分支。Coding Profile 在产品边界为通用 Scratch 增加
`GOTMPDIR` 和 `GOCACHE=go-build`；Execution/Runtime Core 不知道 Go。最终 `ToolResult` 提供状态、
退出码、有界合并首尾、明确省略标记、Output Ref、耗时、安全失败类别、稳定错误码、
`failureAction`、可信 `commandOperation`、本次 `toolCallId`、Scratch 状态和 FileChangeSet
引用。普通命令在固定内存中持续排空输出；`INSPECT` 在通道输出预算耗尽时终止进程树并返回
`OUTPUT_LIMIT_EXCEEDED`，模型必须收窄查询后再试。Java 层只对系统 Git/GitHub CLI 做保守风险分类，
不包装或解释普通命令语义。
Tool Result 另保留 `semanticOutcome`、`semanticReasonCode` 和解释器版本：Git Diff 的退出 1 可作为
`EXPECTED_VARIANT/DIFFERENCES_FOUND` 形成 Diff 证据，Git Grep 的退出 1 是 `EMPTY_RESULT/NO_MATCHES`；
无效 revision 的 128 和 Build/Test 非零退出仍是失败，Timeout/Cancel/未知终止不得自动重放。
执行命令已经从受控 Workspace 启动。最高层受信本地装配可将等于当前 Workspace 或位于其下的绝对
`workdir` 规范化为逻辑相对路径；Workspace 外绝对目录、绝对路径 `cd ...` 仍在进入 Broker 前以
`ABSOLUTE_WORKDIR_FORBIDDEN` 结构化拒绝，非法相对路径以 `WORKDIR_INVALID` 拒绝。dispatch 前的确定性
拒绝直接保存失败 ToolResult，不伪造 dispatched/acknowledged，也不会覆盖稳定错误码或误记为结果未知。

Workspace Checkpoint Adapter 将 Project Snapshot 作为通用 Runtime Capability Checkpoint Participant 接入，并在恢复时重新检查当前授权、Binding、Provider 版本和 Drift。显式 Artifact Export 支持受保护文件及选定 ChangeSet/Patch/Diff 文档，不扫描目录自动发布。`PublishedArtifactRequiredChecker` 只接受 Store 中真实 `PUBLISHED` 的 Artifact；Admin Query 仅返回分页、脱敏、无正文的诊断投影。
