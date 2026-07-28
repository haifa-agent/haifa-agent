package io.haifa.agent.sdk.conversation;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

public record ConversationCommandBinding(
        String callerScopeDigest,
        String operation,
        String idempotencyKeyDigest,
        String requestDigest,
        String dispatchKey,
        AgentSessionId sessionId,
        Optional<AgentRunId> runId,
        boolean completed,
        OptionalLong resultRevision,
        Instant createdAt) {

    public ConversationCommandBinding {
        callerScopeDigest = ConversationRecord.requireText(callerScopeDigest, "callerScopeDigest", 128);
        operation = ConversationRecord.requireText(operation, "operation", 64);
        idempotencyKeyDigest = ConversationRecord.requireText(idempotencyKeyDigest, "idempotencyKeyDigest", 128);
        requestDigest = ConversationRecord.requireText(requestDigest, "requestDigest", 128);
        dispatchKey = ConversationRecord.requireText(dispatchKey, "dispatchKey", 256);
        sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        runId = Objects.requireNonNull(runId, "runId must not be null");
        resultRevision = Objects.requireNonNull(resultRevision, "resultRevision must not be null");
        if (resultRevision.isPresent() && resultRevision.getAsLong() < 0) {
            throw new IllegalArgumentException("resultRevision must not be negative");
        }
        if (completed != resultRevision.isPresent()) {
            throw new IllegalArgumentException("completed command must have a result revision");
        }
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
