package io.haifa.agent.application.project.product.coding;

import io.haifa.agent.core.session.AgentSessionId;
import java.util.List;
import java.util.Objects;

/** Recent user-visible Coding Session history; it is a read projection, never a recovery source. */
public record CodingSessionHistoryPage(
        AgentSessionId sessionId, List<CodingSessionHistoryItem> items, boolean earlierHistoryAvailable) {
    public CodingSessionHistoryPage {
        sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
    }

    public static CodingSessionHistoryPage empty(AgentSessionId sessionId) {
        return new CodingSessionHistoryPage(sessionId, List.of(), false);
    }
}
