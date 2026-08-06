package io.haifa.agent.model.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelCallId;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelMessage;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelToolSpecification;
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
    private static final String TOOL_RESULT_MARKER = "DEEPSEEK_CHAT_TOOL_OK_7319";

    @Test
    void validatesPrimaryModelConnectivityWhenExplicitlyEnabled() {
        String apiKey = requireLiveExecution();
        var response = model(apiKey)
                .invoke(request(
                        snapshot(),
                        List.of(ModelMessage.text(ModelMessageRole.USER, "Reply with exactly CP01_OK.")),
                        List.of()));

        assertThat(response.content()).contains("CP01_OK");
        assertThat(response.usage().inputTokens() + response.usage().outputTokens())
                .isGreaterThan(0);
    }

    @Test
    void closesToolCallAndResultWhenExplicitlyEnabled() {
        String apiKey = requireLiveExecution();
        var model = model(apiKey);
        var snapshot = snapshot();
        var tool = weatherTool();
        var initialMessages = List.of(
                ModelMessage.text(
                        ModelMessageRole.SYSTEM,
                        "Call lookup_weather when weather is requested. After receiving a tool result, reply with exactly its verificationCode."),
                ModelMessage.text(
                        ModelMessageRole.USER,
                        "Use lookup_weather for Shanghai. Do not answer from memory; call the tool exactly once."));

        AgentChatResponse first = model.invoke(request(snapshot, initialMessages, List.of(tool)));

        assertThat(first.finishReason()).isEqualTo(ModelFinishReason.TOOL_CALLS);
        assertThat(first.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.name()).isEqualTo("lookup_weather");
            assertThat(call.arguments()).containsKey("city");
        });

        var toolCall = first.toolCalls().getFirst();
        var followUpMessages = new java.util.ArrayList<>(initialMessages);
        followUpMessages.add(assistantMessage(first));
        followUpMessages.add(ModelMessage.tool(
                toolCall.providerCorrelationId(),
                TOOL_RESULT_MARKER,
                Map.of("verificationCode", TOOL_RESULT_MARKER, "condition", "sunny"),
                false));

        AgentChatResponse completed = model.invoke(request(snapshot, followUpMessages, List.of(tool)));

        assertThat(completed.toolCalls()).isEmpty();
        assertThat(completed.content()).contains(TOOL_RESULT_MARKER);
        assertThat(completed.usage().inputTokens() + completed.usage().outputTokens())
                .isGreaterThan(0);
    }

    private static String requireLiveExecution() {
        boolean enabled = "true".equalsIgnoreCase(System.getenv("HAIFA_DEEPSEEK_LIVE_TEST"))
                || "true".equalsIgnoreCase(System.getenv("HAIFA_SUITE_EXECUTION"));
        Assumptions.assumeTrue(enabled);
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("DEEPSEEK_API_KEY is required for explicit live execution");
        }
        return apiKey;
    }

    private static OpenAiCompatibleChatModel model(String apiKey) {
        return new OpenAiCompatibleChatModel(
                DeepSeekDefaults.provider(),
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                new ObjectMapper(),
                ignored -> new ResolvedCredential(apiKey));
    }

    private static ResolvedModelSnapshot snapshot() {
        var provider = DeepSeekDefaults.provider();
        var definition = provider.models().getFirst();
        return ResolvedModelSnapshot.create(
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
    }

    private static AgentChatRequest request(
            ResolvedModelSnapshot snapshot, List<ModelMessage> messages, List<ModelToolSpecification> tools) {
        return new AgentChatRequest(
                new ModelCallId("live-call"),
                new AgentRunId("live-run"),
                1,
                1,
                snapshot,
                messages,
                tools,
                64,
                Duration.ofSeconds(60),
                Map.of());
    }

    private static ModelToolSpecification weatherTool() {
        return new ModelToolSpecification(
                "lookup_weather",
                "1.0",
                "Look up weather for one city",
                "weather-input",
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

    private static ModelMessage assistantMessage(AgentChatResponse response) {
        return response.reasoning()
                .map(reasoning -> ModelMessage.assistant(response.content(), response.toolCalls(), reasoning))
                .orElseGet(() -> ModelMessage.assistant(response.content(), response.toolCalls()));
    }
}
