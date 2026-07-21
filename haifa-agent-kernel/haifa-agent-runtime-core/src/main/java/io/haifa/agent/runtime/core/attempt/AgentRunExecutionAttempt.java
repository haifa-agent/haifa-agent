package io.haifa.agent.runtime.core.attempt;

import io.haifa.agent.core.checkpoint.CheckpointId;
import io.haifa.agent.core.error.AgentError;
import io.haifa.agent.core.run.AgentRunId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** One physical executor ownership period for a logical run. */
public final class AgentRunExecutionAttempt {
    private final ExecutionAttemptId attemptId;
    private final AgentRunId runId;
    private final int attemptNumber;
    private final Instant createdAt;
    private final CheckpointId resumedFromCheckpointId;
    private ExecutionAttemptStatus status = ExecutionAttemptStatus.QUEUED;
    private Instant startedAt;
    private Instant heartbeatAt;
    private Instant completedAt;
    private String workerId;
    private AgentError error;
    private long version;

    public AgentRunExecutionAttempt(
            ExecutionAttemptId attemptId,
            AgentRunId runId,
            int attemptNumber,
            Instant createdAt,
            Optional<CheckpointId> resumedFromCheckpointId) {
        this.attemptId = Objects.requireNonNull(attemptId, "attemptId must not be null");
        this.runId = Objects.requireNonNull(runId, "runId must not be null");
        if (attemptNumber < 1) throw new IllegalArgumentException("attemptNumber must be positive");
        this.attemptNumber = attemptNumber;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.resumedFromCheckpointId = Objects.requireNonNull(
                        resumedFromCheckpointId, "resumedFromCheckpointId must not be null")
                .orElse(null);
    }

    public void start(String workerId, Instant at) {
        require(ExecutionAttemptStatus.QUEUED);
        this.workerId = requireText(workerId, "workerId");
        this.startedAt = chronological(at);
        this.heartbeatAt = at;
        status = ExecutionAttemptStatus.RUNNING;
        version++;
    }

    public void heartbeat(Instant at) {
        require(ExecutionAttemptStatus.RUNNING);
        heartbeatAt = chronological(at);
        version++;
    }

    public void finish(ExecutionAttemptStatus target, Instant at, Optional<AgentError> error) {
        if (!target.isTerminal()) throw new IllegalArgumentException("attempt target must be terminal");
        if (status != ExecutionAttemptStatus.RUNNING && status != ExecutionAttemptStatus.QUEUED) {
            throw new IllegalStateException("attempt is already terminal");
        }
        completedAt = chronological(at);
        this.error = Objects.requireNonNull(error, "error must not be null").orElse(null);
        if (target != ExecutionAttemptStatus.FAILED && this.error != null) {
            throw new IllegalArgumentException("only a failed attempt may carry an error");
        }
        status = target;
        version++;
    }

    private Instant chronological(Instant at) {
        Objects.requireNonNull(at, "at must not be null");
        Instant floor = startedAt == null ? createdAt : startedAt;
        if (at.isBefore(floor)) throw new IllegalArgumentException("attempt time must not move backwards");
        return at;
    }

    private void require(ExecutionAttemptStatus expected) {
        if (status != expected)
            throw new IllegalStateException("expected attempt status " + expected + " but was " + status);
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }

    public ExecutionAttemptId attemptId() {
        return attemptId;
    }

    public AgentRunId runId() {
        return runId;
    }

    public int attemptNumber() {
        return attemptNumber;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public ExecutionAttemptStatus status() {
        return status;
    }

    public Optional<Instant> startedAt() {
        return Optional.ofNullable(startedAt);
    }

    public Optional<Instant> heartbeatAt() {
        return Optional.ofNullable(heartbeatAt);
    }

    public Optional<Instant> completedAt() {
        return Optional.ofNullable(completedAt);
    }

    public Optional<String> workerId() {
        return Optional.ofNullable(workerId);
    }

    public Optional<CheckpointId> resumedFromCheckpointId() {
        return Optional.ofNullable(resumedFromCheckpointId);
    }

    public Optional<AgentError> error() {
        return Optional.ofNullable(error);
    }

    public long version() {
        return version;
    }
}
