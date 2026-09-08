# Haifa Agent Project Host

Project 的本机文件系统 Adapter。只有本模块允许解析 `WorkspaceLocationRef` 为真实 `Path`、执行 real-path、
symlink/Junction 防逃逸校验，以及访问本机文件、目录和 Git repository boundary。

Host 类型保留在 `io.haifa.agent.project.hostworkspace..`，不进入公共 Javadoc。公共 DTO、Store payload、
Tool schema 与模型输出不得包含真实宿主路径。

Coding Agent 的 `HostWorkspaceRegistryStore` 也位于这一 Host 边界：它只保存可恢复、可撤销的多根挂载事实，
不保存或决定用户授权。产品/模型投影只包含同时具有当前 `WorkspaceAccess` 的 ACTIVE workspace，并仅披露
opaque `workspaceRef`、安全显示名、`READ / DEVELOP` mode、来源和状态。持久 Adapter 必须保护真实路径，
恢复时重新校验目录、real path、link/reparse point、互斥根、稳定 canonical 身份和独立的物理目录身份
fingerprint。ACTIVE 条目在同一安全 canonical path 被删除并重建时保留 workspace identity 和既有 Access，
只刷新 physical fingerprint；换成另一个 canonical path、状态非 ACTIVE 或无法验证时一律 fail closed。
`file.*` 的模型输入仍是宿主绝对路径。
