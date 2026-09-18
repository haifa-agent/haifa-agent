package io.haifa.agent.core.step;

import static io.haifa.agent.core.support.DomainValues.optionalText;

import io.haifa.agent.core.persistence.DomainReconstitution;
import io.haifa.agent.core.persistence.DomainReconstitutionException;
import io.haifa.agent.core.run.AgentRunId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Persisted execution step with controlled lifecycle transitions. */
public final class AgentStep {

    private final AgentStepId id;
    private final AgentRunId runId;
    private final AgentStepId parentStepId;
    private final String branchId;
    private final AgentStepType type;
    private final int sequence;
    private final Instant createdAt;
    private AgentStepStatus status = AgentStepStatus.PENDING;
    private Instant startedAt;
    private Instant completedAt;
    private AgentStepResult result;
    private AgentStepError error;
    private long version;

    public AgentStep(
            AgentStepId id,
            AgentRunId runId,
            AgentStepId parentStepId,
            String branchId,
            AgentStepType type,
            int sequence,
            Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.runId = Objects.requireNonNull(runId, "runId must not be null");
        if (id.equals(parentStepId)) {
            throw new IllegalArgumentException("step cannot be its own parent");
        }
        this.parentStepId = parentStepId;
        this.branchId = optionalText(branchId);
        this.type = Objects.requireNonNull(type, "type must not be null");
        if (sequence < 1) {
            throw new IllegalArgumentException("step sequence must be positive");
        }
        this.sequence = sequence;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public static AgentStep reconstitute(AgentStepPersistenceSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        DomainReconstitution.requireSupportedVersion(snapshot.schemaVersion(), "AgentStep");
        AgentStepStatus restoredStatus =
                DomainReconstitution.enumValue(AgentStepStatus.class, snapshot.status(), "step status");
        DomainReconstitution.requireVersion(snapshot.version(), "step");
        validatePersistedState(snapshot, restoredStatus);
        try {
            AgentStep step = new AgentStep(
                    snapshot.id(),
                    snapshot.runId(),
                    snapshot.parentStepId(),
                    snapshot.branchId(),
                    snapshot.type(),
                    snapshot.sequence(),
                    snapshot.createdAt());
            step.status = restoredStatus;
            step.startedAt = snapshot.startedAt();
            step.completedAt = snapshot.completedAt();
            step.result = snapshot.result();
            step.error = snapshot.error();
            step.version = snapshot.version();
            return step;
        } catch (DomainReconstitutionException exception) {
            throw exception;
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw DomainReconstitution.invalid("AgentStep", exception);
        }
    }

    private static void validatePersistedState(AgentStepPersistenceSnapshot snapshot, AgentStepStatus status) {
        Instant createdAt = snapshot.createdAt();
        Instant updatedAt = snapshot.completedAt() != null
                ? snapshot.completedAt()
                : snapshot.startedAt() != null ? snapshot.startedAt() : createdAt;
        DomainReconstitution.requireChronological(createdAt, updatedAt, "step");
        DomainReconstitution.requireWithinHistory(snapshot.startedAt(), createdAt, updatedAt, "startedAt", "step");
        boolean valid =
                switch (status) {
                    case PENDING ->
                        snapshot.startedAt() == null
                                && snapshot.completedAt() == null
                                && snapshot.result() == null
                                && snapshot.error() == null
                                && snapshot.version() == 0;
                    case RUNNING ->
                        snapshot.startedAt() != null
                                && snapshot.completedAt() == null
                                && snapshot.result() == null
                                && snapshot.error() == null
                                && snapshot.version() >= 1;
                    case WAITING ->
                        snapshot.startedAt() != null
                                && snapshot.completedAt() == null
                                && snapshot.result() == null
                                && snapshot.error() == null
                                && snapshot.version() >= 2;
                    case COMPLETED ->
                        snapshot.startedAt() != null
                                && snapshot.completedAt() != null
                                && snapshot.result() != null
                                && snapshot.error() == null
                                && snapshot.version() >= 2;
                    case FAILED ->
                        snapshot.startedAt() != null
                                && snapshot.completedAt() != null
                                && snapshot.result() == null
                                && snapshot.error() != null
                                && snapshot.version() >= 2;
                    case CANCELLED ->
                        snapshot.completedAt() != null
                                && snapshot.result() == null
                                && snapshot.error() == null
                                && snapshot.version() >= 1;
                    case SKIPPED ->
                        snapshot.startedAt() == null
                                && snapshot.completedAt() != null
                                && snapshot.result() == null
                                && snapshot.error() == null
                                && snapshot.version() == 1;
                };
        if (!valid) {
            DomainReconstitution.invalid("step state is inconsistent for status " + status);
        }
    }

    public AgentStepPersistenceSnapshot persistenceSnapshot() {
        return new AgentStepPersistenceSnapshot(
                DomainReconstitution.SCHEMA_VERSION,
                id,
                runId,
                parentStepId,
                branchId,
                type,
                sequence,
                createdAt,
                status.name(),
                startedAt,
                completedAt,
                result,
                error,
                version);
    }

    public void start(Instant at) {
        requireStatus(AgentStepStatus.PENDING);
        startedAt = requireTime(at);
        status = AgentStepStatus.RUNNING;
        version++;
    }

    public void waitForExternalInput() {
        requireStatus(AgentStepStatus.RUNNING);
        status = AgentStepStatus.WAITING;
        version++;
    }

    public void resume() {
        requireStatus(AgentStepStatus.WAITING);
        status = AgentStepStatus.RUNNING;
        version++;
    }

    public void complete(AgentStepResult result, Instant at) {
        requireStatus(AgentStepStatus.RUNNING);
        this.result = Objects.requireNonNull(result, "result must not be null");
        completedAt = requireTime(at);
        status = AgentStepStatus.COMPLETED;
        version++;
    }

    public void fail(AgentStepError error, Instant at) {
        if (status != AgentStepStatus.RUNNING && status != AgentStepStatus.WAITING) {
            throw new IllegalStateException("only running or waiting steps can fail");
        }
        this.error = Objects.requireNonNull(error, "error must not be null");
        completedAt = requireTime(at);
        status = AgentStepStatus.FAILED;
        version++;
    }

    public void cancel(Instant at) {
        finishWithoutResult(AgentStepStatus.CANCELLED, at);
    }

    public void skip(Instant at) {
        if (status != AgentStepStatus.PENDING) {
            throw new IllegalStateException("only pending steps can be skipped");
        }
        finishWithoutResult(AgentStepStatus.SKIPPED, at);
    }

    private void finishWithoutResult(AgentStepStatus target, Instant at) {
        if (status == AgentStepStatus.COMPLETED
                || status == AgentStepStatus.FAILED
                || status == AgentStepStatus.CANCELLED
                || status == AgentStepStatus.SKIPPED) {
            throw new IllegalStateException("terminal step cannot be changed");
        }
        completedAt = requireTime(at);
        status = target;
        version++;
    }

    private Instant requireTime(Instant at) {
        Objects.requireNonNull(at, "at must not be null");
        Instant floor = startedAt == null ? createdAt : startedAt;
        if (at.isBefore(floor)) {
            throw new IllegalArgumentException("step time must not move backwards");
        }
        return at;
    }

    private void requireStatus(AgentStepStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("expected step status " + expected + " but was " + status);
        }
    }

    public AgentStepId id() {
        return id;
    }

    public AgentRunId runId() {
        return runId;
    }

    public Optional<AgentStepId> parentStepId() {
        return Optional.ofNullable(parentStepId);
    }

    public Optional<String> branchId() {
        return Optional.ofNullable(branchId).filter(value -> !value.isEmpty());
    }

    public AgentStepType type() {
        return type;
    }

    public int sequence() {
        return sequence;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public AgentStepStatus status() {
        return status;
    }

    public Optional<Instant> startedAt() {
        return Optional.ofNullable(startedAt);
    }

    public Optional<Instant> completedAt() {
        return Optional.ofNullable(completedAt);
    }

    public Optional<AgentStepResult> result() {
        return Optional.ofNullable(result);
    }

    public Optional<AgentStepError> error() {
        return Optional.ofNullable(error);
    }

    public long version() {
        return version;
    }
}
