package io.haifa.example.sdk.intermediate;

import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.sdk.conversation.StartConversationCommand;
import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import io.haifa.agent.starter.HaifaAgentStarter;
import io.haifa.example.sdk.support.DeterministicExampleSupport;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Executes a public-record Java Tool through the complete Model -> Tool -> Model loop. */
public final class TypedJavaToolExample {
    private TypedJavaToolExample() {}

    public static void main(String[] args) throws Exception {
        var calls = new AtomicInteger();
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
                .model(model, DeterministicExampleSupport.snapshot())
                .tool(new WeatherTool())
                .build()) {
            var conversation = agent.conversations()
                    .start(new StartConversationCommand("tool-start", "Weather", "Weather in Shanghai?"));
            System.out.println(agent.runs()
                    .await(conversation.activeRunId().orElseThrow())
                    .output()
                    .orElseThrow());
        }
    }

    public record WeatherRequest(String city) {}

    public record WeatherResponse(String forecast) {}

    private static final class WeatherTool implements JavaTool<WeatherRequest, WeatherResponse> {
        private static final JavaToolSpec<WeatherRequest, WeatherResponse> SPEC = JavaToolSpec.builder(
                        "weather.get", WeatherRequest.class, WeatherResponse.class)
                .alias("weather_get")
                .description("Get the current weather for a city")
                .pure()
                .build();

        @Override
        public JavaToolSpec<WeatherRequest, WeatherResponse> spec() {
            return SPEC;
        }

        @Override
        public WeatherResponse invoke(WeatherRequest input, JavaToolContext context) {
            return new WeatherResponse("Sunny in " + input.city());
        }
    }
}
