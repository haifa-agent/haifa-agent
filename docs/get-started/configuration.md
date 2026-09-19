# Configuration

The pure Java Starter exposes a deliberately small configuration surface. Security-sensitive model identity, endpoint selection, and credentials are not accepted from an untrusted prompt or conversation message.

## Built-in defaults

| Setting | Built-in Starter behavior |
| --- | --- |
| Model | deepseek-v4-flash |
| Endpoint | https://api.deepseek.com |
| Credential reference | env://DEEPSEEK_API_KEY |
| Thinking | disabled for the built-in default snapshot |
| Runtime/Conversation state | process-local |
| Files/Shell/Git/MCP/Web/Memory/Artifact/Execution | disabled unless explicitly assembled |
| Policy | PolicyPresets.standardApproval() |

The underlying provider integrations can support other reasoning modes and providers. The table above describes only the Starter's built-in default.

## Customize safe Starter settings

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

The value passed to credentialEnvironmentVariable(...) is an environment-variable **name**, never the secret itself.

## Register explicit models

A trusted host can replace the built-in model catalog with explicitly registered model configurations or adapter/snapshot pairs. Multiple registered models are selected by stable model ID; defaultModel(...) chooses the default.

The Starter does not provide model discovery, automatic fallback, health-based routing, or dynamic provider catalogs.

See [Model providers](../advanced/model-providers.md).

## Spring Boot configuration

The Spring Boot Starter exposes only a narrow property set:

| Property | Default |
| --- | --- |
| haifa.agent.enabled | true |
| haifa.agent.name | haifa-agent |
| haifa.agent.instructions | Starter fallback instructions |
| haifa.agent.model.credential-environment-variable | DEEPSEEK_API_KEY |
| haifa.agent.model.connect-timeout | 10s |

There is intentionally no ordinary property for an API-key value, arbitrary endpoint, model ID, or Thinking switch.

For production assembly, provide your own HaifaAgent bean or trusted customizer rather than widening untrusted external configuration.
