# Haifa Agent OpenAI-Compatible Model Adapter

## Transport 与 dialect

HTTP、鉴权、同步 JSON、SSE framing/limits/cancel、Tool Call 分片和 usage 解析由同一个 Chat
transport 实现；厂商请求扩展、Endpoint policy 与错误分类由冻结到 snapshot 的 dialect 负责。当前支持：

| Provider | dialect id | 同步 | SSE | Tool Call | Thinking |
| --- | --- | --- | --- | --- | --- |
| DeepSeek | `deepseek-openai-chat` | 是 | 是 | 是 | enabled/high，安全 continuation |
| 阿里云百炼 | `aliyun-bailian-openai-chat` | 是 | 是 | 是 | 由受治理 Qwen profile 决定 |

新配置必须冻结 `dialect_id` 和 `dialect_version`。仅为读取早期 DeepSeek `2.0` 快照保留按
`providerId=deepseek` 的兼容解析；其他缺少 dialect 的快照会被拒绝。

## 阿里云百炼

使用 `AliyunBailianProviderFactory` 从外部治理配置构造 Provider 和模型 profile，不在 adapter 中固定
易变的 Qwen 型号、版本或限额。Provider 配置必须包含完整的 `compatible-mode/v1` Base URL、region、
`workspace_scoped` 与 CredentialRef；公共域名和 Workspace 专属域名不可混用。本地示例可使用
`env://DASHSCOPE_API_KEY`，生产应接入现有 Credential binding/lease。

模型 profile 显式声明 `thinking_profile=none|hybrid|always`、`thinking_enabled`、
`supports_tool_stream` 等能力。只有受支持且显式启用时才发送 `thinking_budget`、
`preserve_thinking`、`reasoning_effort`、`tool_stream`；`tool_stream` 默认不发送。百炼 thinking 复用
Runtime 的受保护 continuation，raw reasoning 不进入公共输出。

本阶段仅支持百炼 OpenAI Chat Completions。OpenAI Responses、DashScope 原生协议和
Anthropic-compatible 是独立的后续 adapter，不复用 Chat SSE accumulator，也不应被配置成已支持。

## DeepSeek thinking

The governed DeepSeek default is `thinking=enabled` with `reasoning_effort=high`; explicit disabled snapshots
remain supported. Enabled requests omit unsupported sampling options, parse sync/stream `reasoning_content`, and
record `completion_tokens_details.reasoning_tokens`. Reasoning is returned only as a bounded sensitive payload;
Tool Call continuation is controlled by Runtime and mapped back as assistant `reasoning_content`.

## Synchronous and streaming boundaries

The adapter supports synchronous JSON Chat Completions and `text/event-stream`. Streaming requests send
`stream=true` and `stream_options.include_usage=true`, then aggregate content, reasoning, tool-call arguments,
and final usage. A normalized `AgentChatResponse` is returned only after identity, finish reason, usage, and tool
JSON validation. Reasoning is an internal sensitive event and is never projected to public Runtime output.

The parser bounds each SSE event, the total response, delta count, content, reasoning, and tool arguments.
Consumer cancellation closes the response body and maps to standard `CANCELLED`; synchronous behavior remains
compatible.

使用 Java 21 `HttpClient` 与 Jackson 实现 OpenAI Chat Completions 协议适配器。

## 默认配置

```yaml
provider-id: deepseek
provider-version: provider-v1
adapter-type: openai-compatible
adapter-version: 1.0.0
endpoint: https://api.deepseek.com
credential-ref: env://DEEPSEEK_API_KEY
models:
  - id: deepseek-v4-pro
    version: model-v1
    provider-model-id: deepseek-v4-pro
provider-options:
  dialect_id: deepseek-openai-chat
  dialect_version: "1.0"
thinking: enabled
reasoning-effort: high
```

`DeepSeekDefaults.provider()` 提供无密钥的类型安全示例。生产应用应通过配置构造 Provider，并用 `EnvironmentCredentialResolver` 或自有 Secret Manager Adapter 解析 `CredentialRef`。

DeepSeek 默认 `thinking=enabled`、`reasoning_effort=high`；显式 disabled 快照仍受支持。

## 装配示意

```java
var chatModel = new OpenAiCompatibleChatModel(
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
        new ObjectMapper(),
        new EnvironmentCredentialResolver());

var runtime = new RuntimeCoreBuilder()
        .registerChatModel("openai-compatible", "1.0.0", chatModel)
        .build();
```

每次调用的 Endpoint、`CredentialRef`、Provider Model ID 与调用选项均来自 `AgentChatRequest.model()` 中已持久化的严格冻结快照；Adapter 不在调用期间读取可变 Provider 目录。

实际使用 Tool Calling 时，还必须同时注册 Runtime `ToolDefinition` 和带 JSON Schema 的 `ModelToolSpecification`。

## 测试

普通测试只连接本地 Stub HTTP Server：

```powershell
mvn -pl :haifa-agent-model-openai-compatible -am test
```

真实冒烟测试默认跳过。显式设置以下变量并执行 Failsafe 才会访问 DeepSeek：

```text
HAIFA_DEEPSEEK_LIVE_TEST=true
DEEPSEEK_API_KEY=<secret>
```

百炼 Live IT 还要求显式设置（会产生真实费用）：

```text
HAIFA_BAILIAN_LIVE_TEST=true
DASHSCOPE_API_KEY=<secret>
HAIFA_BAILIAN_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
HAIFA_BAILIAN_MODEL_ID=<governed-model-id>
HAIFA_BAILIAN_REGION=cn-beijing
```

```powershell
mvn -pl :haifa-agent-model-openai-compatible -am verify -DskipITs=false
```

测试和运行日志不得输出 API Key、完整 Prompt 或原始供应商响应。
