package io.haifa.agent.testing.live;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.ModelCallId;
import io.haifa.agent.model.api.ModelMessage;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ResolvedCredential;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.model.openai.DeepSeekDefaults;
import io.haifa.agent.model.openai.OpenAiCompatibleChatModel;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("live")
class PrimaryModelLiveIT {
    @Test
    void connectsPrimaryModel() {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("HAIFA_SUITE_EXECUTION")));
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("DEEPSEEK_API_KEY is required for explicit suite execution");
        }
        var provider = DeepSeekDefaults.provider();
        var definition = provider.models().getFirst();
        var model = new OpenAiCompatibleChatModel(
                provider,
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                new ObjectMapper(),
                ignored -> new ResolvedCredential(apiKey));
        ResolvedModelSnapshot snapshot = ResolvedModelSnapshot.create(
                provider.id(),
                provider.version(),
                definition.id(),
                definition.version(),
                definition.providerModelId(),
                provider.adapterType(),
                "1.0.0",
                provider.endpoint(),
                provider.credentialRef(),
                definition.capabilities(),
                definition.contextWindow(),
                64,
                provider.options(),
                Map.of("thinking", "disabled"));

        var response = model.invoke(new AgentChatRequest(
                new ModelCallId("cp-01-call"),
                new AgentRunId("cp-01-run"),
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
