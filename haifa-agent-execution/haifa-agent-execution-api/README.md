# Haifa Agent Execution API

Provider-neutral 的执行契约，定义有界请求、可信调用上下文、幂等执行、取消、结构化结果、输出存储和环境租约端口。

请求只接受 Workspace 逻辑路径和结构化 argv，不接受宿主机路径、明文凭据或无界资源参数。本模块保持纯 Java，不依赖 Sandbox 实现、Spring 或 Provider SDK。

`ExecutionBroker.openManagedSession(ManagedProcessRequest)` 为需要长驻双向进程的受控能力提供唯一入口。`ManagedProcessSession` 只暴露有界 stdin 写入、stdout/stderr chunk 读取、退出 Future、取消和关闭；授权、环境租约、审计、输出脱敏和进程树收敛仍由既有 Broker/Provider 链路负责。MCP stdio 只能使用此入口，不能直接启动宿主进程。
