package io.haifa.agent.application.project.product.coding;

import io.haifa.agent.core.session.AgentSessionId;
import java.util.Objects;

/** Safe product view of a restorable queued follow-up. */
public record CodingQueuedMessage(
        String followUpId, AgentSessionId sessionId, String summary, long sequence, long revision) {
    public CodingQueuedMessage {
        followUpId = CodingProductValues.requireText(followUpId, "followUpId", 256);
        sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        summary = CodingProductValues.requireText(summary, "summary", 512);
        if (sequence < 1) throw new IllegalArgumentException("sequence must be positive");
        if (revision < 0) throw new IllegalArgumentException("revision must not be negative");
    }
}
