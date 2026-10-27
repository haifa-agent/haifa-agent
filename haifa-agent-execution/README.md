# Haifa Agent Execution

> 沙箱裁剪声明：平台已放弃 OS namespace / 容器级强隔离，底层统一为受控宿主执行。
> 详见 `docs/34-sandbox-simplification-and-host-execution-design.md`。

该 Reactor 聚合命令执行、Sandbox SPI、执行协调，默认本地 Provider `host-guarded`，以及可选的
Linux Docker+AIO Provider `docker-aio`（显式装配，不是产品默认）。
命令契约明确区分内部可信组件使用的 DIRECT argv 与通用 Tool 使用的完整 SHELL 文本；
两者共享同一个 Broker、授权、环境、审计、取消和输出链。API 与 SPI 保持纯 Java；具体 Provider
不反向进入 Kernel、Runtime 或产品模块。

依赖方向：`execution-core -> execution-api + sandbox-api + project-api`，
`execution-host -> execution-core`，`sandbox-host -> sandbox-api + project-api + project-host`，
`sandbox-docker -> sandbox-api + execution-api + project-api + project-host`。
Execution Core 保留跨平台命令语义和
Broker 协调；当前 OS、Host Script runtime 与 NIO Workspace watcher 只在窄 `execution-host` 中解析。
只有 `sandbox-host` 这一个具体 Provider 包允许直接使用 Java 进程 API；`sandbox-docker` 只通过
Unix socket Docker Engine 与 AIO HTTP 协议交互。Project Tool、Runtime、CLI 和 MCP 均通过
`ExecutionBroker`。

进程正常退出后，Execution 以 `EXITED` 和原始 `exitCode` 交付事实，无论退出码是否为零；通用执行层
不据此判断命令的业务成功或失败。共享 Execution Tool 将该确定结果作为完成的 Tool result 交给 Agent，
只有无法可靠启动或交付结果的执行基础设施故障才进入 Tool failure。命令专用适配器可以在上层解释退出码，
但不能改写这一共享进程事实。

输出收集不完整不会覆盖已经确认的进程状态，只以 `outputIncomplete` 标记；超时且进程树终止无法确认时
返回 `TIMEOUT_TREE_UNCONFIRMED` 并保持 unknown。启动进程前无法可靠形成结果时沿用 fail-closed unknown，
且不会伪装成已 dispatch；这些事实不包含命令、PID、宿主路径或原始异常。

Sandbox Profile 精确冻结 Provider、Provider 配置摘要、允许的可执行文件与环境名。Execution Core 在取得
环境租约和打开 Session 前完成 Profile、Provider 与预检结果匹配；未知或冲突绑定 fail closed，
不做 Provider 轮询或回退。网络断网与文件挂载隔离不在通用 Profile 契约内：`host-guarded` 一律使用
宿主网络，`docker-aio` 则在 Provider 可信配置中固定镜像、mount、网络与资源，并要求 Docker 提供
internal + gateway isolated + ICC=false 的临时网络；任一条件不满足即 fail closed，不回退宿主。
`docker-aio` 的 Linux 运行与隔离证据仍为 `NOT_RUN`，不能据源码/替身测试宣称已隔离。
