# Haifa Agent Project Host

Project 的本机文件系统 Adapter。只有本模块允许把逻辑 `WorkspaceId` 解析为经过验证的真实 `Path`、执行 real-path、
symlink/Junction/reparse 防逃逸校验，以及访问本机文件、目录和 Git repository boundary。

Host 类型保留在 `io.haifa.agent.project.hostworkspace..`，不进入公共 Javadoc。公共 DTO 与 Store payload 不得包含
真实宿主路径；CA Host 只按授权合同在 `<workspace_paths>` 提示块与 `workspace_attach` 结果中渲染当前已授权目录的
规范 `rootPath`。

Coding Agent 的 `HostWorkspaceLocationStore` 与 `AuthorizedDirectoryStore` 也位于这一 Host 边界：前者把
`WorkspaceId` 直接映射到规范宿主根，并在每次解析时重新校验 canonical path、link/reparse point 和物理目录身份；
后者只保存当前 tenant/owner 的授权目录事实（owner、`WorkspaceId`、规范宿主根、`READ / DEVELOP`、physical
fingerprint），不保存或推导额外权限。产品/UI 投影（`AuthorizedDirectoryView`）只包含当前 tenant/owner 的 ACTIVE
记录，并仅披露 opaque `workspaceRef`、安全显示名、mode 与状态；CA Host 在模型提示块/工具结果中额外渲染规范
`rootPath`，但 `physicalFingerprint` 不披露。持久 Adapter 必须保护真实路径，恢复时重新校验目录、real path、
link/reparse point、互斥根、稳定 canonical 身份与独立的物理目录身份 fingerprint。同一规范路径被物理替换时
fail closed 并要求显式重新授权；换成另一个 canonical path、状态非 ACTIVE 或无法验证时一律 fail closed。
`file.*` 的模型输入仍是宿主绝对路径；`host-guarded` 不提供 OS 文件系统隔离。
