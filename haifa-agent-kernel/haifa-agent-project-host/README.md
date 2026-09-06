# Haifa Agent Project Host

Project 的本机文件系统 Adapter。只有本模块允许解析 `WorkspaceLocationRef` 为真实 `Path`、执行 real-path、
symlink/Junction 防逃逸校验，以及访问本机文件、目录和 Git repository boundary。

Host 类型保留在 `io.haifa.agent.project.hostworkspace..`，不进入公共 Javadoc。公共 DTO、Store payload、
Tool schema 与模型输出不得包含真实宿主路径。

Coding Agent 的 `HostWorkspaceRegistryStore` 也位于这一 Host 边界：它保存可恢复、可撤销的多根授权事实，
并只向产品/模型投影 opaque `workspaceRef`、安全显示名、权限、来源和状态。持久 Adapter 必须保护真实路径，
恢复时重新校验目录、real path、独立的物理目录身份 fingerprint、link/reparse point 与互斥根；同一路径下
替换出的新目录不会继承原授权。`file.*` 的模型输入仍是宿主绝对路径。
