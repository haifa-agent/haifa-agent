package io.haifa.smoke;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelApiBindingDefinition;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.sdk.conversation.StartConversationCommand;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import io.haifa.agent.starter.HaifaAgentStarter;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

public class MavenConsumerTest {
    @Test
    void executesConversationAndTypedToolUsingOnlyPublishedArtifacts() throws Exception {
        var calls = new AtomicInteger();
        var toolInput = new AtomicReference<WeatherRequest>();
        var model = (io.haifa.agent.model.api.AgentChatModel) request -> {
            if (calls.incrementAndGet() == 1) {
                return new AgentChatResponse(
                        "tool",
                        request.model().providerModelId(),
                        "",
                        List.of(new ModelToolCall(
                                new ProviderToolCallCorrelationId("weather-1"),
                                "weather_get",
                                Map.of("city", "Shanghai"))),
                        ModelFinishReason.TOOL_CALLS,
                        ModelUsage.unpriced(2, 1),
                        "",
                        Map.of());
            }
            return new AgentChatResponse(
                    "final",
                    request.model().providerModelId(),
                    "Sunny in Shanghai",
                    List.of(),
                    ModelFinishReason.STOP,
                    ModelUsage.unpriced(2, 2),
                    "",
                    Map.of());
        };

        try (var agent = HaifaAgentStarter.builder()
                .model(model, snapshot())
                .tool(new WeatherTool(toolInput))
                .build()) {
            var conversation = agent.conversations()
                    .start(new StartConversationCommand("external-smoke", "Weather", "Weather in Shanghai?"));
            var completed = agent.runs().await(conversation.activeRunId().orElseThrow());

            assertEquals("Sunny in Shanghai", completed.output().orElseThrow());
            assertEquals(new WeatherRequest("Shanghai"), toolInput.get());
            assertEquals(2, calls.get());
        }
    }

    private static ResolvedModelSnapshot snapshot() {
        return ResolvedModelSnapshot.create(
                new ModelProviderId("external-smoke"),
                "1.0.0",
                new ModelDefinitionId("external-smoke-model"),
                "1.0.0",
                "external-smoke-model",
                "external-smoke-adapter",
                "1.0.0",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                ModelApiBindingDefinition.STANDARD_DIALECT,
                URI.create("https://model.invalid"),
                new CredentialRef("env://EXTERNAL_SMOKE_KEY"),
                false,
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING),
                8_192,
                1_024,
                Map.of(),
                Map.of());
    }

    public record WeatherRequest(String city) {}

    public record WeatherResponse(String forecast) {}

    private static final class WeatherTool implements JavaTool<WeatherRequest, WeatherResponse> {
        private static final JavaToolSpec<WeatherRequest, WeatherResponse> SPEC = JavaToolSpec.builder(
                        "weather.get", WeatherRequest.class, WeatherResponse.class)
                .alias("weather_get")
                .pure()
                .build();
        private final AtomicReference<WeatherRequest> input;

        private WeatherTool(AtomicReference<WeatherRequest> input) {
            this.input = input;
        }

        @Override
        public JavaToolSpec<WeatherRequest, WeatherResponse> spec() {
            return SPEC;
        }

        @Override
        public WeatherResponse invoke(WeatherRequest request, JavaToolContext context) {
            input.set(request);
            return new WeatherResponse("Sunny in " + request.city());
        }
    }
}
