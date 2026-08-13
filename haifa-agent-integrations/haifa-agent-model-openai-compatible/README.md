# Haifa Agent OpenAI-Compatible Model Adapter

## Profile factory

`OpenAiCompatibleModelProfileFactory` derives a versioned binding profile from an already resolved snapshot. It
recognizes the audited DeepSeek dialect and provider-neutral `standard` bindings only. Vendor-specific bindings that
have not completed their contract phase are marked unverified instead of inheriting capabilities merely because they
share an OpenAI-compatible transport. DeepSeek V4 Flash and V4 Pro are verified independently for Chat Completions,
Responses, and Anthropic Messages; product exposure can still keep a verified control read-only. Provider request
mapping remains in the dialect/adapter layer.

## API Style 与 dialect

本模块实现彼此独立的 `openai-chat-completions`、`openai-responses` 与 `anthropic-messages`。三者复用
Java HTTP、凭据解析和安全限制，但分别拥有自己的请求/响应 DTO 与 SSE accumulator，不跨 Style 复用
`messages`、`choices`、Item 或 Content Block parser。Provider ID 不参与 Style 或 dialect 推断。

普通宿主可以使用 `OpenAiCompatibleModelConfiguration.builder(credentialResolver)` 类型化装配三种已实现
Style 的 adapter、`ResolvedModelSnapshot`、连接/请求超时和受限调用选项。类型化路径只开放 `standard`
与 DeepSeek profile；百炼和方舟继续使用各自的受治理工厂，不能借此绕过 workspace、region 或模型
profile 校验。Builder 只接收 `CredentialRef`，DeepSeek 始终冻结 `thinking=disabled`。

```java
var configured = OpenAiCompatibleModelConfiguration.builder(new EnvironmentCredentialResolver())
        .providerId("deepseek")
        .modelId("deepseek-chat")
        .providerModelId("deepseek-v4-pro")
        .dialect(OpenAiCompatibleModelConfiguration.Dialect.DEEPSEEK)
        .endpoint(URI.create("https://api.deepseek.com"))
        .credentialRef(new CredentialRef("env://DEEPSEEK_API_KEY"))
        .capabilities(Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING))
        .tokenLimits(1_048_576, 8_192)
        .requestTimeout(Duration.ofSeconds(60))
        .temperature(0.2)
        .toolChoice(OpenAiCompatibleModelConfiguration.ToolChoice.AUTO)
        .build();
```

`temperature` 仅适用于 Chat Completions；`responseFormat(JSON_OBJECT)` 仅适用于声明
`STRUCTURED_OUTPUT` 的 Chat Completions/Responses。该格式选项不是 Java record 解码或结构化最终输出 API。
所有 endpoint 必须是干净的 HTTPS URI。高级宿主仍可直接构造 Adapter 与 Snapshot。

## Runtime 结构化最终输出映射

SDK 的 `chat(message, Record.class)` 会把精确输出 Schema 作为 provider-neutral request requirement 传到
Adapter。该动态 Run 要求与上面的静态 `responseFormat(JSON_OBJECT)` 配置不同：

| API style / dialect | 请求映射 | 终态处理 |
| --- | --- | --- |
| Chat Completions `standard` | 原生 `response_format.type=json_schema`，携带 name、strict 与精确 Schema | 无 Tool Call 的最终 content 必须是 JSON object，归一化后由 Runtime 再校验冻结 Schema |
| Chat Completions DeepSeek/现有非标准 dialect | `response_format.type=json_object`，并以有界 developer instruction 披露最终 Schema | Tool Call 不受最终 Schema 限制；最终 object 仍由 Runtime 作为权威门禁 |
| OpenAI Responses `standard` | `text.format.type=json_schema`，携带 name、strict 与精确 Schema | 最终 output text 归一化后由 Runtime 再校验冻结 Schema |
| Anthropic Messages `standard` | 原生 `output_config.format.type=json_schema`，携带精确 Schema | 最终 text block 归一化后由 Runtime 再校验冻结 Schema |
| DeepSeek Anthropic Messages | 当前没有已验证的 `output_config.format` 兼容证据，稳定返回 `structured_output_unsupported` | 不降级成提示词解析或未校验 JSON |

Adapter 只负责协议映射和 JSON object 归一化，不决定业务 record 是否有效。Tool Calls 优先进入既有
Runtime Tool Pipeline；只有最终回答才触发 Schema 门禁。无效 JSON、能力缺失、Provider 拒答和输出截断
分别保持稳定错误分类，SDK 只从持久化的 `AgentRunResult.structuredOutput` 解码类型化值。

Chat Completions 当前支持：

| Provider | dialect id | 同步 | SSE | Tool Call | Thinking |
| --- | --- | --- | --- | --- | --- |
| OpenAI Chat Completions | `standard` | 是 | 是 | 是 | 不发送厂商扩展 |
| DeepSeek | `deepseek-openai-chat` | 是 | 是 | 是 | enabled/high，安全 continuation |
| 阿里云百炼 | `aliyun-bailian-openai-chat` | 是 | 是 | 是 | 由受治理 Qwen profile 决定 |
| 火山方舟 | `volcengine-ark-openai-chat` | 是 | 是 | 是 | 由受治理豆包/Endpoint profile 决定 |

配置通过 Provider 下的 `apiBindings` 声明 Style。省略 dialect 即 `standard`；只有存在已验证协议差异
时才声明非标准 dialect。当前未发布旧配置或快照，不保留旧 options、Provider-ID 猜测或双轨入口。

标准 Chat Completions Provider 的受信配置还会把 Endpoint 主机冻结为 `endpoint_host`，因此
Provider ID 不参与协议或主机推断。`https` 可指向该配置显式声明的第三方主机；`http` 即使主机匹配，
也只有在产品同时显式允许不安全本机模型且 Endpoint 为 loopback 时才接受。严格遵守标准
messages、响应、Tool Call 与 SSE 语义的新厂商只需省略 dialect；非标准字段、
SSE、usage、错误或 Tool Call 行为才需要独立 dialect。

## OpenAI Responses

`OpenAiResponsesModel` 支持同步 Responses 与语义 SSE，映射 message、function_call、
function_call_output、reasoning、usage、incomplete/failed 和 Tool 参数分片。请求固定 `store=false`。
Parser 对累计响应、单事件、事件数、内容和 Tool 参数设限；取消、终态缺失及终态后事件失败关闭。

`standard` 以 OpenAI Responses 契约为准。`deepseek-openai-responses` 只允许已验证的
`deepseek-v4-flash`，拒绝图片/文件与非 automatic function selection，要求单调 `sequence_number`，
并以 Responses 终态收敛而不等待 `[DONE]`。本地 `chatgpt2api` 文本与 SSE 使用 `standard`，但普通
function tool 当前不产生 `function_call`，所以对应模型能力只声明 `TEXT_CHAT`。

## Anthropic Messages

`AnthropicMessagesModel` 以 Anthropic Messages 官方契约作为 `standard`：请求使用 `POST /v1/messages`、
`x-api-key` 与 `anthropic-version: 2023-06-01`，映射顶层 system、Content Blocks、`tool_use`、
`tool_result`、`input_schema`、usage 和 named SSE。thinking、signature 与 redacted thinking 只作为受保护
continuation 保留，不进入公共输出；Tool 参数 JSON、累计响应、事件数和单事件均受限。

`standard` Structured Output 使用官方 `output_config.format` JSON Schema；已有 `output_config.effort` 会与
format 合并。Schema 只约束最终直接输出，不限制 Tool Call、Tool Result 或 Thinking；同步和 SSE 都只在
终态 text block 归一化 structured Map，拒答与截断先保持各自 finish reason。

DeepSeek Anthropic API 使用显式 `deepseek-anthropic-messages` dialect，因为其 ignored/unsupported 字段、
模型映射与 thinking 行为不完全等同 Anthropic standard。该 dialect 仅允许已验证的
`deepseek-v4-flash`/`deepseek-v4-pro`，拒绝图片、文档、redacted thinking 和 server tools；产品默认
冻结 `thinking=disabled`。该 dialect 尚未验证 `output_config.format`，类型化 Structured Output 在网络调用
前 fail closed。Binding 使用完整 Endpoint 覆盖 `https://api.deepseek.com/anthropic`，共享 Credential 与
`nativeStreaming` 仍归 Provider 所有。

## 阿里云百炼

使用 `AliyunBailianProviderFactory` 从外部治理配置构造 Provider 和模型 profile，不在 adapter 中固定
易变的 Qwen 型号、版本或限额。Provider 配置必须包含 `workspaceId` 与 CredentialRef，region 缺省为
`cn-beijing`。Endpoint 不接受外部自由注入，而是固定构造成
`https://{workspaceId}.{region}.maas.aliyuncs.com/compatible-mode/v1`；`workspaceId` 和 region
都必须是合法 DNS label。本地示例可使用 `env://DASHSCOPE_API_KEY`，生产应接入现有 Credential
binding/lease。

当产品通过通用 OpenAI-compatible 配置接入百炼时，dialect 会从同一受信 Endpoint 严格解析并冻结
`workspace_id` 与 `region`。只接受 HTTPS、无端口/查询/片段、路径精确为 `/compatible-mode/v1`，且
主机精确匹配 `{workspaceId}.{region}.maas.aliyuncs.com`；错误地域格式、Workspace、路径或外部域名
都会在装配期 fail closed。该冻结逻辑留在本 adapter，不向 Personal Assistant 公共配置增加百炼字段。
产品只声明 Provider-neutral reasoning mode；adapter 将 `ENABLED` 映射为百炼 `always` thinking、受保护
continuation 和恢复所需的冻结选项，将 `DISABLED` 映射为显式关闭，不根据模型名称猜测行为。

模型 profile 显式声明 `thinking_profile=none|hybrid|always`、`thinking_enabled`、
`supports_tool_stream` 等能力。只有受支持且显式启用时才发送 `thinking_budget`、
`preserve_thinking`、`reasoning_effort`、`tool_stream`；`tool_stream` 默认不发送。百炼 thinking 复用
Runtime 的受保护 continuation，raw reasoning 不进入公共输出。

百炼当前仅支持 OpenAI Chat Completions。DashScope 原生协议和百炼 Anthropic-compatible 尚未接入。

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
models:
  default: deepseek-responses-flash
  providers:
    - id: deepseek
      endpoint: https://api.deepseek.com
      credentialRef: env://DEEPSEEK_API_KEY
      nativeStreaming: true
      apiBindings:
        - style: openai-chat-completions
          dialect: deepseek-openai-chat
        - style: openai-responses
          dialect: deepseek-openai-responses
        - style: anthropic-messages
          dialect: deepseek-anthropic-messages
          endpoint: https://api.deepseek.com/anthropic
      models:
        - id: deepseek-responses-flash
          providerModelId: deepseek-v4-flash
          style: openai-responses
          capabilities: [TEXT_CHAT, TOOL_CALLING, STRUCTURED_OUTPUT, REASONING]
          contextWindow: 131072
          maxOutputTokens: 8192
        - id: deepseek-anthropic-flash
          providerModelId: deepseek-v4-flash
          style: anthropic-messages
          capabilities: [TEXT_CHAT, TOOL_CALLING, REASONING]
          contextWindow: 131072
          maxOutputTokens: 8192
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

Responses Live IT 使用独立显式开关；本地中转只读取通用 OpenAI 变量：

```text
HAIFA_DEEPSEEK_RESPONSES_LIVE_TEST=true
DEEPSEEK_API_KEY=<secret>
HAIFA_DEEPSEEK_RESPONSES_MODEL_ID=deepseek-v4-flash

HAIFA_OPENAI_RESPONSES_LIVE_TEST=true
OPENAI_BASE_URL=http://127.0.0.1:30000/v1
OPENAI_API_KEY=<secret>
OPENAI_MODEL_ID=<model-id>

# 独立探测普通 function Tool Call/Tool Result；不由文本 Live 开关隐式启用
HAIFA_OPENAI_RESPONSES_TOOL_LIVE_TEST=true

HAIFA_DEEPSEEK_ANTHROPIC_LIVE_TEST=true
DEEPSEEK_API_KEY=<secret>
HAIFA_DEEPSEEK_ANTHROPIC_MODEL_ID=deepseek-v4-flash
```

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
