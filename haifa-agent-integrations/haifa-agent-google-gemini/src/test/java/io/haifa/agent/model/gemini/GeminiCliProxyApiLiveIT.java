package io.haifa.agent.model.gemini;

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
import io.haifa.agent.model.api.ModelToolSpecification;
import io.haifa.agent.model.api.ResolvedCredential;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Explicit opt-in live smoke. It never reads CLIProxyAPI auth/config files or prints provider payloads. */
class GeminiCliProxyApiLiveIT {
    private GeminiGenerateContentModel model;
    private ResolvedModelSnapshot snapshot;

    @BeforeEach
    void configure() {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("HAIFA_CLIPROXYAPI_ANTIGRAVITY_LIVE_TEST")));
        String endpoint = required("HAIFA_CLIPROXYAPI_ENDPOINT");
        String key = required("HAIFA_CLIPROXYAPI_API_KEY");
        String providerModel = required("HAIFA_CLIPROXYAPI_MODEL");
        snapshot = ResolvedModelSnapshot.create(
                new ModelProviderId("cliproxyapi-antigravity"),
                "live-v1",
                new ModelDefinitionId("cliproxyapi-gemini-live"),
                "live-v1",
                providerModel,
                ModelApiStyles.GOOGLE_GEMINI_ADAPTER,
                GeminiGenerateContentModel.ADAPTER_VERSION,
                ModelApiStyles.GOOGLE_GEMINI_GENERATE_CONTENT,
                GeminiDialects.CLIPROXYAPI_ANTIGRAVITY,
                URI.create(endpoint),
                new CredentialRef(GeminiGenerateContentModel.CLIPROXY_CREDENTIAL_REF),
                true,
                EnumSet.of(
                        ModelCapability.TEXT_CHAT,
                        ModelCapability.TOOL_CALLING,
                        ModelCapability.STRUCTURED_OUTPUT,
                        ModelCapability.REASONING),
                131072,
                8192,
                Map.of(),
                Map.of());
        model = new GeminiGenerateContentModel(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                new ObjectMapper(),
                reference -> new ResolvedCredential(key),
                true,
                4 * 1024 * 1024);
    }

    @Test
    void completesBoundedTextCall() {
        var response = model.invoke(request(
                "live-text",
                List.of(
                        ModelMessage.text(ModelMessageRole.SYSTEM, "Return only the requested token."),
                        ModelMessage.text(ModelMessageRole.USER, "Return exactly HAIFA_GEMINI_LIVE_OK.")),
                List.of()));
        assertThat(response.content()).contains("HAIFA_GEMINI_LIVE_OK");
        assertThat(response.usage().inputTokens()).isPositive();
    }

    @Test
    void completesSignedTwoTurnToolContinuation() {
        ModelToolSpecification tool = new ModelToolSpecification(
                "haifa_live_echo",
                "1",
                "Return the supplied value",
                "haifa-live-echo",
                "1",
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of("value", Map.of("type", "string")),
                        "required",
                        List.of("value")),
                true);
        var first = model.invoke(request(
                "live-tool-1",
                List.of(
                        ModelMessage.text(
                                ModelMessageRole.USER,
                                "You must call haifa_live_echo exactly once with value HAIFA_TOOL_OK. Do not answer directly.")),
                List.of(tool)));
        assertThat(first.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.name()).isEqualTo("haifa_live_echo");
            assertThat(call.arguments()).containsEntry("value", "HAIFA_TOOL_OK");
        });
        assertThat(first.reasoning()).isPresent();
        var assistant = ModelMessage.assistant(
                first.content(), first.toolCalls(), first.reasoning().orElseThrow());
        var toolResult = ModelMessage.tool(
                first.toolCalls().getFirst().providerCorrelationId(),
                "HAIFA_TOOL_OK",
                Map.of("value", "HAIFA_TOOL_OK"),
                false);
        var second = model.invoke(request(
                "live-tool-2",
                List.of(
                        ModelMessage.text(
                                ModelMessageRole.USER,
                                "You must call haifa_live_echo exactly once with value HAIFA_TOOL_OK. Do not answer directly."),
                        assistant,
                        toolResult),
                List.of(tool)));
        assertThat(second.content()).isNotBlank();
    }

    private AgentChatRequest request(String callId, List<ModelMessage> messages, List<ModelToolSpecification> tools) {
        return new AgentChatRequest(
                new ModelCallId(callId),
                new AgentRunId("run-" + callId),
                1,
                1,
                snapshot,
                messages,
                tools,
                1024,
                Duration.ofSeconds(60),
                Map.of());
    }

    private static String required(String name) {
        String value = System.getenv(name);
        Assumptions.assumeTrue(value != null && !value.isBlank(), name + " is required");
        return value;
    }
}
