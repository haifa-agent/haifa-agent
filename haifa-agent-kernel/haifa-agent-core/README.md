# Haifa Agent Core

定义最稳定的 Agent 领域语言。当前初始化了 Agent、Session、Run 及其生命周期状态，为 Runtime API 提供框架无关的领域契约。

- 允许依赖：`haifa-agent-common` 和 JDK；测试范围内允许测试类库。
- 禁止依赖：Spring、Spring AI、Provider、数据库、Sandbox、Product、Admin 和 Contract。
- 公共 API 分布在 `io.haifa.agent.core.agent`、`session` 与 `run` 包。
- 状态转换由领域对象校验，非法转换立即失败。
