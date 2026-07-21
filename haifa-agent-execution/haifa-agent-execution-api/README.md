# Haifa Agent Execution API

Provider-neutral 的执行契约，定义有界请求、可信调用上下文、幂等执行、取消、结构化结果、输出存储和环境租约端口。

请求只接受 Workspace 逻辑路径和结构化 argv，不接受宿主机路径、明文凭据或无界资源参数。本模块保持纯 Java，不依赖 Sandbox 实现、Spring 或 Provider SDK。
