# Haifa Agent OpenAI-Compatible Model Adapter

## Transport 与 dialect

HTTP、鉴权、同步 JSON、SSE framing/limits/cancel、Tool Call 分片和 usage 解析由同一个 Chat
transport 实现；厂商请求扩展、Endpoint policy 与错误分类由冻结到 snapshot 的 dialect 负责。当前支持：

| Provider | dialect id | 同步 | SSE | Tool Call | Thinking |
| --- | --- | --- | --- | --- | --- |
| OpenAI Chat Completions | `openai-chat-completions` | 是 | 是 | 是 | 不发送厂商扩展 |
| DeepSeek | `deepseek-openai-chat` | 是 | 是 | 是 | enabled/high，安全 continuation |
| 阿里云百炼 | `aliyun-bailian-openai-chat` | 是 | 是 | 是 | 由受治理 Qwen profile 决定 |
| 火山方舟 | `volcengine-ark-openai-chat` | 是 | 是 | 是 | 由受治理豆包/Endpoint profile 决定 |

新配置必须冻结 `dialect_id` 和 `dialect_version`。仅为读取早期 DeepSeek `2.0` 快照保留按
`providerId=deepseek` 的兼容解析；其他缺少 dialect 的快照会被拒绝。

标准 Chat Completions Provider 的受信配置还会把 Endpoint 主机冻结为 `endpoint_host`，因此
Provider ID 不参与协议或主机推断。`https` 可指向该配置显式声明的第三方主机；`http` 即使主机匹配，
也只有在产品同时显式允许不安全本机模型且 Endpoint 为 loopback 时才接受。严格遵守标准
messages、响应、Tool Call 与 SSE 语义的新厂商只需复用 `openai-chat-completions` dialect；非标准字段、
SSE、usage、错误或 Tool Call 行为才需要独立 dialect。

## 阿里云百炼

使用 `AliyunBailianProviderFactory` 从外部治理配置构造 Provider 和模型 profile，不在 adapter 中固定
易变的 Qwen 型号、版本或限额。Provider 配置必须包含 `workspaceId` 与 CredentialRef，region 缺省为
`cn-beijing`。Endpoint 不接受外部自由注入，而是固定构造成
`https://{workspaceId}.{region}.maas.aliyuncs.com/compatible-mode/v1`；`workspaceId` 和 region
都必须是合法 DNS label。本地示例可使用 `env://DASHSCOPE_API_KEY`，生产应接入现有 Credential
binding/lease。

模型 profile 显式声明 `thinking_profile=none|hybrid|always`、`thinking_enabled`、
`supports_tool_stream` 等能力。只有受支持且显式启用时才发送 `thinking_budget`、
`preserve_thinking`、`reasoning_effort`、`tool_stream`；`tool_stream` 默认不发送。百炼 thinking 复用
Runtime 的受保护 continuation，raw reasoning 不进入公共输出。

本阶段仅支持百炼 OpenAI Chat Completions。OpenAI Responses、DashScope 原生协议和
Anthropic-compatible 是独立的后续 adapter，不复用 Chat SSE accumulator，也不应被配置成已支持。

## 火山方舟

`VolcengineArkProviderFactory` 冻结 region、完整 `/api/v3` endpoint、受信 `endpoint_host` 和模型 profile。
`providerModelId` 的语义必须由 `ModelReferenceKind.MODEL_ID` 或 `ENDPOINT_ID` 明确声明，禁止依赖 `ep-`
前缀猜测。Ark dialect 支持 `thinking`、`reasoning_effort`、`max_completion_tokens`、`service_tier` 的
profile allowlist；默认模型不继承 DeepSeek thinking。响应中的 actual model 仅作为本次结果审计，不修改
冻结 binding。

### 当前兼容矩阵

| 能力 | OpenAI Chat Completions | DeepSeek | Bailian | Ark |
| --- | --- | --- | --- | --- |
| Sync Chat | 是 | 是 | 是 | 是 |
| SSE Content | 是 | 是 | 是 | 是 |
| final usage chunk | 是 | 是 | 是 | 是 |
| Tool Calls | 是 | 是 | 是 | 是 |
| Image input | 显式 capability | 显式 capability | 显式 capability | 显式 capability |
| Thinking | 无厂商扩展 | enabled/high | profile-gated | profile-gated |
| Tool reasoning continuation | 否 | 必须 | profile-gated | profile-gated |
| Live IT | 未提供 | opt-in | opt-in | opt-in |

图片输入不是 dialect 推断结果。冻结模型只有声明 `IMAGE_INPUT` 时才可发送图片；纯文本消息继续使用
字符串 `content`，包含 `ImageUrlPart` 或临时 `ImageDataPart` 的 USER 消息映射为标准 Chat Completions
`text` / `image_url` 数组。Adapter 不抓取 URL；临时字节仅在请求组装时转换为 data URL，不进入持久化。

| Provider | Endpoint 示例 | Credential 示例 | 模型引用 | 厂商扩展 |
| --- | --- | --- | --- | --- |
| OpenAI-compatible | `https://api.openai.com/v1`、受信第三方 HTTPS 主机或显式允许的 loopback `/v1` | `env://OPENAI_API_KEY` | model id | 无 |
| DeepSeek | `https://api.deepseek.com` | `env://DEEPSEEK_API_KEY` | model id | thinking object |
| Bailian | `https://{workspaceId}.{region}.maas.aliyuncs.com/compatible-mode/v1` | `env://DASHSCOPE_API_KEY` | Qwen model id/alias | enable_thinking/tool_stream |
| Ark | `https://ark.cn-beijing.volces.com/api/v3` | `env://ARK_API_KEY` | typed Model ID/Endpoint ID | thinking/service_tier/token parameter |

标准 OpenAI dialect 可冻结 `native_streaming=false`。此时 Adapter 使用同步 Chat Completions 获取权威
`usage`，再通过 provider-neutral 默认桥接发出有界 Content/Usage 事件，适用于不实现 SSE usage 的本机中转。

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

真实冒烟测试默认跳过。`DeepSeekLiveIT` 是 nightly 与 Suite Runner 的唯一 CP-01 实现；显式设置
以下变量并执行 Failsafe 才会访问 DeepSeek：

```text
HAIFA_DEEPSEEK_LIVE_TEST=true
DEEPSEEK_API_KEY=<secret>
```

Suite Runner 的 `--execute` 路径会设置 `HAIFA_SUITE_EXECUTION=true`。任一显式开关启用后，
缺少 `DEEPSEEK_API_KEY` 都会失败，而不是静默跳过。

百炼 Live IT 还要求显式设置（会产生真实费用）：

```text
HAIFA_BAILIAN_LIVE_TEST=true
DASHSCOPE_API_KEY=<secret>
HAIFA_BAILIAN_WORKSPACE_ID=<required-workspace-id>
HAIFA_BAILIAN_MODEL_ID=<governed-model-id>
HAIFA_BAILIAN_REGION=cn-beijing
```

方舟 Live IT 只调用用户已经创建并授权的 binding，不访问管理面或启停 Endpoint：

```text
HAIFA_ARK_LIVE_TEST=true
ARK_API_KEY=<secret>
HAIFA_ARK_BASE_URL=https://ark.cn-beijing.volces.com/api/v3
HAIFA_ARK_MODEL_ID=<model-or-endpoint-id>
HAIFA_ARK_MODEL_REFERENCE_KIND=MODEL_ID|ENDPOINT_ID
```

```powershell
mvn -pl :haifa-agent-model-openai-compatible -am verify -DskipITs=false
```

测试和运行日志不得输出 API Key、完整 Prompt 或原始供应商响应。
