package io.haifa.agent.store.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.runtime.core.storage.AppliedCommandResult;
import java.nio.file.Path;
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
