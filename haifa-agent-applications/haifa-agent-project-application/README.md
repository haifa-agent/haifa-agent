# Haifa Agent Project Application

组合 Project Index、Context Source、既有 Runtime Tool Pipeline 与 Project-only 产品外观。普通产品请求只携带 ProjectId 和消息；默认 Workspace、Profile、Context Source 与 Tool disclosure 从可信版本化配置解析。

本模块不包含 Provider 实现，也不建立第二套 Context、Tool Registry、Policy 或 Session 聚合。

`ProjectToolCatalog` 将 `file.list/stat/read/search/create/write/delete/move/diff/patch`、`git.inspect/status/diff` 与 `execution.run` 共 14 个能力注册到唯一 Tool Catalog。每个定义均包含 Draft 2020-12 输入/输出 Schema、风险、幂等性、副作用、资源和审批元数据；普通 Chat、无有效 capability 或模型不支持 Tool 时冻结集合为空。

配置、权限和精确 Tool 身份继续使用点号命名；模型披露使用 Provider-safe Alias，例如 `file.read -> file_read`、`git.status -> git_status` 和 `execution.run -> execution_run`。Alias 只影响模型协议，不改变 Provider 执行时收到的精确 Tool 名称。

`ProjectToolExecutor` 是 Tool Provider adapter，只接收最小化 `ToolInvocationRequest`，并在委派 `ProjectToolOperations` 前重新解析 Run Workspace、Principal 和 capability。文件操作继续受逻辑路径/Workspace 边界约束，Git 与进程执行继续通过既有 Git adapter 和 `ExecutionBroker`，不提供任意 shell、commit、push 或网络能力。

Workspace Checkpoint Adapter 将 Project Snapshot 作为通用 Runtime Capability Checkpoint Participant 接入，并在恢复时重新检查当前授权、Binding、Provider 版本和 Drift。显式 Artifact Export 支持受保护文件及选定 ChangeSet/Patch/Diff 文档，不扫描目录自动发布。`PublishedArtifactRequiredChecker` 只接受 Store 中真实 `PUBLISHED` 的 Artifact；Admin Query 仅返回分页、脱敏、无正文的诊断投影。
