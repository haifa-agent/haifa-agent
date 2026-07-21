# Kernel

Kernel 包含框架无关、长期稳定的基础类型、领域模型、Runtime 契约和默认纯 Java 执行内核。

```text
haifa-agent-common <- haifa-agent-core <- haifa-agent-runtime-api <- haifa-agent-runtime-core
```

- `common`：ID、时间、版本与基础异常。
- `core`：AgentRun、Message、Step、ToolCall、Plan/Todo、Checkpoint 等领域模型；Run 状态合法性只在这里定义。
- `runtime-api`：Snapshot-first 的启动、查询、恢复、命令、Handle 与 Listener 契约。
- `runtime-core`：Bootstrap、`AgentRunExecutionAttempt`、AgentLoop、工具/完成管线、控制、检查点、恢复和内存 Port 实现。

Runtime Core 可以协调 Core 聚合，但不能复制状态表或绕过 `AgentRun` 行为。整个 Kernel 禁止依赖 Spring、具体模型或 Sandbox Provider、MCP Transport、JPA、产品和 Admin 模块。
