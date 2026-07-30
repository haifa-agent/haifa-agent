# Haifa Coding Agent

## 自主交付契约与完成证据

Coding 产品在每个 Run 上从可信调用方元数据或首条权威用户消息确定
`CHANGE/CREATE/ANALYZE/REVIEW/UNKNOWN`，并生成内容寻址的不可变
`CodingTaskContract`。低置信度请求保持 `UNKNOWN`；模型消息不能改变 Intent，也不能制造交付证据。
当前 Contract 由已有 Run/Session 事实确定性重建，因此不新增 SQLite 表或 Migration。

`CodingDeliveryEvidenceLedger` 只从权威 ToolCall、AgentStep、ChangeSet、Execution 状态和 Artifact
引用重建工作区修改、Diff、验证、只读检查、文档、阻塞和有证据的 No-change 事实。模型自由文本不构成
修改、验证通过或 Artifact 证据。`CodingCompletionPolicy` 对 CHANGE/CREATE 默认要求修改或受限
No-change、验证尝试和 Diff；ANALYZE/REVIEW 要求只读证据且拒绝意外修改；UNKNOWN 必须收敛到
完整修改证据、确定性只读证据或结构化阻塞路径之一。

默认冻结交付预留为剩余 Model Call 20%、Tool Call 25%、Wall Time 20%。预留只改变模型收到的有界
收敛指导，不增加 Runtime 的总预算或时限。缺少完成证据时 Runtime 最多执行两次结构化纠偏，恢复后
从持久消息重建次数，耗尽后以 `COMPLETION_REPAIR_EXHAUSTED` 稳定失败。

## Policy 持久化装配

`ProjectPersistenceAssembly.policy()` 是应用级 Policy 权威 Store 组合：内存模式共享同一 `InMemoryPolicyStore`，SQLite 模式复用 `SqliteStoreFoundation` 的 Snapshot、Decision、Evidence、Grant 与 Trust Store。Coding Agent 重启时复用内容一致的固定 Policy Snapshot；不在应用层实现企业组织或审批工作流。

## Policy assembly

`CodingAgentPolicyAssembly` 是产品装配边界：创建 Coding Agent 默认规则 Snapshot、内存
Decision/Approval evidence Store 和本地同主体 Verifier。它不包含组织、审批路由、待办或业务
状态机。`ProjectExecutionToolOperations` 只接受上游 Tool Pipeline 传入的真实
`policyDecisionRef`；缺少引用时 fail closed，Broker 复核同一 Decision 而不再次询问用户。

组合 Project Index、Context Source、既有 Runtime Tool Pipeline 与 Project-only 产品外观。普通产品请求只携带 ProjectId 和消息；默认 Workspace、Profile、Context Source 与 Tool disclosure 从可信版本化配置解析。

本模块承载 Project 产品的内建 Tool，包括 Workspace 文件/Git/Execution Tool，以及默认关闭的 Web
Search/Fetch Tool。Web 的 Provider-neutral Java 接口、Tool adapter、URL Policy 和具体 HTTP Provider
按包分层放在 `io.haifa.agent.application.project.tool.web` 内，不形成独立 Capability 或 Maven Artifact。
本模块不建立第二套 Context、Tool Registry、Policy、Credential Broker 或 Session 聚合。

## 持久化装配

`ProjectPersistenceAssembly` 是产品层唯一持久化装配入口，只接受三种显式模式：

- `MEMORY`：默认模式，使用 Runtime 内存 Port；
- `SQLITE`：SQLite 是 Session、Run、Attempt、Checkpoint、Runtime State、Event/Outbox 与产品会话映射的唯一事实源；
- `SQLITE_WITH_JSONL`：在 `SQLITE` 基础上，把已提交 Outbox 的安全事件投影为可删除的 JSONL。

SQLite 模式要求数据库文件绝对路径和 `env://` 形式的稳定 continuation protector 引用；JSONL 模式还要求
已存在、可写、非符号链接的受控绝对目录。Application 在一次 checksum 校验中组合 Runtime Migration 与自己
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
reconciliation 使用同一 key 收敛到同一 Run。SQLite 中尚未投递的消息与附件引用使用 continuation
protector 加密并校验 digest，不进入 JSONL 或普通日志。`MEMORY` 与 `SQLITE` 通过同一
`CodingSessionStore` 端口提供相同行为。

tui4j Terminal UI 与富 Tool/Execution/Resource 客户端事件已进入独立
`haifa-agent-coding-terminal` 模块，避免产品 façade 依赖终端实现。`CodingShellService` 与
`CodingSessionExportService` 只定义产品边界；CLI 生产装配分别复用既有
Policy/Approval/ExecutionBroker/Sandbox 和 Runtime Message Store。Session Tree/Fork/Clone、PTY、
后台 Job、模型登录/目录仍未实现。

`ProjectToolCatalog` 将 `file.list/stat/read/search/create/write/delete/move/diff/patch`、`git.inspect/status/diff` 与 `execution.run` 共 14 个能力注册到唯一 Tool Catalog。每个定义均包含 Draft 2020-12 输入/输出 Schema、风险、幂等性、副作用、资源和审批元数据；普通 Chat、无有效 capability 或模型不支持 Tool 时冻结集合为空。
`execution.run` 不再使用通用 `project-safe` 标识：产品装配必须提供冻结 `SandboxProfile`，
Catalog、Policy Resource、Execution Request 和 Broker 解析都使用同一精确 Profile Ref/version。
Provider、网络或受信配置变化会改变 Definition/Binding 的安全身份，旧 Decision/Approval 不能用于
新 Profile；模型可见 Schema 包含 command、逻辑 workdir、有界 timeout、安全描述和显式
`operationFamily`。操作族只允许 `BUILD/TEST/INSPECT/DIFF/MUTATE/UNKNOWN`，不能可靠识别时使用
`UNKNOWN`；这些操作族用于语义失败归类和权威交付证据，而不是命令特例。

`ProjectSkillPlatform` 从受信 Discovery/Visibility Context 组装 Skill Catalog 与精确内容 Loader。它提供
`task-planning`、`result-verification` 两个 Classpath SDK 基础 Skill，并允许上层 Application 显式加入
绑定当前可信 tenant/principal 的只读 `USER` Scope 本地目录 Source。目录 root 不来自模型或 Run 请求，
Application 必须在扫描前验证绝对路径、可读性和 symlink 边界。普通旧装配路径不隐式加入 Skill，只有产品
Profile 显式 allowlist 后，`skill.load` / `skill.resource.read` 才作为
`SkillToolCatalogContribution` 写入同一个 `ProjectToolCatalog`。

显式启用的 `web.search` / `web.fetch` 也写入同一个 `ToolCatalogBuilder`。Search 可精确选择 Aliyun、
Brave 或 Tavily，Fetch 当前只允许 Aliyun。具体 Provider、endpoint、非秘密配置和 Fetch URL Policy
进入冻结 binding；Provider 不读取环境变量、不保存 Credential、不执行 fallback。

配置、权限和精确 Tool 身份继续使用点号命名；模型披露使用 Provider-safe Alias，例如 `file.read -> file_read`、`git.status -> git_status` 和 `execution.run -> execution_run`。Alias 只影响模型协议，不改变 Provider 执行时收到的精确 Tool 名称。

经审查启用的 MCP Tool 由 `McpToolCatalogContribution` 写入同一个 `ToolCatalogBuilder`，不会建立 MCP 专用 Registry。每个 MCP server 使用独立 `mcp.<serverId>` Provider；本地 definition hash 与远端 definition digest 分别冻结，Runtime 只通过 `FrozenToolBinding.providerBindingReference` 恢复精确 binding。

`ProjectToolExecutor` 是 Tool Provider adapter，只接收最小化 `ToolInvocationRequest`，并在委派前重新解析 Run Workspace、Principal 和 capability。文件操作继续走 `ProjectToolOperations`；`ProjectExecutionToolOperations` 把 `command/workdir/timeoutMillis/description/operationFamily` 映射为可信 `ExecutionRequest` 并调用 `ExecutionBroker`。`execution.run` 使用配置 Shell 的通用命令文本，不包含命令目录、参数 DSL 或 Maven/npm/Python 等逐命令生产分支。Coding Profile 在产品边界为通用 Scratch 增加 `GOTMPDIR` 和 `GOCACHE=go-build`；Execution/Runtime Core 不知道 Go。最终 `ToolResult` 提供状态、退出码、有界合并尾部、Output Ref、耗时、安全失败类别、Scratch 状态和 FileChangeSet 引用。
执行命令已经从受控 Workspace 启动；若命令仍以绝对路径 `cd` 开头，Adapter 会在进入 Broker
前返回 `ABSOLUTE_WORKDIR_FORBIDDEN` 可恢复失败，调用方应省略 `cd` 或使用逻辑相对
`workdir`，避免无效主机路径消耗执行预算。

Workspace Checkpoint Adapter 将 Project Snapshot 作为通用 Runtime Capability Checkpoint Participant 接入，并在恢复时重新检查当前授权、Binding、Provider 版本和 Drift。显式 Artifact Export 支持受保护文件及选定 ChangeSet/Patch/Diff 文档，不扫描目录自动发布。`PublishedArtifactRequiredChecker` 只接受 Store 中真实 `PUBLISHED` 的 Artifact；Admin Query 仅返回分页、脱敏、无正文的诊断投影。
