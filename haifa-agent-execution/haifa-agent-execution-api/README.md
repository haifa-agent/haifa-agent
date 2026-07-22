# Haifa Agent Execution API

Provider-neutral 的执行契约，定义有界请求、可信调用上下文、幂等执行、取消、结构化结果、输出存储和环境租约端口。

请求只接受 Workspace 逻辑路径，以及 `ExecutionCommand.direct(argv)` 或 `ExecutionCommand.shell(command)` 两种明确命令形式，不接受宿主机工作目录、明文凭据或无界资源参数。SHELL 保存一个最大 32KB 的完整命令字符串，Shell 类型和路径由可信 Provider 配置决定。本模块保持纯 Java，不依赖 Sandbox 实现、Spring 或 Provider SDK。

一次性执行可通过 `ExecutionBroker.execute(request, observer)` 观察 stdout/stderr chunk；observer 只负责有界展示，唯一权威结果仍是完成后的 `ExecutionResult`。Tool/Runtime cancellation 通过既有 `ExecutionBroker.cancel(executionId)` 收敛。

`ExecutionBroker.openManagedSession(ManagedProcessRequest)` 为需要长驻双向进程的受控能力提供唯一入口。`ManagedProcessSession` 只暴露有界 stdin 写入、stdout/stderr chunk 读取、退出 Future、取消和关闭；授权、环境租约、审计、输出脱敏和进程树收敛仍由既有 Broker/Provider 链路负责。MCP stdio 只能使用此入口，不能直接启动宿主进程。
