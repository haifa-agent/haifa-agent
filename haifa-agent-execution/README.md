# Haifa Agent Execution

该 Reactor 聚合命令执行、Sandbox SPI、执行协调与首个受控 Host Provider。API 与 SPI 保持纯 Java；具体 Provider 不反向进入 Kernel、Runtime 或产品模块。

依赖方向：`execution-core -> execution-api + sandbox-api + project`，`sandbox-host -> sandbox-api + project`。只有 `sandbox-host` 的受控实现允许直接使用 Java 进程 API。
