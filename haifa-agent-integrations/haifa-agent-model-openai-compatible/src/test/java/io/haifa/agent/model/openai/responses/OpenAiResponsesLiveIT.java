package io.haifa.agent.model.openai.responses;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelCallId;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelMessage;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.model.openai.EnvironmentCredentialResolver;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("live")
class OpenAiResponsesLiveIT {
    @Test
    void invokesDeepSeekResponsesWhenExplicitlyEnabled() {
        Assumptions.assumeTrue(enabled("HAIFA_DEEPSEEK_RESPONSES_LIVE_TEST"));
        requireEnvironment("DEEPSEEK_API_KEY");
        String providerModelId = environment("HAIFA_DEEPSEEK_RESPONSES_MODEL_ID", "deepseek-v4-flash");

        var response = model(false)
                .invoke(request(snapshot(
                        "deepseek",
                        providerModelId,
                        URI.create("https://api.deepseek.com"),
                        "DEEPSEEK_API_KEY",
                        OpenAiResponsesDialects.DEEPSEEK,
                        Set.of(
                                ModelCapability.TEXT_CHAT,
                                ModelCapability.TOOL_CALLING,
                                ModelCapability.STRUCTURED_OUTPUT,
                                ModelCapability.REASONING))));

        assertThat(response.responseId()).isNotBlank();
        assertThat(response.content()).isNotBlank();
        assertThat(response.usage().outputTokens()).isPositive();
    }

    @Test
    void invokesTrustedLoopbackResponsesWhenExplicitlyEnabled() {
        Assumptions.assumeTrue(enabled("HAIFA_OPENAI_RESPONSES_LIVE_TEST"));
        String baseUrl = requireEnvironment("OPENAI_BASE_URL");
        requireEnvironment("OPENAI_API_KEY");
        String providerModelId = requireEnvironment("OPENAI_MODEL_ID");

        var response = model(true)
                .invoke(request(snapshot(
                        "local-openai",
                        providerModelId,
                        URI.create(baseUrl),
                        "OPENAI_API_KEY",
                        OpenAiResponsesDialects.STANDARD,
                        Set.of(ModelCapability.TEXT_CHAT))));

        assertThat(response.responseId()).isNotBlank();
        assertThat(response.content()).isNotBlank();
        assertThat(response.usage().outputTokens()).isPositive();
    }

    private static OpenAiResponsesModel model(boolean allowInsecureLoopback) {
        return new OpenAiResponsesModel(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                new ObjectMapper(),
                new EnvironmentCredentialResolver(),
                allowInsecureLoopback,
                1024 * 1024);
    }

    private static AgentChatRequest request(ResolvedModelSnapshot snapshot) {
        return new AgentChatRequest(
                new ModelCallId("responses-live-call"),
                new AgentRunId("responses-live-run"),
                1,
                1,
                snapshot,
                java.util.List.of(ModelMessage.text(ModelMessageRole.USER, "Reply with OK.")),
                java.util.List.of(),
                16,
                Duration.ofSeconds(60),
                Map.of());
    }

    private static ResolvedModelSnapshot snapshot(
            String providerId,
            String providerModelId,
            URI endpoint,
            String credentialEnvironment,
            String dialect,
            Set<ModelCapability> capabilities) {
        return ResolvedModelSnapshot.create(
                new ModelProviderId(providerId),
                "live-v1",
                new ModelDefinitionId(providerId + "-responses-live"),
                "live-v1",
                providerModelId,
                OpenAiResponsesModel.ADAPTER_TYPE,
                OpenAiResponsesModel.ADAPTER_VERSION,
                ModelApiStyles.OPENAI_RESPONSES,
                dialect,
                endpoint,
                new CredentialRef("env://" + credentialEnvironment),
                false,
                capabilities,
                131_072,
                8_192,
                Map.of(),
                Map.of());
    }

    private static boolean enabled(String name) {
        return "true".equalsIgnoreCase(System.getenv(name));
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for explicit live execution");
        }
        return value.trim();
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
