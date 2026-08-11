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

/** Executes a deterministic Model -> Geocode -> Weather -> Model collaboration. */
public final class MultiToolCollaborationExample {
    private MultiToolCollaborationExample() {}

    public static void main(String[] arguments) throws Exception {
        var model = DeterministicExampleSupport.scripted(
                request ->
                        toolCall(request.model().providerModelId(), "geocode-1", "geocode", Map.of("city", "Shanghai")),
                request -> toolCall(
                        request.model().providerModelId(),
                        "weather-1",
                        "weather_at_coordinates",
                        Map.of("latitude", 31.2304, "longitude", 121.4737)),
                request -> new AgentChatResponse(
                        "multi-tool-final",
                        request.model().providerModelId(),
                        "Cloudy in Shanghai, 28°C",
                        List.of(),
                        ModelFinishReason.STOP,
                        ModelUsage.unpriced(3, 3),
                        "",
                        Map.of()));

        try (var agent = HaifaAgentStarter.builder()
                .model(model, DeterministicExampleSupport.snapshot())
                .instructions("Resolve the city first, then request weather for the returned coordinates.")
                .tool(new GeocodeTool())
                .tool(new WeatherAtCoordinatesTool())
                .build()) {
            var conversation = agent.conversations()
                    .start(new StartConversationCommand(
                            "multi-tool-start", "Multi-tool weather", "What is the weather in Shanghai?"));
            System.out.println(agent.runs()
                    .await(conversation.activeRunId().orElseThrow())
                    .output()
                    .orElseThrow());
        }
    }

    private static AgentChatResponse toolCall(
            String providerModelId, String correlationId, String alias, Map<String, Object> arguments) {
        return new AgentChatResponse(
                correlationId,
                providerModelId,
                "",
                List.of(new ModelToolCall(new ProviderToolCallCorrelationId(correlationId), alias, arguments)),
                ModelFinishReason.TOOL_CALLS,
                ModelUsage.unpriced(2, 1),
                "",
                Map.of());
    }

    public record CityRequest(String city) {}

    public record Coordinates(double latitude, double longitude) {}

    public record CoordinateRequest(double latitude, double longitude) {}

    public record Forecast(String text) {}

    private static final class GeocodeTool implements JavaTool<CityRequest, Coordinates> {
        private static final JavaToolSpec<CityRequest, Coordinates> SPEC = JavaToolSpec.builder(
                        "location.geocode", CityRequest.class, Coordinates.class)
                .alias("geocode")
                .description("Resolve a city to deterministic example coordinates")
                .pure()
                .build();

        @Override
        public JavaToolSpec<CityRequest, Coordinates> spec() {
            return SPEC;
        }

        @Override
        public Coordinates invoke(CityRequest input, JavaToolContext context) {
            return new Coordinates(31.2304, 121.4737);
        }
    }

    private static final class WeatherAtCoordinatesTool implements JavaTool<CoordinateRequest, Forecast> {
        private static final JavaToolSpec<CoordinateRequest, Forecast> SPEC = JavaToolSpec.builder(
                        "weather.at-coordinates", CoordinateRequest.class, Forecast.class)
                .alias("weather_at_coordinates")
                .description("Return deterministic example weather for coordinates")
                .pure()
                .build();

        @Override
        public JavaToolSpec<CoordinateRequest, Forecast> spec() {
            return SPEC;
        }

        @Override
        public Forecast invoke(CoordinateRequest input, JavaToolContext context) {
            return new Forecast("Cloudy at " + input.latitude() + "," + input.longitude());
        }
    }
}
