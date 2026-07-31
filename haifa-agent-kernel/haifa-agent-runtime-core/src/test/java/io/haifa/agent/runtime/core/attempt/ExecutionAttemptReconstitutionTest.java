package io.haifa.agent.runtime.core.attempt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.checkpoint.CheckpointId;
import io.haifa.agent.core.error.AgentError;
import io.haifa.agent.core.error.AgentErrorCode;
import io.haifa.agent.core.persistence.DomainReconstitutionException;
import io.haifa.agent.core.persistence.DomainReconstitutionFailure;
import io.haifa.agent.core.run.AgentRunId;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ExecutionAttemptReconstitutionTest {

    private static final Instant NOW = Instant.parse("2026-07-25T00:00:00Z");

    @Test
    void roundTripsEveryLegalStatusAndOptionalField() {
        assertRoundTrip(attempt("queued"));

        AgentRunExecutionAttempt running = started("running");
        running.heartbeat(NOW.plusSeconds(2));
        assertRoundTrip(running);

        for (ExecutionAttemptStatus status : new ExecutionAttemptStatus[] {
            ExecutionAttemptStatus.SUCCEEDED,
            ExecutionAttemptStatus.PAUSED,
            ExecutionAttemptStatus.CANCELLED,
            ExecutionAttemptStatus.ABANDONED
        }) {
            AgentRunExecutionAttempt terminal = started(status.name().toLowerCase());
            terminal.finish(status, NOW.plusSeconds(2), Optional.empty());
            assertRoundTrip(terminal);
        }

        AgentRunExecutionAttempt failed = started("failed");
        failed.finish(ExecutionAttemptStatus.FAILED, NOW.plusSeconds(2), Optional.of(error()));
        assertRoundTrip(failed);

        AgentRunExecutionAttempt queuedFailure = attempt("queued-failure");
        queuedFailure.finish(ExecutionAttemptStatus.FAILED, NOW.plusSeconds(1), Optional.empty());
        assertRoundTrip(queuedFailure);
    }

    @Test
    void rejectsUnknownVersionEnumBackwardsTimeAndNegativeVersion() {
        ExecutionAttemptPersistenceSnapshot snapshot = attempt("invalid").persistenceSnapshot();
        assertFailure(
                copy(snapshot, "2", snapshot.status(), snapshot.createdAt(), snapshot.version()),
                DomainReconstitutionFailure.UNSUPPORTED_SCHEMA_VERSION);
        assertFailure(
                copy(snapshot, snapshot.schemaVersion(), "MISSING", snapshot.createdAt(), snapshot.version()),
                DomainReconstitutionFailure.UNKNOWN_ENUM);
        ExecutionAttemptPersistenceSnapshot running = started("invalid-time").persistenceSnapshot();
        assertFailure(
                copy(running, running.schemaVersion(), running.status(), NOW.plusSeconds(2), running.version()),
                DomainReconstitutionFailure.INVALID_HISTORY);
        assertFailure(
                copy(snapshot, snapshot.schemaVersion(), snapshot.status(), snapshot.createdAt(), -1),
                DomainReconstitutionFailure.INVALID_HISTORY);
    }

    @Test
    void ordinaryBehaviorRejectsFinishingBeforeLatestHeartbeat() {
        AgentRunExecutionAttempt attempt = started("chronology");
        attempt.heartbeat(NOW.plusSeconds(3));

        assertThatThrownBy(() -> attempt.finish(ExecutionAttemptStatus.SUCCEEDED, NOW.plusSeconds(2), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("backwards");
    }

    private static AgentRunExecutionAttempt attempt(String id) {
        return new AgentRunExecutionAttempt(
                new ExecutionAttemptId(id), new AgentRunId("run"), 1, NOW, Optional.of(new CheckpointId("checkpoint")));
    }

    private static AgentRunExecutionAttempt started(String id) {
        AgentRunExecutionAttempt attempt = attempt(id);
        attempt.start("process-1", NOW.plusSeconds(1));
        return attempt;
    }

    private static void assertRoundTrip(AgentRunExecutionAttempt attempt) {
        ExecutionAttemptPersistenceSnapshot snapshot = attempt.persistenceSnapshot();
        assertThat(AgentRunExecutionAttempt.reconstitute(snapshot).persistenceSnapshot())
                .isEqualTo(snapshot);
    }

    private static AgentError error() {
        return new AgentError(
                AgentErrorCode.RUNTIME_EXECUTION_FAILED, Map.of(), "diag-attempt-failed", NOW.plusSeconds(2));
    }

    private static ExecutionAttemptPersistenceSnapshot copy(
            ExecutionAttemptPersistenceSnapshot source,
            String schemaVersion,
            String status,
            Instant createdAt,
            long version) {
        return new ExecutionAttemptPersistenceSnapshot(
                schemaVersion,
                source.attemptId(),
                source.runId(),
                source.attemptNumber(),
                createdAt,
                source.resumedFromCheckpointId(),
                status,
                source.startedAt(),
                source.heartbeatAt(),
                source.completedAt(),
                source.workerId(),
                source.error(),
                version);
    }

    private static void assertFailure(
            ExecutionAttemptPersistenceSnapshot snapshot, DomainReconstitutionFailure failure) {
        assertThatThrownBy(() -> AgentRunExecutionAttempt.reconstitute(snapshot))
                .isInstanceOfSatisfying(
                        DomainReconstitutionException.class,
                        exception -> assertThat(exception.failure()).isEqualTo(failure));
    }
}
