package io.haifa.agent.model.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelApiBindingDefinition;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelCallId;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinition;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelMessage;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelProviderDefinition;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ModelStatus;
import io.haifa.agent.model.api.ModelStreamControl;
import io.haifa.agent.model.api.ModelStreamEvent;
import io.haifa.agent.model.api.ModelToolSpecification;
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
class TokenRhythmLiveIT {
    private static final String PROVIDER_MODEL = "deepseek-ai/DeepSeek-V4-Flash";
    private static final String TOOL_RESULT_MARKER = "TOKENRHYTHM_TOOL_OK_8421";

    @Test
    void streamsContentAndPublishesOneFinalUsageWhenExplicitlyEnabled() {
        String apiKey = requireLiveExecution();
        List<ModelStreamEvent> events = new ArrayList<>();

        var response = model(apiKey)
                .invokeStreaming(
                        request(
                                List.of(ModelMessage.text(
                                        ModelMessageRole.USER,
                                        "Reply with one short sentence containing TOKENRHYTHM_STREAM_OK.")),
                                List.of()),
                        event -> {
                            events.add(event);
                            return ModelStreamControl.CONTINUE;
                        });

        assertThat(response.content()).contains("TOKENRHYTHM_STREAM_OK");
        assertThat(response.usage().inputTokens() + response.usage().outputTokens())
                .isGreaterThan(0);
        assertThat(events).anyMatch(ModelStreamEvent.ContentDelta.class::isInstance);
        assertThat(events)
                .filteredOn(ModelStreamEvent.UsageReported.class::isInstance)
                .hasSize(1);
    }

    @Test
    void completesARealStreamingToolContinuationWhenExplicitlyEnabled() {
        String apiKey = requireLiveExecution();
        var model = model(apiKey);
        var tool = verificationTool();
        var initialMessages = List.of(
                ModelMessage.text(
                        ModelMessageRole.SYSTEM,
                        "Call lookup_verification when asked to verify a city. After the tool result, reply with exactly verificationCode."),
                ModelMessage.text(
                        ModelMessageRole.USER, "Verify Shanghai with lookup_verification. Call the tool once."));
        List<ModelStreamEvent> firstEvents = new ArrayList<>();

        AgentChatResponse first = model.invokeStreaming(request(initialMessages, List.of(tool)), event -> {
            firstEvents.add(event);
            return ModelStreamControl.CONTINUE;
        });

        assertThat(first.finishReason()).isEqualTo(ModelFinishReason.TOOL_CALLS);
        assertThat(first.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.name()).isEqualTo("lookup_verification");
            assertThat(call.arguments()).containsKey("city");
        });
        assertThat(firstEvents)
                .filteredOn(ModelStreamEvent.UsageReported.class::isInstance)
                .hasSize(1);

        var toolCall = first.toolCalls().getFirst();
        var continuation = new ArrayList<>(initialMessages);
        continuation.add(ModelMessage.assistant(first.content(), first.toolCalls()));
        continuation.add(ModelMessage.tool(
                toolCall.providerCorrelationId(),
                TOOL_RESULT_MARKER,
                Map.of("verificationCode", TOOL_RESULT_MARKER),
                false));
        List<ModelStreamEvent> finalEvents = new ArrayList<>();

        AgentChatResponse completed = model.invokeStreaming(request(continuation, List.of(tool)), event -> {
            finalEvents.add(event);
            return ModelStreamControl.CONTINUE;
        });

        assertThat(completed.toolCalls()).isEmpty();
        assertThat(completed.content()).contains(TOOL_RESULT_MARKER);
        assertThat(completed.usage().inputTokens() + completed.usage().outputTokens())
                .isGreaterThan(0);
        assertThat(finalEvents)
                .filteredOn(ModelStreamEvent.UsageReported.class::isInstance)
                .hasSize(1);
    }

    private static String requireLiveExecution() {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("HAIFA_TOKENRHYTHM_LIVE_TEST")));
        String apiKey = System.getenv("TR_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("TR_API_KEY is required for explicit live execution");
        }
        return apiKey;
    }

    private static OpenAiCompatibleChatModel model(String apiKey) {
        return new OpenAiCompatibleChatModel(
                provider(),
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                new ObjectMapper(),
                ignored -> new ResolvedCredential(apiKey));
    }

    private static ModelProviderDefinition provider() {
        URI endpoint = URI.create("https://api.tokenrhythm.com/v1");
        ModelProviderId providerId = new ModelProviderId("tokenrhythm");
        ModelDefinition model = new ModelDefinition(
                new ModelDefinitionId("tokenrhythm-deepseek-v4-flash"),
                "model-v1",
                providerId,
                PROVIDER_MODEL,
                "TokenRhythm DeepSeek V4 Flash",
                ModelStatus.ACTIVE,
                EnumSet.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING),
                1_000_000,
                8_192,
                Map.of(),
                Map.of(),
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS);
        return new ModelProviderDefinition(
                providerId,
                "provider-v1",
                "TokenRhythm",
                endpoint,
                new CredentialRef("env://TR_API_KEY"),
                true,
                ProviderStatus.ACTIVE,
                List.of(new ModelApiBindingDefinition(
                        ModelApiStyles.OPENAI_CHAT_COMPLETIONS, OpenAiCompatibleDialects.TOKENRHYTHM)),
                List.of(model),
                OpenAiCompatibleDialects.configuredOptions(OpenAiCompatibleDialects.TOKENRHYTHM, endpoint),
                Map.of());
    }

    private static ResolvedModelSnapshot snapshot() {
        var provider = provider();
        var model = provider.models().getFirst();
        return ResolvedModelSnapshot.create(
                provider.id(),
                provider.version(),
                model.id(),
                model.version(),
                model.providerModelId(),
                ModelApiStyles.OPENAI_CHAT_ADAPTER,
                "1.0.0",
                model.style(),
                OpenAiCompatibleDialects.TOKENRHYTHM,
                provider.endpoint(),
                provider.credentialRef(),
                true,
                model.capabilities(),
                model.contextWindow(),
                model.maxOutputTokens(),
                provider.options(),
                Map.of());
    }

    private static AgentChatRequest request(List<ModelMessage> messages, List<ModelToolSpecification> tools) {
        return new AgentChatRequest(
                new ModelCallId("tokenrhythm-live-call"),
                new AgentRunId("tokenrhythm-live-run"),
                1,
                1,
                snapshot(),
                messages,
                tools,
                2_048,
                Duration.ofSeconds(60),
                Map.of());
    }

    private static ModelToolSpecification verificationTool() {
        return new ModelToolSpecification(
                "lookup_verification",
                "1.0",
                "Look up a deterministic verification code for one city",
                "verification-input",
                "1.0",
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of("city", Map.of("type", "string")),
                        "required",
                        List.of("city"),
                        "additionalProperties",
                        false),
                false);
    }
}
