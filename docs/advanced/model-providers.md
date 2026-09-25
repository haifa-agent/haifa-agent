# Model Providers

Haifa Agent 将 Provider-neutral Model Contract 与具体 Provider Protocol Adapter 分离。

Run 会冻结自己使用的 Model Snapshot 与 Adapter coordinate。Runtime 不会把已经创建的 Run 静默迁移到不同 Provider / Model Binding。

## Starter 内建默认值

安全默认 SDK Starter 当前会构建一个 DeepSeek V4 Flash Binding：

- Endpoint：https://api.deepseek.com
- Credential：env://DEEPSEEK_API_KEY
- 内建 Snapshot Thinking：disabled
- Persistence：process-local

这些只是 Starter 默认值，不是整个 Model Integration 的全局限制。

## 显式注册 Model

可信应用代码可以注册：

- Typed OpenAI-compatible Model Configuration；
- 或显式 AgentChatModel + ResolvedModelSnapshot。

一旦注册自定义 Model，就会替代 Starter 内建 Model Catalog。

可以注册多个 Model ID，并通过 defaultModel(...) 选择默认项。

## Provider Integrations

仓库当前包含 OpenAI-compatible、Anthropic-style、Google Gemini 以及 Local Auth Compatibility 等 Integration。

OpenAI-compatible 模块支持多个经过审查的 Provider Dialect / Binding。Provider / Model 兼容变化通常比公共 Architecture 更快，因此精确事实以模块 README 与测试为准：

- [OpenAI-compatible Integration](../../haifa-agent-integrations/haifa-agent-model-openai-compatible/README.md)
- [Anthropic Integration](../../haifa-agent-integrations/haifa-agent-model-anthropic/README.md)
- [Google Gemini Integration](../../haifa-agent-integrations/haifa-agent-google-gemini/README.md)

## Provider token limits and stream safety

`AgentChatRequest.maxOutputTokens` is mapped to each protocol's provider-side output-token parameter. This is the
semantic limit that should normally stop generation; it is not a byte limit and therefore cannot by itself bound an
SSE response body.

| Provider / binding | Request parameter sent by the adapter | Provider-side end signal |
| --- | --- | --- |
| OpenAI native Chat Completions | `max_completion_tokens` (current OpenAI field) | Chat `finish_reason=length`; SSE usage/terminal chunks |
| Generic OpenAI-compatible Chat bindings | `max_tokens` by default | Chat `finish_reason=length`; SSE ends with `[DONE]` where supported |
| OpenAI-compatible Ark | `max_tokens`, or `max_completion_tokens` when `token_limit_parameter` selects it | Provider-specific Chat `finish_reason` and stream terminal event |
| OpenAI Responses | `max_output_tokens` | `response.incomplete` with `incomplete_details.reason=max_output_tokens` |
| DeepSeek Responses | `max_output_tokens` | Responses-compatible incomplete reason; Chat API uses `max_tokens` |
| Google Gemini `generateContent` | `generationConfig.maxOutputTokens` | `finishReason=MAX_TOKENS` |
| Anthropic Messages | `max_tokens` | `message_delta` / final message stop reason `max_tokens` |

The OpenAI-compatible adapter also serves DeepSeek, Bailian, Kimi, Zhipu, SiliconFlow and TokenRhythm Chat bindings;
they inherit the `max_tokens` field unless their dialect explicitly changes it. The Antigravity Gemini private dialect
removes `maxOutputTokens` because that endpoint does not accept the public Gemini field. The Codex Responses dialect
also intentionally omits `max_output_tokens` because its endpoint contract does not accept it.

Provider token limits remain the primary semantic guard. Haifa additionally applies a local transport safety policy to
native SSE responses: each event is capped at 1 MiB, and the complete raw stream is capped at a fixed 64 MiB. The
64 MiB cap is only a final defensive fallback for malformed, misconfigured, or unexpectedly verbose streams; it is
not a provider-published stream-size guarantee and is independent of the configured semantic response-byte budget.

协议参数的官方参考： [OpenAI Chat Completions](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)、
[OpenAI Responses streaming events](https://developers.openai.com/api/reference/resources/responses/streaming-events)、
[DeepSeek Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/)、
[Gemini generateContent](https://ai.google.dev/api/generate-content) 和
[Anthropic Messages](https://docs.anthropic.com/en/api/messages)。

## 不做隐式 Fallback

Haifa Agent 不把 Provider Catalog 当成 best-effort router。

未知、不可用或不匹配的 Binding 会显式失败；Authentication failure 也不会自动切到另一个 Model。

## Reasoning / Continuation

Reasoning 能力取决于具体 Provider 与 Binding。

某些 Provider 为保持 Tool-call Protocol 正确性，需要保存受保护的 Provider Continuation；这类数据不是公开 Reasoning Output。

面向产品的应用应依赖归一化 Final Answer、Tool Call、Usage 与安全 Runtime Lifecycle Event，而不是依赖 Provider 私有 Chain-of-Thought。

## Credentials

Model Credential 通过间接 Reference 表达，并在 Adapter Boundary 解析。

不要把 Secret 值写入 ProductProfile、Run Input、Prompt、Log 或 Public Diagnostics。
