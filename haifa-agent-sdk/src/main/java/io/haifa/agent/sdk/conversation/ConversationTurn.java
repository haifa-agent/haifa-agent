package io.haifa.agent.sdk.conversation;

import io.haifa.agent.core.content.ArtifactRefPart;
import io.haifa.agent.core.content.AssetRefPart;
import io.haifa.agent.core.content.ContentPart;
import io.haifa.agent.core.content.ImageUrlContentPart;
import io.haifa.agent.core.content.StoredAudioContentPart;
import io.haifa.agent.core.content.StoredImageContentPart;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.message.MessageVisibility;
import io.haifa.agent.core.run.AgentRunId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** User-visible conversation turn; tool and runtime-private content are never projected here. */
public record ConversationTurn(
        String messageId,
        MessageRole role,
        Optional<AgentRunId> runId,
        long sequence,
        List<ContentPart> contents,
        MessageVisibility visibility,
        Instant createdAt) {
    public ConversationTurn {
        messageId = ConversationRecord.requireText(messageId, "messageId", 256);
        role = Objects.requireNonNull(role, "role must not be null");
        runId = Objects.requireNonNull(runId, "runId must not be null");
        if (sequence < 1) throw new IllegalArgumentException("sequence must be positive");
        contents = List.copyOf(Objects.requireNonNull(contents, "contents must not be null"));
        if (contents.isEmpty()) throw new IllegalArgumentException("contents must not be empty");
        if (contents.stream()
                .anyMatch(content -> !(content instanceof TextPart
                        || content instanceof AssetRefPart
                        || content instanceof ArtifactRefPart
                        || content instanceof ImageUrlContentPart
                        || content instanceof StoredImageContentPart
                        || content instanceof StoredAudioContentPart))) {
            throw new IllegalArgumentException("tool protocol content is not user-visible");
        }
        long textLength = contents.stream()
                .filter(TextPart.class::isInstance)
                .map(TextPart.class::cast)
                .mapToLong(value -> value.text().length())
                .sum();
        if (textLength > 64_000) throw new IllegalArgumentException("turn text is too long");
        visibility = Objects.requireNonNull(visibility, "visibility must not be null");
        if (visibility != MessageVisibility.USER_VISIBLE) {
            throw new IllegalArgumentException("ConversationTurn must be user-visible");
        }
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public String text() {
        return contents.stream()
                .filter(TextPart.class::isInstance)
                .map(TextPart.class::cast)
                .map(TextPart::text)
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.joining("\n"));
    }
}
