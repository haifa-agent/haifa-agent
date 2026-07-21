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

`COPY_ON_WRITE`、`EPHEMERAL_COPY`、命令执行和 Git 已由 Execution/Sandbox/Git 模块实现；Project 模块不反向依赖这些高层实现。

`ProjectIndexService` 提供 generation 原子切换的文件、Java 语法级 Symbol 和 Markdown heading 索引。索引只保存逻辑路径和有界派生元数据，可全量重建，也可由完整 `FileChangeSet` 精确更新；查询结果在返回前仍通过当前文件服务重新授权。外部漂移只把 generation 标记为 `SUSPECT`，不会伪造 ChangeSet。

`ProjectConfiguration` 是不可变、内容寻址的可信配置版本，冻结默认 Workspace、Product Profile、能力、Context Source、Tool 与安全策略引用；不保存 Host Path 或 Credential。

Workspace Snapshot 支持 `METADATA_ONLY`、`GIT_REFERENCE` 与仅限受控 `EPHEMERAL_COPY` 的 `FULL_COPY`。Snapshot 是可验证的 Workspace 状态引用，不等于 Runtime Checkpoint；它保存逻辑身份、Revision、manifest/root fingerprint、可选 Git 证据或外部 payload ref，绝不内嵌主机路径或副本正文。`WorkspaceSnapshotValidator` 只分类 Drift，不执行 fetch、reset、checkout 或覆盖操作。
