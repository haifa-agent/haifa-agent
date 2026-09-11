# Haifa Coding Agent

## Product client API

`io.haifa.agent.application.project.product.coding.client.CodingSessionClient` 是 Coding Agent 的稳定产品
API；`LocalCodingSessionClient` 是当前进程内实现。Terminal、其他 UI 和测试只依赖该契约，不从
Terminal 模块反向取得产品能力，也不直接访问 Runtime Store。独立产品的具体 Runtime、模型、Tool、
Persistence 装配仍由最高层应用模块负责。`CodingSessionView.activeRun` 只表示当前活动 Run；需要等待或
核验终态的客户端必须保存 `runId`，并通过 `CodingSessionClient.findRun` 查询作用域内的权威 Snapshot。
同一产品 API 包中的 `CodingAgentClient`、`CodingAgentClientFactory` 和 `CodingAgentClientMetadata`
定义标准客户端与公开装配契约，不包含 Provider 分支；具体工厂实现继续位于最高层独立产品装配模块。
`CodingAuthenticationClient` 还通过 `CodingAuthenticationProgressView` 向 UI 投影不含 Secret 的
`STARTING / WAITING_USER / EXCHANGING / STORING` 登录阶段；具体 OAuth Attempt、Provider HTTP 和凭据存储
仍留在最高层装配与共享 Local Model Auth Integration 中。

## Shared model profile readiness

Coding model preferences remain product-owned and this phase does not add `/thinking` or change the Session Store.
The Coding product projects the trusted Profile into a safe, four-dimensional model state: connection, binding
availability, transient runtime health, and Run scope. Unknown Profile metadata is fail-closed, and version/digest
fields remain outside the product projection. A compile-time architecture test imports the public
`ModelBindingProfile` and `DefaultModelParameterResolver` contracts and verifies that Model API/Core have no Personal
Assistant dependency. Future Coding controls can therefore reuse the common validation and snapshot semantics
without copying PA DTOs or provider dialect logic.

## Prompt-first 自主交付

Coding Agent 的基础工作方法由产品拥有的版本化资源
`META-INF/haifa-agent/prompts/coding-agent-v1.txt` 提供。资源具有稳定版本和 SHA-256 身份，CLI
只负责装配，不再维护按评测 Case 累积的长 Prompt。基础 Prompt 保持通用；Tool 专属路径、
`operationFamily` 和验证/Diff 用法由对应 Tool Definition 描述；`task-planning` 与
`result-verification` 继续通过 Skill 渐进披露。

基础 Prompt 把完整任务契约作为实现与完成的事实边界：核心逻辑通过不能替代 public API、输入输出、错误、
状态、副作用、顺序、兼容性或修改范围等明确契约。Agent 在修改前建立与风险匹配的简短契约清单，完成前
回读原始请求和权威仓库契约；精确类型、文本、格式和动态值只在公开来源明确要求时按字面核对。

BUILD/TEST 首次失败但有界输出没有可操作证据时，基础 Prompt 要求 Agent 不原样盲重试；
`result-verification` Skill 指导它最多重跑一次，在同一 Shell 命令中把 stdout/stderr 重定向到临时日志，
仅返回关键词命中及少量上下文，保留原测试退出码并清理文件。该协议直接复用 `execution.run`，不增加日志 Tool、
Output Store 读取、持久化或 Runtime Completion Gate。

交付约束位于版本化基础 Prompt。精确剩余预算和完整交付状态留在权威控制面与 Trace，不再通过每轮
重建的尾部 `[CODING_RUN_STATE]` 改写模型请求。预算阈值、恢复策略和完成门禁纠偏只在状态转换时作为
Agent-visible、用户不可见的 Session 消息追加；旧请求因此保持为新请求的完整历史前缀。追加消息不包含
Case/Fixture 信息、宿主路径、原始 Tool 输出或模型自报的语义覆盖。

Coding Agent 不再向模型上下文注入 `ORIENT/PLAN/CHANGE/VERIFY/REVIEW/DELIVER/BLOCKED`
等叙事性阶段，也不再持续派发 `coding.work-phase` 进展事件。模型自主负责规划并决定下一步操作；确定性
逻辑仅在终态完成时由 `CodingCompletionPolicy` 守住用户明确的交付约束（如必需的变更/验证证据、
未解决的确定性阻塞、硬预算限制以及提交/推送/PR 意图），不满足要求时阻止任务完成，满足时中性放行。
ANALYZE/REVIEW 任务保持只读约束；修改工作区不会隐式产生 commit、push 或 PR 意图。

`execution.run` 2.0.0 按可信有效操作族限制每通道输出：INSPECT 使用模型输出预算 1×、DIFF 4×，
TEST/BUILD/MUTATE/UNKNOWN 8×，同时受硬上限约束。Diff 结果提供观察到的文件/分块数、计数是否完整和
可选 Artifact Ref；截断后必须使用返回引用或更窄的分页命令，不能把观察计数当作完整 Diff。
失败结果同样保留执行状态、可选退出码、墙钟耗时、截断标记、bounded 合并输出和稳定失败/动作码，
并通过权威 `ToolResult` 进入后续模型上下文。默认只接受退出码 0；调用方只有在命令文档明确把某个
非零退出定义为正常观察结果时，才可通过同时包含 0 和该值的 `expectedExitCodes` 显式声明。普通未声明
非零退出保持 `COMMAND_FAILED`；只有执行器明确报告可执行文件或工具链缺失时才使用
`DEPENDENCY_UNAVAILABLE`，不会从普通命令输出关键词推断。

## 自主交付模式与完成证据

Coding 产品只接受可信调用方元数据提供的 `CHANGE/CREATE/ANALYZE/REVIEW` 模式；没有可信模式时保持
`UNKNOWN`，不从普通用户文本的关键词推断意图。模型消息不能改变模式，也不能制造交付证据。

`CodingDeliveryEvidenceLedger` 只从权威 ToolCall、AgentStep、有界执行事实和按需审查状态
引用重建工作区修改、确定性 Change Review、验证、只读检查、阻塞和有证据的 No-change 事实。模型自由文本不构成
修改或验证通过证据。Issue 29 将文件变更事实从 `FileChangeSet` 事务收敛为成功的 Mutation ToolCall 与按需审查（Git 目录使用 Git 工作树状态，Plain 目录使用会话内有界记录 `SessionChangeLedger`），移除每次写操作同步生成 Review 的开销。Phase 3 以 Run 级 `RepositoryBaseline` 在首次受管写入前冻结各仓 HEAD 与 dirty 摘要，按 nearest repository boundary 分流 Git/Plain Review；`execution.run` 无法证明全部写入归属、初始工作树已脏或证据读取不完整时，`coding-change-review/2` 明确产出 `ATTRIBUTION_PARTIAL`，不会伪装成完整证据。既有 `coding-change-review/1` 仍可确定性读取。

`CodingCompletionPolicy` 对 CHANGE/CREATE 默认要求修改或受限 No-change、最后一次修改之后的验证尝试，
以及覆盖最后一次修改的确定性 Review；`DIFF_INSPECTION` 不再作为修改任务完成门禁的兼容 fallback，
但 DIFF 命令、只读审阅能力和对应诊断事实继续保留。ANALYZE/REVIEW 要求只读证据且拒绝意外修改。UNKNOWN 用于普通交互：
没有权威 Workspace 修改时允许文本回答正常结束，不触发完成修复；一旦观察到 Workspace 修改，
仍必须满足完整的修改、验证和 Review 证据。需要硬性交付保证的调用方必须提供可信任务模式。

轻量 `CodingVerificationProfile` 只保存有界候选、来源、成本、超时与触发层级，按“用户显式配置 →
仓库指令/构建配置 → 相邻测试 → 生态默认”在每个触发层级独立选择，不引入语言插件框架。验证阶梯仍由
Coding Prompt/Skill 约束为语法/静态检查、精确相邻测试、受影响模块和最终门禁。TEST/BUILD Tool Result
保留每次结构化 Validation Attempt；候选在 Coding Session 创建时由可信 Host 冻结到 Session metadata，
重启后按摘要与精确命令匹配恢复来源和 scope。runner stdout/stderr 不作为数量或 scope 的可信来源，当前
统一报告 `COUNTS_UNAVAILABLE`，也不会扩展 runner 专用解析器来制造虚假的完整覆盖。CLI Host 会把根目录
现存且非符号链接的 `verify.ps1` 或 `verify.sh` 作为当前 OS 的平台验证候选冻结；精确命中该候选的执行即使
没有模型提供的可选 operation-family hint，也可产生验证证据，其他未知命令不能据此冒充验证。

可信本机产品宿主可以在 Definition instructions 中冻结一个产品私有、Agent-visible 的 L0-L2 Workspace
环境块，用于表达已经由宿主掌握的安全边界、根仓库/instructions 状态、根静态项目标记和 frozen validation
candidate。该投影不是公共 `WorkspaceSnapshot`、Capability Detector 或恢复事实源；Coding Agent 模块不新增
对应公共 DTO、持久化 Schema 或动态 executable/version 探测，具体静态发现和路径脱敏仍由 CLI 宿主负责。

`CodingRunOutcomeProjectionService` 将交付证据结果与 Run 协议状态分别投影为
`SATISFIED/INCOMPLETE` 和 `CLEAN/PARTIAL/UNCLEAN/IN_PROGRESS`，并通过 `coding-run-outcome/2` 的幂等
`coding.task-outcome` 安全事件记录。Coding CLI、Coding Web 与受信 Coding Host 可通过
`CodingSessionClient.findOutcome` 查询权威投影，无需解析 Event map；归档查看器仍可兼容读取历史
`outcome/1` 与当前 `outcome/2` 文件事件。它不是 Benchmark Verifier 结果，也不增加新的 Core Run 状态。

可信宿主还可在创建 Session 或提交新 Turn 时冻结 `WORKTREE_ONLY/LOCAL_COMMIT/REMOTE_PUSH/PULL_REQUEST`
交付意图；默认仍是 `WORKTREE_ONLY`，普通模型文本和“继续”不会升级它。交付意图只表达完成目标和投影
元数据，不授权或拦截 Git 命令。Commit、Push、PR 与其他命令一样通过唯一的 `execution.run` 进入通用
风险分类、Policy/Approval、Workspace、Sandbox、网络权限和审计边界，不再经过 Coding 产品专用的
Broker 前置交付门禁。系统仍从 Tool 结果投影有界交付证据；显式选择更高交付目标时，完成策略仍按顺序
检查相应结果。证据绑定脱敏的 workspace-relative Repository Scope Digest，因此根仓、`docs/`、
`test-config/` 等独立仓库不能互相复用拓扑、Diff、验证或结果确认事实。

默认冻结交付预留为剩余 Model Call 20%、Tool Call 25%、Wall Time 20%。预留只作为控制面事实，
其中 Wall Time 与 Runtime 一致地排除人工交互/审批等待；预留不增加 Runtime 的总预算或时限，也不逐轮进入模型 Prompt。缺少完成证据时 Runtime 最多执行两次结构化纠偏，恢复后
从持久消息重建次数，耗尽后以 `COMPLETION_REPAIR_EXHAUSTED` 稳定失败。

生产控制面不维护 Verification Plan/Dimension/Evidence，也不接受模型自报的验证标签。外部
Evaluation/Trace Replay 继续独立使用隐藏验收、Workspace 快照与 Scratch 清理事实，不与生产完成门禁
共享模型声明。

## Policy assembly

`CodingAgentPolicyAssembly` 是产品装配边界：创建 Coding Agent 自有的 immutable
`PolicyRuleSet`、共享的纯 evaluator 和独立 Approval verifier。它不创建 Snapshot/Decision/Evidence/
Grant/Trust Store，也不包含组织、审批路由、待办或业务状态机。

`CodingAgentExecutionPolicy` 在 Broker 最终门按可信 `ExecutionOrigin` 分类当前已支持入口：
Runtime Tool 必须关联 `sourceToolCallId`，用户终端命令不能携带 Tool Call，内部只读 Git 必须是
`PRODUCT_INTERNAL + git.read`。相关键不是授权凭据；WorkspaceAccess、path、Sandbox、Credential 与
Broker enforcement 仍实时执行，未知入口 fail closed。Runtime 来源会重新读取 Run、运行中的 frozen
ToolCall、configuration、当前 Policy 与唯一有效的 exact Interaction；CLI 用户命令和模型触发执行要求
DEVELOP。只有产品内部固定的 8 条只读 Git probe 可在 READ 下执行，任何 argv、profile、environment、
scratch、timeout、output family 或 working-directory 扩张均拒绝。CA/PA 当前均拒绝 managed session。

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
绝对目录。Application 使用共享 SQLite 边界唯一的 `HaifaAgentStoreMigrations`；V1000～V1007 已由该
统一 registry 拥有，WorkspaceAccess 建表已折入 V1007，CA 不再维护产品侧 migration 追加链。
每次进程启动生成新的 worker ID，并把完整 `RuntimePersistencePorts` 与 worker ID 注入 `RuntimeCoreBuilder`。
`SQLITE_BUSY/LOCKED` 的有界重试只在 SQLite `BEGIN IMMEDIATE` 尚未开始事务工作时由 Store 执行；Runtime 不重放
整个 Unit of Work，事务工作开始后、提交不确定或其他数据库错误均 fail closed。

Core `AgentSession` 与 `ProjectProductSession` 使用同一个 `AgentSessionId`。产品映射显式保存
tenant、principal、project、workspace、配置 ID/版本/digest 和 product profile；每次读取都与 Core
Session 重新核对，漂移时 fail closed。JSONL projector 只在 Runtime 提交后触发；关闭时先停止上层新请求，
再冲刷投影，最后关闭 SQLite 连接。

Application 自有的 Product/Coding 表通过 MyBatis Mapper XML 接入
`SqliteRuntimeUnitOfWork`，与 Runtime 共用同一个 `BEGIN IMMEDIATE` 事务边界；应用层 Store
不直接使用 JDBC。Mapper 仍经过 SQLite Foundation 的静态 XML 校验，禁止 `${...}` 动态 SQL。

`coding_workspace_registry` 是 CA 自有 Host/Application 持久事实，不进入公共 Runtime/Core。SQLite Adapter
通过当前持久保护器保存本机根位置，并绑定 project、workspace、location 与物理目录身份 physical fingerprint；解密失败、目录缺失、
canonical 身份漂移、link/reparse point 或根重叠都会禁用记录而不恢复挂载。同一安全 canonical path 删除后重建时，
ACTIVE 条目保留 workspace identity 并刷新 physical fingerprint；REVOKED、DISABLED、不同 canonical path 或不可验证路径
都不会自动恢复。模型只能看到脱敏 Registry 与当前 Access 的交集投影；本地
`file.*` 继续接收宿主绝对路径并在当前活动 Registry/Scope 中重新解析。标准 `CodingSessionClient` 还提供
脱敏 workspace 清单与撤销入口，供受信产品界面移除非初始根的持久 Access 和挂载。

`coding_workspace_access` 是 CA 唯一持续用户授权关系。领域对象只由现有 `TenantRef + PrincipalRef` 组成的
owner、`WorkspaceId` 与 `READ / DEVELOP` mode 构成；SQLite 表也严格只有对应五列。`READ` 只允许文件读取，
`DEVELOP` 才允许文件 mutation 与 execution 进入后续 Policy/Sandbox/Credential 门。启动时只在初始 Access
缺失时创建 `DEVELOP`，不得覆盖已降级值；attach/worktree 由受信控制面替换 mode，撤销先删除 Access。
每次文件操作和 execution workspace 解析都会读取当前 Access，即使旧 Scope 或 Registry 仍有活动 mount，
缺失/降级也会 fail closed。Registry、Host Scope 和技术 Binding 均不携带或推导用户权限；CA mount 的
Binding 固定提供技术读写上限，只能进一步拒绝，不能在 Access 缺失时放行。Registry 终态字段为
`physicalFingerprint` / `physical_fingerprint`，且不新增第二个 fingerprint。该 Store 不进入公共
Runtime/SDK/Execution 或 Personal Assistant。

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

`ProjectToolCatalog` 将 `file.list/stat/read/search/create/write/delete/move/diff/patch`、`workspace.attach`、
`workspace.worktree.create` 与 `execution.run` 共 13 个能力注册到唯一 Tool Catalog。模型目录不再披露 `git.*` 或
`github.*` Tool；Git/GitHub 操作由
`execution.run` 直接调用系统 `git` / `gh`。每个定义均包含 Draft 2020-12 输入/输出 Schema、风险、
幂等性、副作用、资源和审批元数据；普通 Chat、无有效 capability 或模型不支持 Tool 时冻结集合为空。
Catalog 保留 `file.search` 供显式配置兼容，但 Coding CLI 默认不冻结该能力；大型仓库的文件发现和内容
搜索使用通用 `execution.run`，由模型根据冻结 Shell 与 `PATH` 选择 `rg`、`rg --files` 或平台适配的
替代命令。应用不增加搜索专用 Executor、不解析搜索意图，也不在 Java 中拼接命令选项。
普通手工源码更新优先使用 `file.patch` 2.1.0：它接受 Codex 风格的上下文 Patch，覆盖新增、更新和同一授权
目录内的多文件调用；删除和移动分别使用 `file.delete`、`file.move`。Update hunk 的 `@@ <text>` 只是可选
导航提示，旧正文/context 的唯一精确匹配才决定落点；多处匹配无法由唯一提示消歧时 fail closed，不选择第一个位置。
`file.write` 保留给有意整体替换的小文件；目标不存在时会原子创建，生成代码和机械批量修改继续通过通用 CLI/生成器完成。
文件 Mutation 保证完整原子替换；遇到外部冲突或不确定结果时 fail closed 返回错误，不自动重放或自动对账。`execution.run` 会在进程启动时记录 execution ID、PID 和工作目录
摘要；已得到终态进程结果可直接对账，未知终止或失败不得自动重放。
`execution.run` 不再使用通用 `project-safe` 标识：产品装配必须提供冻结 `SandboxProfile`，
Catalog、Policy Resource、Execution Request 和 Broker 解析都使用同一精确 Profile Ref/version。
Provider、网络或受信配置变化会改变 Definition/Binding 的安全身份，旧 Decision/Approval 不能用于
新 Profile；模型可见 Schema 包含 command、活动 Registry 的 `workspaceRef`、该根下的 `relativeWorkdir`、有界 timeout、安全描述和可选
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

模型目录不包含权限申请 Tool。当受信 preflight 产生稳定错误码且 Tool 异常与 Journal 同时证明 `NOT_DISPATCHED` 时，Runtime 将原 ToolCall 和 Step 标记为 `FAILED`，记录不可变的失败事实（包含 `failureCode` 与 `dispatchState = NOT_DISPATCHED`），返回 `CONTINUE` 允许模型在下一个 turn 获知失败原因后自主决策（如调整参数、更换能力或向用户报告阻塞）；系统不创建 `execution-recovery` Interaction，不生成 successor 调用，也不维护双重 recovery profile。若工具已派发或结果不确定，或者属于内部协议/配置错误，则一律 Fail Closed（终止 Run 为 `FAILED`），严禁自动重放具有副作用的工具调用。

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
`file.read -> file_read` 和 `execution.run -> execution_run`。Alias 只影响模型协议，不改变 Provider
执行时收到的精确 Tool 名称。历史 frozen Run 中旧 `git.*` identity 仅用于读取持久化交付证据，不能进入
新 Run 的 Tool Catalog。

经审查启用的 MCP Tool 由 `McpToolCatalogContribution` 写入同一个 `ToolCatalogBuilder`，不会建立 MCP 专用 Registry。每个 MCP server 使用独立 `mcp.<serverId>` Provider；本地 definition hash 与远端 definition digest 分别冻结，Runtime 只通过 `FrozenToolBinding.providerBindingReference` 恢复精确 binding。

`ProjectToolExecutor` 是 Tool Provider adapter，只接收最小化 `ToolInvocationRequest`，并在委派前重新解析 Run Workspace、Principal 和 capability。文件操作继续走 `ProjectToolOperations`；`ProjectExecutionToolOperations` 把
`command/workspaceRef/relativeWorkdir/timeoutMillis/description` 及可选 `operationFamily`
映射为可信 `ExecutionRequest` 并调用 `ExecutionBroker`。`execution.run` 使用配置 Shell 的通用命令文本，不包含命令
目录、参数 DSL 或 Maven/npm/Python 等逐命令生产分支。Coding Profile 在产品边界为通用 Scratch 增加
`GOTMPDIR` 和 `GOCACHE=go-build`；Execution/Runtime Core 不知道 Go。最终 `ToolResult` 提供状态、
退出码、有界合并首尾、明确省略标记、Output Ref、耗时、安全失败类别、稳定错误码、
`failureAction`、可信 `commandOperation`、本次 `toolCallId`、Scratch 状态和 FileChangeSet
引用。普通命令在固定内存中持续排空输出；`INSPECT` 在通道输出预算耗尽时终止进程树并返回
`OUTPUT_LIMIT_EXCEEDED`，模型必须收窄查询后再试。Java 层只对系统 Git/GitHub CLI 做保守风险分类，
不包装或解释普通命令语义。
进程数预算触发且进程树已收敛时返回 `PROCESS_LIMIT_EXCEEDED`，不会伪装成 `OUTCOME_UNKNOWN`。已持久化的
ExecutionResult 是权威执行事实；Change Review 等派生投影失败只返回安全的不可用原因码，不得吞掉执行结果。
Change Review 成功结果中的 `artifactRef` 与 `changeReviewArtifactRef` 均由严格输出 Schema 声明，避免
确定的 ExecutionResult 因派生字段契约漂移被误判为 `TOOL_OUTCOME_UNKNOWN`。
命中冻结验证候选时生成的 `validationAttemptRef` 同样属于严格 Schema 契约，并在直接返回与只读
reconcile 路径使用同一份冻结定义校验。
Tool Result 另保留 `semanticOutcome`、`semanticReasonCode` 和解释器版本。普通命令默认只接受退出码 0；
例如只有调用方为 `git diff --exit-code`、`git diff --no-index`、`git grep` 或 `rg` 显式声明
`expectedExitCodes: [0, 1]` 时，退出 1 才作为 `EXPECTED_VARIANT/DECLARED_EXPECTED_EXIT_CODE` 进入证据。
无效 revision 的 128 和未声明的 Build/Test 非零退出仍是失败，Timeout/Cancel/未知终止不能通过该字段
改写为成功，也不得自动重放。
执行命令已经从受控 Workspace 启动。模型必须从安全 Registry 投影选择 `workspaceRef`，并以 `relativeWorkdir`
表达该活动根下的目录；Host Adapter 再解析为 `WorkspacePath` 与受保护物理目录。绝对 workdir、UNC/盘符、遍历、
链接逃逸、失效或撤销的 root 都在进入 Broker 前结构化拒绝。直接 `git -C` 返回不产生 Policy Decision 的
`WORKSPACE_PROTOCOL_REQUIRED`，不再升级成不可批准的权限拒绝。dispatch 前的确定性拒绝直接保存失败
ToolResult，不伪造 dispatched/acknowledged，也不会覆盖稳定错误码或误记为结果未知。

`workspace.worktree.create` 是 CA 独有的始终审批能力：精确目标同时绑定 source `workspaceRef`、不可变 base
commit、新分支、受控 target name 和交付意图，不接受模型指定的主机目标路径或权限；source 必须具有当前
`DEVELOP` Access。受信 Git Provider 创建并校验 worktree 后，CA 才把新 root 以
`APPROVED_WORKTREE_CREATE` 登记、写入新 workspace 的 `DEVELOP` Access 并返回脱敏
`workspaceRef`；失败时清理且不激活 root。当前重启恢复无法建立受信 Git reconciliation，因此会 fail closed
禁用对应 root，不能把普通 Registry 测试描述成进程级强隔离证明。`file.*` 仍要求模型传宿主绝对路径并由
Registry/Scope 映射，未改成相对路径或 root alias。

Workspace Checkpoint Adapter 可由受信 Host 注册为通用 Runtime Capability Checkpoint Participant，并在恢复时重新检查当前授权、Binding、Provider 版本和 Drift；类型存在不等于所有 Host 已完成装配。DIRECT Host 只做 current-state reconcile，永不自动覆盖文件；无人值守 Host 必须使用隔离 Workspace 与可恢复 Snapshot，否则不能声明具备自动恢复等级。显式 Artifact Export 支持受保护文件及选定 ChangeSet/Patch/Diff 文档，不扫描目录自动发布。`PublishedArtifactRequiredChecker` 只接受 Store 中真实 `PUBLISHED` 的 Artifact；Admin Query 仅返回分页、脱敏、无正文的诊断投影。

Completion 产品验收统一通过 `CompletionPolicy` 返回结构化阻塞与证据。Artifact 检查由产品的
`PublishedArtifactRequiredChecker` 实现该接口；Runtime 不再提供单独的 `RequiredArtifactChecker` 配置入口。

Coding base prompt 1.8.0 将任务进展和方法选择交给主模型；Runtime 不再按普通失败簇次数裁决任务终止。权限、unknown、资源上限与产品 Completion 门禁保持独立。
