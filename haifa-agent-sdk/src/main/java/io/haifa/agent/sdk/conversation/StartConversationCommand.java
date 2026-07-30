package io.haifa.agent.sdk.conversation;

import java.util.Objects;

public record StartConversationCommand(
        String idempotencyKey, String displayName, String message, java.util.Optional<String> runProfileId) {
    public StartConversationCommand(String idempotencyKey, String displayName, String message) {
        this(idempotencyKey, displayName, message, java.util.Optional.empty());
    }

    public StartConversationCommand {
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey", 256);
        displayName = requireText(displayName, "displayName", 256);
        message = requireText(message, "message", 32_000);
        runProfileId = Objects.requireNonNull(runProfileId, "runProfileId must not be null")
                .map(value -> requireText(value, "runProfileId", 256));
    }

    private static String requireText(String value, String field, int limit) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        if (normalized.length() > limit) throw new IllegalArgumentException(field + " is too long");
        return normalized;
    }
}
