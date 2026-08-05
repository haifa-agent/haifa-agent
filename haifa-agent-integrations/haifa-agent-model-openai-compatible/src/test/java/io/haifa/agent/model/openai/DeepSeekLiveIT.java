package io.haifa.agent.model.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.model.api.AgentChatRequest;
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
    @Test
    void validatesV4ProThinkingWhenExplicitlyEnabled() {
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
                provider.adapterType(),
                "1.0.0",
                provider.endpoint(),
                provider.credentialRef(),
                definition.capabilities(),
                definition.contextWindow(),
                256,
                provider.options(),
                definition.options());

        var response = model.invoke(new AgentChatRequest(
                new ModelCallId("live-call"),
                new AgentRunId("live-run"),
                1,
                1,
                snapshot,
                List.of(ModelMessage.text(ModelMessageRole.USER, "Reply with exactly CP01_OK.")),
                List.of(),
                256,
                Duration.ofSeconds(60),
                Map.of()));

        assertThat(response.content()).contains("CP01_OK");
        assertThat(response.reasoning()).isPresent();
        assertThat(response.metadata()).containsKey("reasoningCharacters");
        assertThat((Integer) response.metadata().get("reasoningCharacters")).isPositive();
        int reasoningCharacters = response.reasoning().orElseThrow().use(reasoning -> {
            assertThat(response.toString()).doesNotContain(reasoning);
            return reasoning.length();
        });
        assertThat(reasoningCharacters).isPositive();
        assertThat(response.usage().inputTokens() + response.usage().outputTokens())
                .isGreaterThan(0);
    }

    @Test
    void validatesV4ProThinkingToolContinuationWhenExplicitlyEnabled() {
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
                provider.adapterType(),
                "1.0.0",
                provider.endpoint(),
                provider.credentialRef(),
                definition.capabilities(),
                definition.contextWindow(),
                512,
                provider.options(),
                definition.options());
        ModelToolSpecification echo = new ModelToolSpecification(
                "echo",
                "1.0.0",
                "Return the supplied text unchanged",
                "echo.input",
                "1.0",
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of("text", Map.of("type", "string")),
                        "required",
                        List.of("text"),
                        "additionalProperties",
                        false),
                false);
        String prompt = "Call the echo tool exactly once with text continuation-ok, then report its result.";

        var first = model.invoke(new AgentChatRequest(
                new ModelCallId("live-thinking-tool-call-1"),
                new AgentRunId("live-thinking-tool-run"),
                1,
                1,
                snapshot,
                List.of(ModelMessage.text(ModelMessageRole.USER, prompt)),
                List.of(echo),
                512,
                Duration.ofSeconds(60),
                Map.of()));

        assertThat(first.finishReason()).isEqualTo(ModelFinishReason.TOOL_CALLS);
        assertThat(first.reasoning()).isPresent();
        assertThat(first.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.name()).isEqualTo("echo");
            assertThat(call.arguments()).containsEntry("text", "continuation-ok");
        });
        var toolCall = first.toolCalls().getFirst();
        var second = model.invoke(new AgentChatRequest(
                new ModelCallId("live-thinking-tool-call-2"),
                new AgentRunId("live-thinking-tool-run"),
                1,
                2,
                snapshot,
                List.of(
                        ModelMessage.text(ModelMessageRole.USER, prompt),
                        ModelMessage.assistant(
                                first.content(),
                                first.toolCalls(),
                                first.reasoning().orElseThrow()),
                        ModelMessage.tool(
                                toolCall.providerCorrelationId(),
                                "continuation-ok",
                                Map.of("text", "continuation-ok"),
                                false)),
                List.of(echo),
                512,
                Duration.ofSeconds(60),
                Map.of()));

        assertThat(second.finishReason()).isEqualTo(ModelFinishReason.STOP);
        assertThat(second.content()).contains("continuation-ok");
        assertThat(second.usage().inputTokens() + second.usage().outputTokens()).isGreaterThan(0);
    }
}
