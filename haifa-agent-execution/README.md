# Haifa Agent Execution

> 沙箱裁剪声明：平台已放弃 OS namespace / 容器级强隔离，底层统一为受控宿主执行。
> 详见 `docs/34-sandbox-simplification-and-host-execution-design.md`。

该 Reactor 聚合命令执行、Sandbox SPI、执行协调，以及唯一的本地具体 Provider `host-guarded`。
命令契约明确区分内部可信组件使用的 DIRECT argv 与通用 Tool 使用的完整 SHELL 文本；
两者共享同一个 Broker、授权、环境、审计、取消和输出链。API 与 SPI 保持纯 Java；具体 Provider
不反向进入 Kernel、Runtime 或产品模块。

依赖方向：`execution-core -> execution-api + sandbox-api + project-api`，
`execution-host -> execution-core`，`sandbox-host -> sandbox-api + project-api + project-host`。
Execution Core 保留跨平台命令语义和
Broker 协调；当前 OS、Host Script runtime 与 NIO Workspace watcher 只在窄 `execution-host` 中解析。
只有 `sandbox-host` 这一个具体 Provider 包允许直接使用 Java 进程 API；Project Tool、Runtime、
CLI 和 MCP 均通过 `ExecutionBroker`。

进程正常退出后，Execution 以 `EXITED` 和原始 `exitCode` 交付事实，无论退出码是否为零；通用执行层
不据此判断命令的业务成功或失败。共享 Execution Tool 将该确定结果作为完成的 Tool result 交给 Agent，
只有无法可靠启动或交付结果的执行基础设施故障才进入 Tool failure。命令专用适配器可以在上层解释退出码，
但不能改写这一共享进程事实。

Sandbox Profile 精确冻结 Provider、Provider 配置摘要、允许的可执行文件与环境名。Execution Core 在取得
环境租约和打开 Session 前完成 Profile、Provider 与预检结果匹配；未知或冲突绑定 fail closed，
不做 Provider 轮询或回退。网络断网与文件挂载隔离不再属于 Profile 契约，宿主进程一律使用宿主网络。
