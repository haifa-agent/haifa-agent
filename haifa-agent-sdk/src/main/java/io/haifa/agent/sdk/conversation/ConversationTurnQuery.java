package io.haifa.agent.sdk.conversation;

import java.util.Objects;
import java.util.Optional;

public record ConversationTurnQuery(Optional<ConversationTurnCursor> after, int limit) {
    public ConversationTurnQuery {
        after = Objects.requireNonNull(after, "after must not be null");
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
    }

    public static ConversationTurnQuery first(int limit) {
        return new ConversationTurnQuery(Optional.empty(), limit);
    }
}
