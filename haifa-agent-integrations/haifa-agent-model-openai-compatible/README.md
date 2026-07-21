# Haifa Agent OpenAI-Compatible Model Adapter

使用 Java 21 `HttpClient` 与 Jackson 实现的同步 OpenAI Chat Completions 协议适配器。首个配置目标为 DeepSeek `deepseek-v4-pro`。

## 默认配置

```yaml
provider-id: deepseek
adapter-type: openai-compatible
endpoint: https://api.deepseek.com
credential-ref: env://DEEPSEEK_API_KEY
models:
  - id: deepseek-v4-pro
    provider-model-id: deepseek-v4-pro
thinking: disabled
stream: false
```

`DeepSeekDefaults.provider()` 提供无密钥的类型安全示例。生产应用应通过配置构造 Provider，并用 `EnvironmentCredentialResolver` 或自有 Secret Manager Adapter 解析 `CredentialRef`。

首版强制 `thinking.type=disabled`。不能通过调用选项开启思考模式，因为 DeepSeek 在思考模式的 Tool Call 后要求回传 `reasoning_content`，该能力需要独立的持久化与安全设计。

## 装配示意

```java
var provider = DeepSeekDefaults.provider();
var chatModel = new OpenAiCompatibleChatModel(
        provider,
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
        new ObjectMapper(),
        new EnvironmentCredentialResolver());

var runtime = new RuntimeCoreBuilder()
        .registerChatModel("openai-compatible", chatModel)
        .build();
```

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

```powershell
mvn -pl :haifa-agent-model-openai-compatible -am verify -DskipITs=false
```

测试和运行日志不得输出 API Key、完整 Prompt 或原始供应商响应。
