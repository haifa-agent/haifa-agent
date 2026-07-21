package io.haifa.agent.runtime.core.storage;

import io.haifa.agent.core.content.ContentPart;
import io.haifa.agent.core.message.AgentMessageId;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.message.MessageStatus;
import io.haifa.agent.core.message.MessageVisibility;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Message fact before the repository atomically assigns its session sequence. */
public record SessionMessageDraft(
        AgentMessageId id,
        AgentSessionId sessionId,
        Optional<AgentRunId> runId,
        Optional<AgentMessageId> parentMessageId,
        MessageRole role,
        MessageStatus status,
        MessageVisibility visibility,
        List<ContentPart> contents,
        Map<String, Object> metadata,
        Instant createdAt) {
    public SessionMessageDraft {
        id = Objects.requireNonNull(id, "id must not be null");
        sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        runId = Objects.requireNonNull(runId, "runId must not be null");
        parentMessageId = Objects.requireNonNull(parentMessageId, "parentMessageId must not be null");
        role = Objects.requireNonNull(role, "role must not be null");
        status = Objects.requireNonNull(status, "status must not be null");
        visibility = Objects.requireNonNull(visibility, "visibility must not be null");
        contents = List.copyOf(Objects.requireNonNull(contents, "contents must not be null"));
        if (contents.isEmpty()) throw new IllegalArgumentException("message contents must not be empty");
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata must not be null"));
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
