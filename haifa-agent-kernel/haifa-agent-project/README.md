# Haifa Agent Project

纯 Java 的 Project、Workspace 与安全文件系统边界。

本模块保存长期 Project、可选 Workspace、Binding、类型化逻辑路径和 Provider-neutral 文件读取契约。真实主机路径只允许存在于 `provider.local`；Runtime、Product、领域对象和普通 Store 不得接收或返回主机路径。

当前支持：

- `DIRECT`、`READ_ONLY` Binding 下受保护的 `list/stat/read/search`；
- 仅使用 `WorkspacePath` 的 `create/write/delete/move`，并由 Service 与 Local Provider 双层校验只读模式、能力和权限；
- Workspace 级写租约、WorkspaceRevision 与强内容 Hash 前置条件；
- 同目录临时文件、durable flush、原子移动尝试，以及可识别的非原子降级结果；
- 带幂等 operation key 的 `FileChangeSet`、受控 Quarantine 删除/恢复和未知结果 Reconciliation；
- 有界文本 Unified Diff 生成、严格 Parser、Patch 预校验、精确 Hunk 应用与结构化部分失败。

`COPY_ON_WRITE`、`EPHEMERAL_COPY`、命令执行、Git、Index、Snapshot 和 Artifact 尚未在本模块落地，分别由后续阶段实现。
