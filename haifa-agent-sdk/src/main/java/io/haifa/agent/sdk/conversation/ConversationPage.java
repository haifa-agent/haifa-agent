package io.haifa.agent.sdk.conversation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ConversationPage(List<ConversationRecord> items, Optional<ConversationCursor> nextCursor) {
    public ConversationPage {
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
        nextCursor = Objects.requireNonNull(nextCursor, "nextCursor must not be null");
    }
}
