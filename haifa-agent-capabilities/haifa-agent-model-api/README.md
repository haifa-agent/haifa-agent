# Haifa Agent Model API

纯 Java、供应商无关的模型能力契约。

本模块定义：

- Provider、Model、Model Call 与 Credential 的类型安全标识；
- Provider 配置状态、Model 配置状态与独立的运行健康状态；
- 一个 Provider 下不可变、有序的 `List<ModelDefinition>`；
- Run 级 `ResolvedModelSnapshot`；
- 标准 Chat Message、Tool Specification、Tool Call、Usage、Finish Reason 与 Error；
- `AgentChatModel` 和 `CredentialResolver` 端口。

本模块不得依赖 Jackson、HTTP Client、Spring、OpenAI、DeepSeek 或其他 Provider SDK。
