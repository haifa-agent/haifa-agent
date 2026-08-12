package io.haifa.agent.sdk.conversation;

import io.haifa.agent.core.content.ContentPart;
import io.haifa.agent.core.content.ImageUrlContentPart;
import io.haifa.agent.core.content.StoredImageContentPart;
import io.haifa.agent.core.run.StructuredOutputRequirement;
import io.haifa.agent.core.session.AgentSessionId;
import java.util.List;
import java.util.Objects;

public record SubmitConversationTurnCommand(
        AgentSessionId sessionId,
        long expectedRevision,
        String idempotencyKey,
        String message,
        java.util.Optional<String> runProfileId,
        List<ContentPart> inputs,
        java.util.Optional<StructuredOutputRequirement> structuredOutput) {
    public SubmitConversationTurnCommand(
            AgentSessionId sessionId, long expectedRevision, String idempotencyKey, String message) {
        this(
                sessionId,
                expectedRevision,
                idempotencyKey,
                message,
                java.util.Optional.empty(),
                List.of(),
                java.util.Optional.empty());
    }

    public SubmitConversationTurnCommand(
            AgentSessionId sessionId,
            long expectedRevision,
            String idempotencyKey,
            String message,
            java.util.Optional<String> runProfileId) {
        this(sessionId, expectedRevision, idempotencyKey, message, runProfileId, List.of(), java.util.Optional.empty());
    }

    public SubmitConversationTurnCommand(
            AgentSessionId sessionId,
            long expectedRevision,
            String idempotencyKey,
            String message,
            java.util.Optional<String> runProfileId,
            List<ContentPart> inputs) {
        this(sessionId, expectedRevision, idempotencyKey, message, runProfileId, inputs, java.util.Optional.empty());
    }

    public SubmitConversationTurnCommand {
        sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (expectedRevision < 0) throw new IllegalArgumentException("expectedRevision must not be negative");
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey", 256);
        message = requireText(message, "message", 32_000);
        runProfileId = Objects.requireNonNull(runProfileId, "runProfileId must not be null")
                .map(value -> requireText(value, "runProfileId", 256));
        inputs = imageInputs(inputs);
        structuredOutput = Objects.requireNonNullElse(structuredOutput, java.util.Optional.empty());
    }

    private static String requireText(String value, String field, int limit) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        if (normalized.length() > limit) throw new IllegalArgumentException(field + " is too long");
        return normalized;
    }

    private static List<ContentPart> imageInputs(List<ContentPart> values) {
        List<ContentPart> copied = List.copyOf(Objects.requireNonNull(values, "inputs must not be null"));
        if (copied.size() > 4) throw new IllegalArgumentException("a conversation turn may contain at most 4 images");
        if (copied.stream()
                .anyMatch(value ->
                        !(value instanceof ImageUrlContentPart) && !(value instanceof StoredImageContentPart))) {
            throw new IllegalArgumentException("conversation inputs may contain image references only");
        }
        return copied;
    }
}
