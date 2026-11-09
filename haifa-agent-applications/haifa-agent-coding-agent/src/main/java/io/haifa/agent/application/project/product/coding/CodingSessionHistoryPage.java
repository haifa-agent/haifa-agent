package io.haifa.agent.application.project.product.coding;

import io.haifa.agent.core.session.AgentSessionId;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/** Recent user-visible Coding Session history; it is a read projection, never a recovery source. */
public record CodingSessionHistoryPage(
        AgentSessionId sessionId,
        List<CodingSessionHistoryItem> items,
        boolean earlierHistoryAvailable,
        OptionalLong nextBeforeSequence) {
    public CodingSessionHistoryPage {
        sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
        nextBeforeSequence = Objects.requireNonNull(nextBeforeSequence, "nextBeforeSequence must not be null");
        if (nextBeforeSequence.isPresent() && (!earlierHistoryAvailable || nextBeforeSequence.getAsLong() < 1)) {
            throw new IllegalArgumentException("history continuation must identify earlier history");
        }
    }

    public CodingSessionHistoryPage(
            AgentSessionId sessionId, List<CodingSessionHistoryItem> items, boolean earlierHistoryAvailable) {
        this(
                sessionId,
                items,
                earlierHistoryAvailable,
                earlierHistoryAvailable && !items.isEmpty()
                        ? OptionalLong.of(items.getFirst().sequence())
                        : OptionalLong.empty());
    }

    public static CodingSessionHistoryPage empty(AgentSessionId sessionId) {
        return new CodingSessionHistoryPage(sessionId, List.of(), false);
    }
}
