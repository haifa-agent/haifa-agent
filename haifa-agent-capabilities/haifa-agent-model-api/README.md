# Haifa Agent Model API

## Binding profiles and effective parameters

`ModelBindingProfile` is the provider-neutral, versioned capability and parameter contract for one exact model
binding. `ModelBindingConsistencyValidator` enforces strict bidirectional consistency between `ModelDefinition`
and its authoritative `ModelBindingProfile`. `EffectiveModelParameters` contains only values validated against
that trusted profile. A run derives a new `ResolvedModelSnapshot` with those effective values and a new digest;
endpoint and credential data remain frozen and are never exposed as user preferences. Product-specific controls
and labels do not belong in this module.

## Reasoning safety

Reasoning policy is represented by typed mode/effort values and normalized into frozen snapshot options.
`SensitiveModelReasoning` is bounded, defensively copied, digest-addressed, and redacted by `toString`. It exists
only at the model invocation/continuation boundary and is never a public Agent message payload.

## Streaming invocation

`AgentChatModel.invoke` remains the compatible synchronous entry point. `invokeStreaming` publishes ordered
`ModelStreamEvent` values through a synchronous callback and stops the physical call when the callback returns
`CANCEL`. The default implementation projects a synchronous result as one completed content delta. Provider raw
chunks, SDK types, and reasoning text must not cross this module boundary.

`AgentChatResponse` preserves an otherwise empty successful transport response so the Runtime can classify it at the
provider-neutral boundary. Empty content with no Tool Call or structured output is not a valid agent decision; callers
must not treat the transport object itself as successful task completion.

`AgentChatRequest.requestId` identifies one frozen logical request across bounded physical attempts, while
`callId` identifies one physical provider call. Adapters normalize empty, partial, server, timeout, and transport
failures separately and may attach a typed `retryAfter` plus `outputObserved`; they never decide the Runtime retry
count or switch the frozen provider/model binding.

纯 Java、供应商无关的模型能力契约。

本模块定义：

- Provider、API Style Binding、Model、Model Call 与 Credential 的类型安全标识；
- Provider 配置状态、Model 配置状态与独立的运行健康状态；
- 一个 Provider 下不可变、有序且 Style 唯一的 `List<ModelApiBindingDefinition>`，以及有序的
  `List<ModelDefinition>`；
- Run 级严格冻结的 `ResolvedModelSnapshot`，包含 Provider/Model/Adapter 版本、API Style、Dialect、
  解析后的 Endpoint、`CredentialRef`、`nativeStreaming`、类型化上下文限制、调用选项和配置摘要；
- 标准 Chat Message、Tool Specification、Tool Call、Usage、Finish Reason 与 Error；
- `AgentChatModel` 和 `CredentialResolver` 端口。

本模块不得依赖 Jackson、HTTP Client、Spring、OpenAI、DeepSeek 或其他 Provider SDK。

工具结果消息除有界摘要和 Provider correlation 外，还可携带已归一化、深度不可变的 `toolResultData` 与裁剪标记；具体协议序列化由 Provider Adapter 负责。

`ResolvedModelSnapshot` 当前只接受严格的 `3.0` Schema。本项目尚未发布旧配置或旧快照，因此不提供
旧构造器、双轨解析或隐式迁移；不完整快照直接失败关闭。

Provider 持有共享 Endpoint、`CredentialRef` 和 `nativeStreaming`。Binding 只持有必填 `style`、缺省为
`standard` 的可选 dialect，以及可选的完整 Endpoint 覆盖；Model 通过 `style` 精确引用同一 Provider
下唯一 Binding。严格遵守既有 Style 的新 Provider 只增加配置，厂商差异才增加 typed dialect/profile。
所有影响请求语义的字段进入快照摘要，Credential 只保存引用而不保存明文。

`ModelReferenceKind.MODEL_ID/ENDPOINT_ID` 用于需要区分模型和已部署推理 Endpoint 的 Provider；该类型由
Provider factory 写入冻结 profile，adapter 不得用名称前缀反推生产语义。
