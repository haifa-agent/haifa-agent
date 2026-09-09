# Haifa Agent Execution

该 Reactor 聚合命令执行、Sandbox SPI、执行协调，以及 Host Guarded 与 Local Native 两个本地具体
Provider。命令契约明确区分内部可信组件使用的 DIRECT argv 与通用 Tool 使用的完整 SHELL 文本；
两者共享同一个 Broker、授权、环境、审计、取消和输出链。API 与 SPI 保持纯 Java；具体 Provider
不反向进入 Kernel、Runtime 或产品模块。

依赖方向：`execution-core -> execution-api + sandbox-api + project-api`，
`execution-host -> execution-core`，`sandbox-host -> sandbox-api + project-api + project-host`，
`sandbox-local-native -> sandbox-api + project-api + project-host`。Execution Core 保留跨平台命令语义和
Broker 协调；当前 OS、Host Script runtime 与 NIO Workspace watcher 只在窄 `execution-host` 中解析。
只有 Host 与 Local Native 两个精确的具体 Provider 包允许直接使用 Java 进程 API；Project Tool、Runtime、
CLI 和 MCP 均通过 `ExecutionBroker`。

进程正常退出后，Execution 以 `EXITED` 和原始 `exitCode` 交付事实，无论退出码是否为零；通用执行层
不据此判断命令的业务成功或失败。共享 Execution Tool 将该确定结果作为完成的 Tool result 交给 Agent，
只有无法可靠启动或交付结果的执行基础设施故障才进入 Tool failure。命令专用适配器可以在上层解释退出码，
但不能改写这一共享进程事实。

Sandbox Profile 现在精确冻结 Provider、Provider 配置摘要、文件策略和必需能力。Execution Core 在取得
环境租约和打开 Session 前完成 Profile、Provider、预检结果和能力匹配；未知、冲突或能力不足均
fail closed，不做 Provider 轮询或 Host 回退。Local Native 已实现 macOS Seatbelt、Linux
bubblewrap 内部 Adapter 和 Windows unsupported 路径；Task 02 不改变 CLI 默认装配。
