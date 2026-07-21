package io.haifa.agent.model.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.model.api.AgentChatRequest;
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
import org.junit.jupiter.api.Test;

class DeepSeekLiveIT {
    @Test
    void invokesDeepSeekWhenExplicitlyEnabled() {
        boolean enabled = "true".equalsIgnoreCase(System.getenv("HAIFA_DEEPSEEK_LIVE_TEST"));
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        Assumptions.assumeTrue(enabled && apiKey != null && !apiKey.isBlank());
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
                new ModelCallId("live-call"),
                new AgentRunId("live-run"),
                1,
                1,
                snapshot,
                List.of(ModelMessage.text(ModelMessageRole.USER, "Reply with the single word OK.")),
                List.of(),
                64,
                Duration.ofSeconds(60),
                Map.of()));

        assertThat(response.content()).isNotBlank();
    }
}
