# Haifa Agent Runtime API

定义 Runtime 的稳定入口、查询、恢复、命令和监听契约，不包含默认 AgentLoop 实现。

- 允许依赖：`haifa-agent-core`（传递依赖 `common`）和 JDK。
- 禁止依赖：Runtime 实现、产品、Spring、模型 Provider、MCP、Sandbox Provider、Admin。
- 所有公共类型位于 `io.haifa.agent.runtime.api`。
- 接口使用同步最小契约；流式事件和异步执行将在相应能力边界明确后扩展。
- `AgentRunRequest` 只携带 Definition ID/版本、Session ID、目标和配置快照引用，不传递完整 Core 聚合。
- Session 与 Definition 没有固定绑定，同一 Session 可按每次请求选择不同的版本化 Definition。
- `AgentRunSnapshot` 表示某一时点的不可变运行视图；Core 的 `AgentRunResult` 表示最终结构化业务结果，两者不再同名或混用。
- Query、Resume 和 Command 均使用正式的 `AgentRunId`。
