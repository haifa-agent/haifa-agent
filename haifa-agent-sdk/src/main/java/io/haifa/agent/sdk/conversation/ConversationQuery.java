package io.haifa.agent.sdk.conversation;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record ConversationQuery(
        Optional<String> text, Set<ConversationStatus> statuses, Optional<ConversationCursor> after, int limit) {

    public ConversationQuery {
        text = Objects.requireNonNull(text, "text must not be null")
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> {
                    if (value.length() > 256) throw new IllegalArgumentException("query text is too long");
                    return value;
                });
        statuses = Set.copyOf(Objects.requireNonNull(statuses, "statuses must not be null"));
        if (statuses.isEmpty()) throw new IllegalArgumentException("statuses must not be empty");
        after = Objects.requireNonNull(after, "after must not be null");
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
    }

    public static ConversationQuery active(int limit) {
        return new ConversationQuery(Optional.empty(), Set.of(ConversationStatus.ACTIVE), Optional.empty(), limit);
    }
}
