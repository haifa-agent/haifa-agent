# 配置

Pure Java Starter 刻意只暴露一组很小的配置面。Model 身份、Endpoint 选择、Credential 等安全敏感信息不能由不可信 Prompt 或 Conversation 消息直接注入。

## 内建默认值

| 配置 | Starter 默认行为 |
| --- | --- |
| Model | deepseek-v4-flash |
| Endpoint | https://api.deepseek.com |
| Credential Reference | env://DEEPSEEK_API_KEY |
| Thinking | 内建默认 Snapshot 关闭 |
| Runtime / Conversation 状态 | 进程内 |
| 文件 / Shell / Git / MCP / Web / Memory / Artifact / Execution | 除非显式装配，否则关闭 |
| Policy | PolicyPresets.standardApproval() |

底层 Provider Integration 可以支持其它 Reasoning 模式和 Provider。上表只描述 Starter 自己的内建默认行为。

## 调整 Starter 的安全配置

~~~java
import io.haifa.agent.starter.HaifaAgentStarter;
import java.time.Duration;

try (var agent = HaifaAgentStarter.builder()
        .name("support-agent")
        .instructions("You are a concise support assistant.")
        .credentialEnvironmentVariable("MY_DEEPSEEK_API_KEY")
        .connectTimeout(Duration.ofSeconds(15))
        .build()) {
    // use the Agent
}
~~~

credentialEnvironmentVariable(...) 接收的是环境变量名称，而不是 Secret 值本身。

## 注册显式 Model

可信宿主可以使用显式的 Model Configuration，或者直接提供 Adapter + ResolvedModelSnapshot，以替换 Starter 的内建 Model Catalog。

可以注册多个稳定 Model ID，并通过 defaultModel(...) 选择默认 Model。

Starter 不提供 Model 自动发现、隐式 fallback、基于健康状态的路由或动态 Provider Catalog。

详见 [Model Providers](../advanced/model-providers.md)。

## Spring Boot 配置

Spring Boot Starter 只暴露有限的 Properties：

| Property | 默认值 |
| --- | --- |
| haifa.agent.enabled | true |
| haifa.agent.name | haifa-agent |
| haifa.agent.instructions | Starter fallback instructions |
| haifa.agent.model.credential-environment-variable | DEEPSEEK_API_KEY |
| haifa.agent.model.connect-timeout | 10s |

这里有意不提供明文 api-key、任意 Endpoint、Model ID 或 Thinking 开关。

生产环境如果需要更完整的装配，应提供自己的 HaifaAgent Bean 或可信 Customizer，而不是继续扩大不可信外部配置面。
