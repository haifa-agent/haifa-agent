# Kernel

Kernel 包含框架无关、长期稳定的基础类型、领域模型、Runtime 契约和默认纯 Java 执行内核。

```text
haifa-agent-common <- haifa-agent-core <- haifa-agent-runtime-api <- haifa-agent-runtime-core
                           ^
                           +-- haifa-agent-project-api <- haifa-agent-project-core <- haifa-agent-project-host
```

- `common`：ID、时间、版本与基础异常。
- `core`：AgentRun、Message、Step、ToolCall、Plan/Todo、Checkpoint 等领域模型；Run 状态合法性只在这里定义。
- `runtime-api`：Snapshot-first 的启动、查询、恢复、命令、Handle 与 Listener 契约。
- `runtime-core`：Bootstrap、`AgentRunExecutionAttempt`、AgentLoop、工具/完成管线、控制、检查点、恢复和内存 Port 实现。
- `project-api`：保持 `io.haifa.agent.project.<area>` 的公共值对象、端口、请求/结果和稳定错误契约。
- `project-core`：位于 `io.haifa.agent.project.core..` 的纯 Java 默认服务、Parser、算法与 InMemory Store。
- `project-host`：位于 `io.haifa.agent.project.hostworkspace..` 的物理路径、文件系统和本机 Workspace 实现。

Runtime Core 可以协调 Core 聚合，但不能复制状态表或绕过 `AgentRun` 行为。Project API/Core 不使用
`java.nio.file` 或本机环境发现，Host 依赖不能反向进入它们。整个 Kernel 禁止依赖 Spring、具体模型或
Sandbox Provider、MCP Transport、JPA、产品和 Admin 模块。
