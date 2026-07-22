package io.haifa.agent.model.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelCallId;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelMessage;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelReferenceKind;
import io.haifa.agent.model.api.ResolvedCredential;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class VolcengineArkLiveIT {
    @Test
    void invokesExistingArkBindingWhenExplicitlyEnabled() {
        boolean enabled = "true".equalsIgnoreCase(System.getenv("HAIFA_ARK_LIVE_TEST"));
        String apiKey = System.getenv("ARK_API_KEY");
        String baseUrl = System.getenv("HAIFA_ARK_BASE_URL");
        String modelId = System.getenv("HAIFA_ARK_MODEL_ID");
        String kind = System.getenv("HAIFA_ARK_MODEL_REFERENCE_KIND");
        Assumptions.assumeTrue(enabled
                && apiKey != null
                && !apiKey.isBlank()
                && baseUrl != null
                && !baseUrl.isBlank()
                && modelId != null
                && !modelId.isBlank()
                && kind != null
                && !kind.isBlank());
        URI endpoint = URI.create(baseUrl);
        var provider = VolcengineArkProviderFactory.provider(
                new VolcengineArkProviderFactory.ProviderConfiguration(
                        "live-v1",
                        endpoint,
                        System.getenv().getOrDefault("HAIFA_ARK_REGION", "cn-beijing"),
                        endpoint.getHost(),
                        new CredentialRef("env://ARK_API_KEY")),
                List.of(new VolcengineArkProviderFactory.ModelProfile(
                        new ModelDefinitionId("ark-live"),
                        "live-v1",
                        modelId,
                        "Ark Live Binding",
                        ModelReferenceKind.valueOf(kind),
                        EnumSet.of(ModelCapability.TEXT_CHAT),
                        32_768,
                        64,
                        Map.of("thinking_profile", "none", "thinking_enabled", false))));
        var definition = provider.models().getFirst();
        var adapter = new OpenAiCompatibleChatModel(
                provider,
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                new ObjectMapper(),
                ignored -> new ResolvedCredential(apiKey));
        var snapshot = ResolvedModelSnapshot.create(
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
                definition.maxOutputTokens(),
                provider.options(),
                definition.options());

        var result = adapter.invoke(new AgentChatRequest(
                new ModelCallId("ark-live-call"),
                new AgentRunId("ark-live-run"),
                1,
                1,
                snapshot,
                List.of(ModelMessage.text(ModelMessageRole.USER, "Reply with the single word OK.")),
                List.of(),
                64,
                Duration.ofSeconds(60),
                Map.of()));

        assertThat(result.content()).isNotBlank();
    }
}
