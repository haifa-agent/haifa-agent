package io.haifa.example.sdk.intermediate;

import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.starter.HaifaAgentStarter;
import io.haifa.example.sdk.support.DeterministicExampleSupport;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Returns a Java record only after Runtime validates and persists the frozen final-output schema. */
public final class StructuredOutputExample {
    private StructuredOutputExample() {}

    public static void main(String[] arguments) throws Exception {
        Map<String, Object> output =
                Map.of("city", "Shanghai", "days", 2, "activities", List.of("Bund walk", "Shanghai Museum"));
        var model = (io.haifa.agent.model.api.AgentChatModel) request -> new AgentChatResponse(
                "trip-plan-response",
                request.model().providerModelId(),
                "{\"city\":\"Shanghai\",\"days\":2,\"activities\":[\"Bund walk\",\"Shanghai Museum\"]}",
                List.of(),
                ModelFinishReason.STOP,
                ModelUsage.unpriced(4, 4),
                "",
                Map.of(),
                Optional.empty(),
                Optional.of(output));

        try (var agent = HaifaAgentStarter.builder()
                .model(model, DeterministicExampleSupport.snapshot())
                .build()) {
            var response = agent.chat("Plan a two-day trip.", TripPlan.class).await();
            TripPlan plan = response.value();
            System.out.println(plan.city() + ": " + plan.activities());
        }
    }

    public record TripPlan(String city, int days, List<String> activities) {}
}
