# Haifa Agent Model API

## Reasoning safety

Reasoning policy is represented by typed mode/effort values and normalized into frozen snapshot options.
`SensitiveModelReasoning` is bounded, defensively copied, digest-addressed, and redacted by `toString`. It exists
only at the model invocation/continuation boundary and is never a public Agent message payload.

## Streaming invocation

`AgentChatModel.invoke` remains the compatible synchronous entry point. `invokeStreaming` publishes ordered
`ModelStreamEvent` values through a synchronous callback and stops the physical call when the callback returns
`CANCEL`. The default implementation projects a synchronous result as one completed content delta. Provider raw
chunks, SDK types, and reasoning text must not cross this module boundary.

纯 Java、供应商无关的模型能力契约。

本模块定义：

- Provider、Model、Model Call 与 Credential 的类型安全标识；
- Provider 配置状态、Model 配置状态与独立的运行健康状态；
- 一个 Provider 下不可变、有序的 `List<ModelDefinition>`；
- Run 级严格冻结的 `ResolvedModelSnapshot`，包含 Provider/Model/Adapter 版本、规范化 Endpoint、`CredentialRef`、类型化上下文限制、调用选项和配置摘要；
- 标准 Chat Message、Tool Specification、Tool Call、Usage、Finish Reason 与 Error；
- `AgentChatModel` 和 `CredentialResolver` 端口。

本模块不得依赖 Jackson、HTTP Client、Spring、OpenAI、DeepSeek 或其他 Provider SDK。

工具结果消息除有界摘要和 Provider correlation 外，还可携带已归一化、深度不可变的 `toolResultData` 与裁剪标记；具体协议序列化由 Provider Adapter 负责。

`ResolvedModelSnapshot` 当前只接受严格的 `2.0` Schema。旧快照缺失冻结字段时必须由显式迁移程序处理；运行时不会用当前目录值或默认值补齐。

OpenAI-compatible Provider 的厂商协议差异必须通过 `providerOptions` 冻结稳定的 dialect id/version；
region、Workspace/endpoint scope、模型 reasoning/tool profile 等会影响请求语义的字段也必须进入快照和配置摘要，
Credential 只保存引用而不保存明文。
