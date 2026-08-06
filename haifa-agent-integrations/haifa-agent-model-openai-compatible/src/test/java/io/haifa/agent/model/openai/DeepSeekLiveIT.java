package io.haifa.agent.model.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelCallId;
import io.haifa.agent.model.api.ModelMessage;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ResolvedCredential;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("live")
class DeepSeekLiveIT {
    @Test
    void validatesPrimaryModelConnectivityWhenExplicitlyEnabled() {
        boolean enabled = "true".equalsIgnoreCase(System.getenv("HAIFA_DEEPSEEK_LIVE_TEST"))
                || "true".equalsIgnoreCase(System.getenv("HAIFA_SUITE_EXECUTION"));
        Assumptions.assumeTrue(enabled);
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("DEEPSEEK_API_KEY is required for explicit live execution");
        }
        var provider = DeepSeekDefaults.provider();
        var model = new OpenAiCompatibleChatModel(
                provider,
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                new ObjectMapper(),
                ignored -> new ResolvedCredential(apiKey));
        var definition = provider.models().getFirst();
        ResolvedModelSnapshot snapshot = ResolvedModelSnapshot.create(
                provider.id(),
                provider.version(),
                definition.id(),
                definition.version(),
                definition.providerModelId(),
                ModelApiStyles.adapterType(definition.style()),
                "1.0.0",
                definition.style(),
                provider.binding(definition.style()).dialect(),
                provider.endpoint(),
                provider.credentialRef(),
                provider.nativeStreaming(),
                definition.capabilities(),
                definition.contextWindow(),
                64,
                provider.options(),
                Map.of("thinking", "disabled"));

        var response = model.invoke(new AgentChatRequest(
                new ModelCallId("live-call"),
                new AgentRunId("live-run"),
                1,
                1,
                snapshot,
                List.of(ModelMessage.text(ModelMessageRole.USER, "Reply with exactly CP01_OK.")),
                List.of(),
                64,
                Duration.ofSeconds(60),
                Map.of()));

        assertThat(response.content()).contains("CP01_OK");
        assertThat(response.usage().inputTokens() + response.usage().outputTokens())
                .isGreaterThan(0);
    }
}
