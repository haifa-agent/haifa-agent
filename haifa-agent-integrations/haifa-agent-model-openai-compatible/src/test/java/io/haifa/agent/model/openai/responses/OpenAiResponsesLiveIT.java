package io.haifa.agent.model.openai.responses;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelCallId;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelMessage;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ModelStreamControl;
import io.haifa.agent.model.api.ModelStreamEvent;
import io.haifa.agent.model.api.ModelToolSpecification;
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
class OpenAiResponsesLiveIT {
    private static final String DEEPSEEK_TOOL_RESULT_MARKER = "DEEPSEEK_RESPONSES_TOOL_OK_7319";

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
    void closesDeepSeekToolCallAndResultWhenExplicitlyEnabled() {
        Assumptions.assumeTrue(enabled("HAIFA_DEEPSEEK_RESPONSES_LIVE_TEST"));
        requireEnvironment("DEEPSEEK_API_KEY");
        String providerModelId = environment("HAIFA_DEEPSEEK_RESPONSES_MODEL_ID", "deepseek-v4-flash");
        var snapshot = snapshot(
                "deepseek",
                providerModelId,
                URI.create("https://api.deepseek.com"),
                "DEEPSEEK_API_KEY",
                OpenAiResponsesDialects.DEEPSEEK,
                Set.of(
                        ModelCapability.TEXT_CHAT,
                        ModelCapability.TOOL_CALLING,
                        ModelCapability.STRUCTURED_OUTPUT,
                        ModelCapability.REASONING));

        assertToolRoundTrip(model(false), snapshot, DEEPSEEK_TOOL_RESULT_MARKER);
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

    @Test
    void streamsTrustedLoopbackResponsesWhenExplicitlyEnabled() {
        Assumptions.assumeTrue(enabled("HAIFA_OPENAI_RESPONSES_LIVE_TEST"));
        String baseUrl = requireEnvironment("OPENAI_BASE_URL");
        requireEnvironment("OPENAI_API_KEY");
        String providerModelId = requireEnvironment("OPENAI_MODEL_ID");
        var events = new ArrayList<ModelStreamEvent>();
        var response = model(true)
                .invokeStreaming(
                        request(snapshot(
                                "local-openai",
                                providerModelId,
                                URI.create(baseUrl),
                                "OPENAI_API_KEY",
                                OpenAiResponsesDialects.STANDARD,
                                Set.of(ModelCapability.TEXT_CHAT),
                                true)),
                        event -> {
                            events.add(event);
                            return ModelStreamControl.CONTINUE;
                        });

        assertThat(response.responseId()).isNotBlank();
        assertThat(response.content()).isNotBlank();
        assertThat(response.usage().outputTokens()).isPositive();
        assertThat(events).anyMatch(ModelStreamEvent.ContentDelta.class::isInstance);
        assertThat(events).anyMatch(ModelStreamEvent.UsageReported.class::isInstance);
    }

    @Test
    void confirmsTrustedLoopbackDoesNotExposeFunctionToolsWhenExplicitlyEnabled() {
        Assumptions.assumeTrue(enabled("HAIFA_OPENAI_RESPONSES_TOOL_LIVE_TEST"));
        String baseUrl = requireEnvironment("OPENAI_BASE_URL");
        requireEnvironment("OPENAI_API_KEY");
        String providerModelId = requireEnvironment("OPENAI_MODEL_ID");
        var snapshot = snapshot(
                "local-openai",
                providerModelId,
                URI.create(baseUrl),
                "OPENAI_API_KEY",
                OpenAiResponsesDialects.STANDARD,
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING));

        var response = model(true)
                .invoke(request(
                        snapshot, toolPromptMessages(), List.of(weatherTool()), Map.of("tool_choice", "required")));

        assertThat(response.finishReason()).isEqualTo(ModelFinishReason.STOP);
        assertThat(response.toolCalls()).isEmpty();
        assertThat(response.content()).isNotBlank();
        assertThat(response.usage().inputTokens() + response.usage().outputTokens())
                .isGreaterThan(0);
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
        return request(
                snapshot,
                List.of(ModelMessage.text(
                        ModelMessageRole.USER,
                        "Check whether 17 multiplied by 19 is greater than 300, then finish with the word OK.")),
                List.of());
    }

    private static AgentChatRequest request(
            ResolvedModelSnapshot snapshot, List<ModelMessage> messages, List<ModelToolSpecification> tools) {
        return request(snapshot, messages, tools, Map.of());
    }

    private static AgentChatRequest request(
            ResolvedModelSnapshot snapshot,
            List<ModelMessage> messages,
            List<ModelToolSpecification> tools,
            Map<String, Object> options) {
        return new AgentChatRequest(
                new ModelCallId("responses-live-call"),
                new AgentRunId("responses-live-run"),
                1,
                1,
                snapshot,
                messages,
                tools,
                2_048,
                Duration.ofSeconds(60),
                options);
    }

    private static void assertToolRoundTrip(OpenAiResponsesModel model, ResolvedModelSnapshot snapshot, String marker) {
        var tool = weatherTool();
        var initialMessages = toolPromptMessages();

        AgentChatResponse first = model.invoke(request(snapshot, initialMessages, List.of(tool)));

        assertThat(first.finishReason()).isEqualTo(ModelFinishReason.TOOL_CALLS);
        assertThat(first.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.name()).isEqualTo("lookup_weather");
            assertThat(call.arguments()).containsKey("city");
        });

        var toolCall = first.toolCalls().getFirst();
        var followUpMessages = new ArrayList<>(initialMessages);
        followUpMessages.add(assistantMessage(first));
        followUpMessages.add(ModelMessage.tool(
                toolCall.providerCorrelationId(),
                marker,
                Map.of("verificationCode", marker, "condition", "sunny"),
                false));

        AgentChatResponse completed = model.invoke(request(snapshot, followUpMessages, List.of(tool)));

        assertThat(completed.toolCalls()).isEmpty();
        assertThat(completed.content()).contains(marker);
        assertThat(completed.usage().inputTokens() + completed.usage().outputTokens())
                .isGreaterThan(0);
    }

    private static List<ModelMessage> toolPromptMessages() {
        return List.of(
                ModelMessage.text(
                        ModelMessageRole.SYSTEM,
                        "Call lookup_weather when weather is requested. After receiving a tool result, reply with exactly its verificationCode."),
                ModelMessage.text(
                        ModelMessageRole.USER,
                        "Use lookup_weather for Shanghai. Do not answer from memory; call the tool exactly once."));
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

    private static ResolvedModelSnapshot snapshot(
            String providerId,
            String providerModelId,
            URI endpoint,
            String credentialEnvironment,
            String dialect,
            Set<ModelCapability> capabilities) {
        return snapshot(providerId, providerModelId, endpoint, credentialEnvironment, dialect, capabilities, false);
    }

    private static ResolvedModelSnapshot snapshot(
            String providerId,
            String providerModelId,
            URI endpoint,
            String credentialEnvironment,
            String dialect,
            Set<ModelCapability> capabilities,
            boolean nativeStreaming) {
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
                nativeStreaming,
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
