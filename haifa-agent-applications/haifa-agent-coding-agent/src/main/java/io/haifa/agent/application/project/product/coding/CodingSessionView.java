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
        String productProfileRef) {
    public CodingSessionView {
        summary = Objects.requireNonNull(summary, "summary must not be null");
        activeRun = Objects.requireNonNull(activeRun, "activeRun must not be null");
        pendingInteraction = Objects.requireNonNull(pendingInteraction, "pendingInteraction must not be null");
        eventCursor = Objects.requireNonNull(eventCursor, "eventCursor must not be null");
        configurationDigest = CodingProductValues.requireText(configurationDigest, "configurationDigest", 256);
        productProfileRef = CodingProductValues.requireText(productProfileRef, "productProfileRef", 256);
    }
}
