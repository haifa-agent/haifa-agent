package io.haifa.agent.sdk.conversation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ConversationTurnPage(List<ConversationTurn> items, Optional<ConversationTurnCursor> nextCursor) {
    public ConversationTurnPage {
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
        nextCursor = Objects.requireNonNull(nextCursor, "nextCursor must not be null");
    }
}
