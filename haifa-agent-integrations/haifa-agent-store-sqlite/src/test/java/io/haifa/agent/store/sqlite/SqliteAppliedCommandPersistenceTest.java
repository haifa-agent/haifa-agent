package io.haifa.agent.store.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.runtime.api.RunCancellation;
import io.haifa.agent.runtime.api.RuntimeCommand;
import io.haifa.agent.runtime.api.RuntimeCommandId;
import io.haifa.agent.runtime.api.RuntimeCommandResult;
import io.haifa.agent.runtime.api.RuntimeCommandStatus;
import io.haifa.agent.runtime.api.RuntimeCommandType;
import io.haifa.agent.runtime.core.storage.AppliedCommandResult;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteAppliedCommandPersistenceTest {

    private static final String CALLER_SCOPE = "tenant:principal";
    private static final String OPERATION = "rename";
    private static final String IDEMPOTENCY_KEY = "key";
    private static final Instant APPLIED_AT = SqliteTestSupport.NOW;

    @Test
    void deadlineCommandResultKeepsItsPersistedMeaningAndRejectsDifferentLimits(@TempDir Path directory) {
        RunCancellation cancellation = RunCancellation.deadlineExceeded(Duration.ofSeconds(1));
        RuntimeCommandResult result;
        try (SqliteStoreFoundation foundation = SqliteTestSupport.foundation(directory)) {
            var run = SqliteAggregateTestData.prepareRun(foundation);
            RuntimeCommand command = new RuntimeCommand(
                    new RuntimeCommandId("deadline-command"),
                    run.id(),
                    RuntimeCommandType.CANCEL,
                    cancellation.arguments(),
                    "deadline-key",
                    APPLIED_AT);
            result = new RuntimeCommandResult(command, RuntimeCommandStatus.ACCEPTED, AgentRunSnapshot.from(run));
            foundation.idempotency().recordCommandResult(CALLER_SCOPE, "deadline-key", result);
            foundation.idempotency().recordCommandResult(CALLER_SCOPE, "deadline-key", result);
        }
        try (SqliteStoreFoundation reopened = SqliteTestSupport.foundation(directory)) {
            var persisted = reopened.idempotency()
                    .findCommandResult(CALLER_SCOPE, "deadline-key")
                    .orElseThrow();
            assertThat(persisted.command().commandId())
                    .isEqualTo(result.command().commandId());
            assertThat(persisted.command().runId()).isEqualTo(result.command().runId());
            assertThat(persisted.snapshot()).isEqualTo(result.snapshot());
            assertThat(RunCancellation.from(persisted.command().arguments())).isEqualTo(cancellation);
            reopened.idempotency().recordCommandResult(CALLER_SCOPE, "deadline-key", result);
            RuntimeCommand different = new RuntimeCommand(
                    result.command().commandId(),
                    result.command().runId(),
                    RuntimeCommandType.CANCEL,
                    RunCancellation.deadlineExceeded(Duration.ofMillis(1001)).arguments(),
                    "deadline-key",
                    APPLIED_AT);
            assertThatThrownBy(() -> reopened.idempotency()
                            .recordCommandResult(
                                    CALLER_SCOPE,
                                    "deadline-key",
                                    new RuntimeCommandResult(different, result.status(), result.snapshot())))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("command result idempotency key has conflicting content");
            assertThat(RunCancellation.from(reopened.idempotency()
                            .findCommandResult(CALLER_SCOPE, "deadline-key")
                            .orElseThrow()
                            .command()
                            .arguments()))
                    .isEqualTo(cancellation);
        }
    }

    @Test
    void appliedCommandResultRoundTripsAcrossFreshFoundation(@TempDir Path directory) {
        AppliedCommandResult result = appliedCommand("{\"conversationId\":\"c1\"}");

        try (SqliteStoreFoundation foundation = SqliteTestSupport.foundation(directory)) {
            assertThat(foundation.idempotency().recordAppliedCommand(result)).isEqualTo(result);
            assertThat(foundation.idempotency().recordAppliedCommand(result)).isEqualTo(result);
        }

        try (SqliteStoreFoundation reopened = SqliteTestSupport.foundation(directory)) {
            assertThat(reopened.idempotency().findAppliedCommand(CALLER_SCOPE, OPERATION, IDEMPOTENCY_KEY))
                    .contains(result);
        }
    }

    @Test
    void conflictingAppliedCommandResultThrowsAndPreservesStoredValue(@TempDir Path directory) {
        AppliedCommandResult result = appliedCommand("{\"conversationId\":\"c1\"}");
        AppliedCommandResult conflicting = appliedCommand("{\"conversationId\":\"c2\"}");

        try (SqliteStoreFoundation foundation = SqliteTestSupport.foundation(directory)) {
            foundation.idempotency().recordAppliedCommand(result);
            assertThatThrownBy(() -> foundation.idempotency().recordAppliedCommand(conflicting))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("applied command idempotency key has conflicting content");
        }

        try (SqliteStoreFoundation reopened = SqliteTestSupport.foundation(directory)) {
            assertThat(reopened.idempotency().findAppliedCommand(CALLER_SCOPE, OPERATION, IDEMPOTENCY_KEY))
                    .contains(result);
        }
    }

    private static AppliedCommandResult appliedCommand(String resultPayload) {
        return new AppliedCommandResult(
                CALLER_SCOPE, OPERATION, IDEMPOTENCY_KEY, Optional.of("request-digest"), 1, resultPayload, APPLIED_AT);
    }
}
