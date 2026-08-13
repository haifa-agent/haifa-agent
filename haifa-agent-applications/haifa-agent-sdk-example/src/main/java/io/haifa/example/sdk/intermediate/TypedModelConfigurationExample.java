package io.haifa.example.sdk.intermediate;

import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.openai.EnvironmentCredentialResolver;
import io.haifa.agent.model.openai.OpenAiCompatibleModelConfiguration;
import io.haifa.agent.model.openai.OpenAiCompatibleModelConfiguration.Dialect;
import io.haifa.agent.model.openai.OpenAiCompatibleModelConfiguration.ToolChoice;
import io.haifa.agent.starter.HaifaAgentStarter;
import java.net.URI;
import java.time.Duration;
import java.util.Set;

/** Builds a frozen DeepSeek model registration without making a provider request. */
public final class TypedModelConfigurationExample {
    private TypedModelConfigurationExample() {}

    public static void main(String[] arguments) {
        var configured = OpenAiCompatibleModelConfiguration.builder(new EnvironmentCredentialResolver())
                .providerId("deepseek")
                .providerVersion("2026-08-12")
                .modelId("deepseek-chat")
                .modelVersion("2026-08-12")
                .providerModelId("deepseek-v4-pro")
                .dialect(Dialect.DEEPSEEK)
                .endpoint(URI.create("https://api.deepseek.com"))
                .credentialRef(new CredentialRef("env://DEEPSEEK_API_KEY"))
                .capabilities(Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING))
                .tokenLimits(1_048_576, 8_192)
                .connectTimeout(Duration.ofSeconds(10))
                .requestTimeout(Duration.ofSeconds(60))
                .temperature(0.2d)
                .toolChoice(ToolChoice.AUTO)
                .build();

        try (var agent = HaifaAgentStarter.builder()
                .instructions("Answer clearly using only disclosed application Tools.")
                .model(configured)
                .build()) {
            System.out.println(agent.assembly().profile().runProfileId());
            System.out.println(configured.snapshot().configurationDigest());
        }
    }
}
