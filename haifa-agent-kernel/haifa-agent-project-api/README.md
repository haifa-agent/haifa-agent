# Haifa Agent Project API

纯 Java 的 Project、Workspace 与安全文件系统公共契约。默认算法和 InMemory 实现在
`haifa-agent-project-core`，真实宿主文件系统实现在 `haifa-agent-project-host`。

本模块保存长期 Project、可选逻辑 Workspace、类型化逻辑路径和 Provider-neutral 文件读取契约。
它不包含默认实现或 Host Workspace Access；Runtime、Product、领域对象和普通 Store 不得接收或返回主机路径。

公共契约支持：

- 受保护的 `list/stat/read/search`；
- 仅使用 `WorkspacePath` 的 `create/write/delete/move`，由 Service 与 Local Provider 双层校验路径边界；`READ/DEVELOP` 授权由 CA 入口负责；
- Workspace 级写租约、WorkspaceRevision 与强内容 Hash 前置条件；
- 同目录临时文件与原子移动替换，保证不暴露半写文件；
- 受保护的普通文件直接删除与移动，严格校验根目录边界；`READ/DEVELOP` 授权由 CA 入口负责，Host 只做路径与物理安全；
- 有界文本 Unified Diff 生成、严格 Parser、Patch 预校验、精确 Hunk 应用与结构化部分失败；
- 平台已收敛为安全原子替换与 `SessionChangeLedger` 纯内存会话变更账本，不再维护自研文件恢复事务、回收站（Quarantine）与跨重启调和。
- `HostRepositoryLocator` 只在单个已授权主机目录内向上定位最近仓库边界；Git 有效性由外部 `HostGitInspectionPort` 回答。Project 不运行 Git、不依赖 Execution，也不把仓库物理路径带出 Host Workspace Access。

命令执行和 Git 已由 Execution/Sandbox/Git 模块实现；Project 模块不反向依赖这些高层实现。

`haifa-agent-project-core` 的 `ProjectIndexService` 提供 generation 原子切换的文件、Java 语法级 Symbol 和 Markdown heading 索引。索引契约只保存逻辑路径和有界派生元数据。

`ProjectConfiguration` 是不可变、内容寻址的可信配置版本，冻结默认 Workspace、Product Profile、能力、Context Source、Tool 与安全策略引用；不保存 Host Path 或 Credential。
