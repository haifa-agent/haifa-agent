package io.haifa.agent.core.run;

import io.haifa.agent.core.session.SessionId;
import java.time.Instant;
import java.util.Objects;

/** Immutable aggregate representing one execution of an Agent session. */
public record AgentRun(RunId id, SessionId sessionId, AgentRunStatus status, Instant createdAt, Instant updatedAt) {

    public AgentRun {
        id = Objects.requireNonNull(id, "id must not be null");
        sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        status = Objects.requireNonNull(status, "status must not be null");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
    }

    public static AgentRun create(SessionId sessionId, Instant now) {
        return new AgentRun(RunId.create(), sessionId, AgentRunStatus.NEW, now, now);
    }

    public AgentRun transitionTo(AgentRunStatus target, Instant at) {
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(at, "at must not be null");
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException("cannot transition Agent run from " + status + " to " + target);
        }
        if (at.isBefore(updatedAt)) {
            throw new IllegalArgumentException("transition time must not be before the current update time");
        }
        return new AgentRun(id, sessionId, target, createdAt, at);
    }
}
