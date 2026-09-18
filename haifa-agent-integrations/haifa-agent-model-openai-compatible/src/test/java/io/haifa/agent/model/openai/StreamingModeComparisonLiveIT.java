package io.haifa.agent.model.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelApiBindingDefinition;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelCallId;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinition;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelMessage;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelProviderDefinition;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ModelStatus;
import io.haifa.agent.model.api.ModelStreamControl;
import io.haifa.agent.model.api.ModelStreamEvent;
import io.haifa.agent.model.api.ProviderStatus;
import io.haifa.agent.model.api.ResolvedCredential;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("live")
class StreamingModeComparisonLiveIT {
    private static final String SILICONFLOW_MODEL = "deepseek-ai/DeepSeek-V4-Flash";
    private static final String DEEPSEEK_MODEL = "deepseek-v4-flash";

    @Test
    void comparesSiliconFlowAndDeepSeekWithNativeStreamingEnabledAndDisabled() {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("HAIFA_STREAMING_MODE_COMPARISON_LIVE_TEST")));
        String siliconFlowKey = requiredSecret("SILICONFLOW_API_KEY");
        String deepSeekKey = requiredSecret("DEEPSEEK_API_KEY");

        verify("siliconflow", true, siliconFlowKey);
        verify("siliconflow", false, siliconFlowKey);
        verify("deepseek", true, deepSeekKey);
        verify("deepseek", false, deepSeekKey);
    }

    private static void verify(String providerId, boolean nativeStreaming, String apiKey) {
        ModelProviderDefinition provider = provider(providerId, nativeStreaming);
        var adapter = new OpenAiCompatibleChatModel(
                provider,
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                new ObjectMapper(),
                ignored -> new ResolvedCredential(apiKey));
        List<ModelStreamEvent> events = new ArrayList<>();
        String marker = "siliconflow".equals(providerId) ? "SF_MATRIX_OK" : "DS_MATRIX_OK";

        var response = adapter.invokeStreaming(request(snapshot(provider), marker), event -> {
            events.add(event);
            return ModelStreamControl.CONTINUE;
        });

        long contentDeltas = events.stream()
                .filter(ModelStreamEvent.ContentDelta.class::isInstance)
                .count();
        long usageEvents = events.stream()
                .filter(ModelStreamEvent.UsageReported.class::isInstance)
                .count();
        assertThat(response.content()).contains(marker);
        assertThat(response.usage().inputTokens() + response.usage().outputTokens())
                .isGreaterThan(0);
        assertThat(contentDeltas).isGreaterThan(0);
        assertThat(usageEvents).isEqualTo(1);
        System.out.printf(
                "LIVE_MATRIX provider=%s nativeStreaming=%s contentDeltas=%d usageEvents=%d inputTokens=%d outputTokens=%d%n",
                providerId,
                nativeStreaming,
                contentDeltas,
                usageEvents,
                response.usage().inputTokens(),
                response.usage().outputTokens());
    }

    private static ModelProviderDefinition provider(String providerId, boolean nativeStreaming) {
        boolean siliconFlow = "siliconflow".equals(providerId);
        URI endpoint = URI.create(siliconFlow ? "https://api.siliconflow.cn/v1" : "https://api.deepseek.com");
        String dialect = siliconFlow ? OpenAiCompatibleDialects.SILICONFLOW : OpenAiCompatibleDialects.DEEPSEEK;
        String providerModel = siliconFlow ? SILICONFLOW_MODEL : DEEPSEEK_MODEL;
        ModelProviderId id = new ModelProviderId(providerId);
        Map<String, Object> providerOptions = siliconFlow
                ? OpenAiCompatibleDialects.configuredOptions(dialect, endpoint)
                : Map.of("thinking", "disabled");
        Map<String, Object> modelOptions = siliconFlow ? Map.of() : Map.of("thinking", "disabled");
        ModelDefinition model = new ModelDefinition(
                new ModelDefinitionId(providerId + "-streaming-matrix"),
                "model-v1",
                id,
                providerModel,
                providerId + " streaming matrix",
                ModelStatus.ACTIVE,
                EnumSet.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING),
                1_000_000,
                2_048,
                modelOptions,
                Map.of(),
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS);
        return new ModelProviderDefinition(
                id,
                "provider-v1",
                providerId,
                endpoint,
                new CredentialRef(siliconFlow ? "env://SILICONFLOW_API_KEY" : "env://DEEPSEEK_API_KEY"),
                nativeStreaming,
                ProviderStatus.ACTIVE,
                List.of(new ModelApiBindingDefinition(ModelApiStyles.OPENAI_CHAT_COMPLETIONS, dialect)),
                List.of(model),
                providerOptions,
                Map.of());
    }

    private static ResolvedModelSnapshot snapshot(ModelProviderDefinition provider) {
        ModelDefinition model = provider.models().getFirst();
        return ResolvedModelSnapshot.create(
                provider.id(),
                provider.version(),
                model.id(),
                model.version(),
                model.providerModelId(),
                ModelApiStyles.OPENAI_CHAT_ADAPTER,
                "1.0.0",
                model.style(),
                provider.binding(model.style()).dialect(),
                provider.endpoint(),
                provider.credentialRef(),
                provider.nativeStreaming(),
                model.capabilities(),
                model.contextWindow(),
                model.maxOutputTokens(),
                provider.options(),
                model.options());
    }

    private static AgentChatRequest request(ResolvedModelSnapshot snapshot, String marker) {
        return new AgentChatRequest(
                new ModelCallId(snapshot.providerId().value() + "-" + snapshot.nativeStreaming()),
                new AgentRunId("streaming-mode-comparison"),
                1,
                1,
                snapshot,
                List.of(ModelMessage.text(
                        ModelMessageRole.USER, "Reply with exactly " + marker + " and no other text.")),
                List.of(),
                128,
                Duration.ofSeconds(90),
                Map.of());
    }

    private static String requiredSecret(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for explicit comparison live execution");
        }
        return value;
    }
}
