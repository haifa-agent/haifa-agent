# Haifa Agent Project Application

组合 Project Index、Context Source、既有 Runtime Tool Pipeline 与 Project-only 产品外观。普通产品请求只携带 ProjectId 和消息；默认 Workspace、Profile、Context Source 与 Tool disclosure 从可信版本化配置解析。

本模块承载 Project 产品的内建 Tool，包括 Workspace 文件/Git/Execution Tool，以及默认关闭的 Web
Search/Fetch Tool。Web 的 Provider-neutral Java 接口、Tool adapter、URL Policy 和具体 HTTP Provider
按包分层放在 `io.haifa.agent.application.project.tool.web` 内，不形成独立 Capability 或 Maven Artifact。
本模块不建立第二套 Context、Tool Registry、Policy、Credential Broker 或 Session 聚合。

`ProjectToolCatalog` 将 `file.list/stat/read/search/create/write/delete/move/diff/patch`、`git.inspect/status/diff` 与 `execution.run` 共 14 个能力注册到唯一 Tool Catalog。每个定义均包含 Draft 2020-12 输入/输出 Schema、风险、幂等性、副作用、资源和审批元数据；普通 Chat、无有效 capability 或模型不支持 Tool 时冻结集合为空。

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

`ProjectToolExecutor` 是 Tool Provider adapter，只接收最小化 `ToolInvocationRequest`，并在委派前重新解析 Run Workspace、Principal 和 capability。文件操作继续走 `ProjectToolOperations`；`ProjectExecutionToolOperations` 只把 `command/workdir/timeoutMillis/description` 映射为可信 `ExecutionRequest` 并调用 `ExecutionBroker`。`execution.run` 使用配置 Shell 的通用命令文本，不包含命令目录、参数 DSL 或 Maven/npm/Python 等逐命令生产分支。最终 `ToolResult` 提供状态、退出码、有界合并尾部、Output Ref、耗时、安全失败和 FileChangeSet 引用。

Workspace Checkpoint Adapter 将 Project Snapshot 作为通用 Runtime Capability Checkpoint Participant 接入，并在恢复时重新检查当前授权、Binding、Provider 版本和 Drift。显式 Artifact Export 支持受保护文件及选定 ChangeSet/Patch/Diff 文档，不扫描目录自动发布。`PublishedArtifactRequiredChecker` 只接受 Store 中真实 `PUBLISHED` 的 Artifact；Admin Query 仅返回分页、脱敏、无正文的诊断投影。
