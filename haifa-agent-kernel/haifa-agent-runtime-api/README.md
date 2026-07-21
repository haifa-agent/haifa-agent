# Haifa Agent Runtime API

定义 Runtime 的稳定入口、查询、恢复、命令和监听契约，不包含默认 AgentLoop 实现。

- 允许依赖：`haifa-agent-core`（传递依赖 `common`）和 JDK。
- 禁止依赖：Runtime 实现、产品、Spring、模型 Provider、MCP、Sandbox Provider、Admin。
- 所有公共类型位于 `io.haifa.agent.runtime.api`。
- 接口使用同步最小契约；流式事件和异步执行将在相应能力边界明确后扩展。
