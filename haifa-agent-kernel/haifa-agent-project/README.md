# Haifa Agent Project

纯 Java 的 Project、Workspace 与安全文件系统边界。

本模块保存长期 Project、可选 Workspace、Binding、类型化逻辑路径和 Provider-neutral 文件读取契约。真实主机路径只允许存在于 `provider.local`；Runtime、Product、领域对象和普通 Store 不得接收或返回主机路径。

当前阶段支持 `DIRECT`、`READ_ONLY` 的安全 `list/stat/read/search`。`COPY_ON_WRITE`、`EPHEMERAL_COPY`、文件写入、Execution、Git、Index、Snapshot 和 Artifact 由后续阶段实现。
