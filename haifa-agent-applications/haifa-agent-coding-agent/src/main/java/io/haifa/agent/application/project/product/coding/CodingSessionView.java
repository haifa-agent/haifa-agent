package io.haifa.agent.application.project.product.coding;

import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.runtime.api.InteractionView;
import io.haifa.agent.runtime.api.RunEventCursor;
import java.util.Objects;
import java.util.Optional;

public record CodingSessionView(
        CodingSessionSummary summary,
        Optional<AgentRunSnapshot> activeRun,
        Optional<InteractionView> pendingInteraction,
        Optional<RunEventCursor> eventCursor,
        String configurationDigest,
        String productProfileRef,
        CodingModelSelection model,
        Optional<String> activeRunTaskSummary) {
    public CodingSessionView {
        summary = Objects.requireNonNull(summary, "summary must not be null");
        activeRun = Objects.requireNonNull(activeRun, "activeRun must not be null");
        pendingInteraction = Objects.requireNonNull(pendingInteraction, "pendingInteraction must not be null");
        eventCursor = Objects.requireNonNull(eventCursor, "eventCursor must not be null");
        configurationDigest = CodingProductValues.requireText(configurationDigest, "configurationDigest", 256);
        productProfileRef = CodingProductValues.requireText(productProfileRef, "productProfileRef", 256);
        model = Objects.requireNonNull(model, "model must not be null");
        activeRunTaskSummary = Objects.requireNonNull(activeRunTaskSummary, "activeRunTaskSummary must not be null")
                .map(value -> CodingProductValues.requireText(value, "activeRunTaskSummary", 120));
    }

    public CodingSessionView(
            CodingSessionSummary summary,
            Optional<AgentRunSnapshot> activeRun,
            Optional<InteractionView> pendingInteraction,
            Optional<RunEventCursor> eventCursor,
            String configurationDigest,
            String productProfileRef,
            CodingModelSelection model) {
        this(
                summary,
                activeRun,
                pendingInteraction,
                eventCursor,
                configurationDigest,
                productProfileRef,
                model,
                Optional.empty());
    }

    public CodingSessionView(
            CodingSessionSummary summary,
            Optional<AgentRunSnapshot> activeRun,
            Optional<InteractionView> pendingInteraction,
            Optional<RunEventCursor> eventCursor,
            String configurationDigest,
            String productProfileRef) {
        this(
                summary,
                activeRun,
                pendingInteraction,
                eventCursor,
                configurationDigest,
                productProfileRef,
                new CodingModelSelection(
                        new CodingModelOption(
                                productProfileRef,
                                productProfileRef,
                                "configured",
                                "Configured",
                                java.util.Set.of(),
                                1),
                        0,
                        true),
                Optional.empty());
    }
}
