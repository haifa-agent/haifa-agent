package io.haifa.example.sdk.intermediate;

import io.haifa.agent.sdk.tool.JavaTool;
import io.haifa.agent.sdk.tool.JavaToolContext;
import io.haifa.agent.sdk.tool.JavaToolSpec;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Demonstrates the bounded nested Java record shapes supported by typed Tools. */
public final class TripPlanTool implements JavaTool<TripPlanTool.Request, TripPlanTool.Response> {
    public enum TemperatureUnit {
        CELSIUS,
        FAHRENHEIT
    }

    public record Preferences(TemperatureUnit temperatureUnit, Set<String> alerts) {}

    public record Request(
            String destination,
            LocalDate travelDate,
            Optional<Preferences> preferences,
            List<String> interests,
            Map<String, Integer> partyByAgeGroup) {}

    public record Stop(String name, int recommendedMinutes) {}

    public record Response(String destination, LocalDate travelDate, List<Stop> stops, Map<String, String> notes) {}

    private static final JavaToolSpec<Request, Response> SPEC = JavaToolSpec.builder(
                    "travel.plan", Request.class, Response.class)
            .alias("trip_plan")
            .description("Build a deterministic itinerary from structured preferences")
            .pure()
            .build();

    @Override
    public JavaToolSpec<Request, Response> spec() {
        return SPEC;
    }

    @Override
    public Response invoke(Request input, JavaToolContext context) {
        return new Response(
                input.destination(),
                input.travelDate(),
                List.of(new Stop("West Lake", 120), new Stop("Longjing Tea Village", 90)),
                Map.of(
                        "party", input.partyByAgeGroup().toString(),
                        "interests", String.join(", ", input.interests())));
    }
}
