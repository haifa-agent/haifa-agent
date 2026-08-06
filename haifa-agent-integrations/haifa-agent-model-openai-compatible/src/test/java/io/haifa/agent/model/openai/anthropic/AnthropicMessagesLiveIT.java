package io.haifa.agent.model.openai.anthropic;

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
import io.haifa.agent.model.api.ModelStreamControl;
import io.haifa.agent.model.api.ModelStreamEvent;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.model.openai.EnvironmentCredentialResolver;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("live")
class AnthropicMessagesLiveIT {
    @Test
    void streamsDeepSeekAnthropicMessagesWhenExplicitlyEnabled() {
        Assumptions.assumeTrue(enabled("HAIFA_DEEPSEEK_ANTHROPIC_LIVE_TEST"));
        requireEnvironment("DEEPSEEK_API_KEY");
        String providerModelId = environment("HAIFA_DEEPSEEK_ANTHROPIC_MODEL_ID", "deepseek-v4-flash");
        List<ModelStreamEvent> events = new ArrayList<>();

        var response = model().invokeStreaming(request(snapshot(providerModelId)), event -> {
            events.add(event);
            return ModelStreamControl.CONTINUE;
        });

        assertThat(response.responseId()).isNotBlank();
        assertThat(response.content()).isNotBlank();
        assertThat(response.usage().outputTokens()).isPositive();
        assertThat(events).anyMatch(ModelStreamEvent.ContentDelta.class::isInstance);
        assertThat(events).anyMatch(ModelStreamEvent.UsageReported.class::isInstance);
    }

    private static AnthropicMessagesModel model() {
        return new AnthropicMessagesModel(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                new ObjectMapper(),
                new EnvironmentCredentialResolver(),
                false,
                1024 * 1024);
    }

    private static AgentChatRequest request(ResolvedModelSnapshot snapshot) {
        return new AgentChatRequest(
                new ModelCallId("anthropic-live-call"),
                new AgentRunId("anthropic-live-run"),
                1,
                1,
                snapshot,
                List.of(ModelMessage.text(ModelMessageRole.USER, "Reply with OK.")),
                List.of(),
                16,
                Duration.ofSeconds(60),
                Map.of());
    }

    private static ResolvedModelSnapshot snapshot(String providerModelId) {
        return ResolvedModelSnapshot.create(
                new ModelProviderId("deepseek"),
                "live-v1",
                new ModelDefinitionId("deepseek-anthropic-live"),
                "live-v1",
                providerModelId,
                AnthropicMessagesModel.ADAPTER_TYPE,
                AnthropicMessagesModel.ADAPTER_VERSION,
                ModelApiStyles.ANTHROPIC_MESSAGES,
                AnthropicMessagesDialects.DEEPSEEK,
                URI.create("https://api.deepseek.com/anthropic"),
                new CredentialRef("env://DEEPSEEK_API_KEY"),
                true,
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING, ModelCapability.REASONING),
                131_072,
                8_192,
                Map.of("thinking", "disabled"),
                Map.of("thinking", "disabled"));
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
