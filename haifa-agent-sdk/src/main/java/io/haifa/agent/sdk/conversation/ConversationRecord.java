package io.haifa.agent.sdk.conversation;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

public record ConversationRecord(
        AgentSessionId sessionId,
        TenantRef tenant,
        PrincipalRef principal,
        String displayName,
        ConversationStatus status,
        Optional<AgentRunId> activeRunId,
        OptionalLong activeRunVersion,
        Optional<String> activeDispatchKey,
        Instant createdAt,
        Instant lastActivityAt,
        long revision) {

    public ConversationRecord {
        sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        tenant = Objects.requireNonNull(tenant, "tenant must not be null");
        principal = Objects.requireNonNull(principal, "principal must not be null");
        displayName = requireText(displayName, "displayName", 256);
        status = Objects.requireNonNull(status, "status must not be null");
        activeRunId = Objects.requireNonNull(activeRunId, "activeRunId must not be null");
        activeRunVersion = Objects.requireNonNull(activeRunVersion, "activeRunVersion must not be null");
        activeDispatchKey = Objects.requireNonNull(activeDispatchKey, "activeDispatchKey must not be null")
                .map(value -> requireText(value, "activeDispatchKey", 256));
        if (activeRunId.isPresent() != activeRunVersion.isPresent()) {
            throw new IllegalArgumentException("active Run ID and version must be present together");
        }
        if (activeRunId.isPresent() && activeDispatchKey.isPresent()) {
            throw new IllegalArgumentException("active Run and pending dispatch cannot both be present");
        }
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        lastActivityAt = Objects.requireNonNull(lastActivityAt, "lastActivityAt must not be null");
        if (lastActivityAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("lastActivityAt must not precede createdAt");
        }
        if (revision < 0) throw new IllegalArgumentException("revision must not be negative");
    }

    static String requireText(String value, String field, int limit) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        if (normalized.length() > limit) throw new IllegalArgumentException(field + " is too long");
        return normalized;
    }
}
