package io.haifa.agent.runtime.core.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class InMemoryRuntimeStoreIdempotencyTest {

    private static final String CALLER_SCOPE = "tenant:principal";
    private static final String OPERATION = "rename";
    private static final String IDEMPOTENCY_KEY = "key";
    private static final Instant APPLIED_AT = Instant.parse("2026-09-15T08:00:00Z");

    @Test
    void recordThenFindReturnsEqualValue() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AppliedCommandResult result = appliedCommand("payload-v1");

        assertThat(store.recordAppliedCommand(result)).isEqualTo(result);
        assertThat(store.findAppliedCommand(CALLER_SCOPE, OPERATION, IDEMPOTENCY_KEY))
                .contains(result);
    }

    @Test
    void reRecordingIdenticalContentIsStable() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AppliedCommandResult result = appliedCommand("payload-v1");

        store.recordAppliedCommand(result);

        assertThat(store.recordAppliedCommand(result)).isEqualTo(result);
        assertThat(store.findAppliedCommand(CALLER_SCOPE, OPERATION, IDEMPOTENCY_KEY))
                .contains(result);
    }

    @Test
    void conflictingReRecordingThrows() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AppliedCommandResult result = appliedCommand("payload-v1");
        store.recordAppliedCommand(result);

        assertThatThrownBy(() -> store.recordAppliedCommand(appliedCommand("payload-v2")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("applied command idempotency key has conflicting content");
        assertThat(store.findAppliedCommand(CALLER_SCOPE, OPERATION, IDEMPOTENCY_KEY))
                .contains(result);
    }

    private static AppliedCommandResult appliedCommand(String payload) {
        return new AppliedCommandResult(
                CALLER_SCOPE, OPERATION, IDEMPOTENCY_KEY, Optional.of("request-digest"), 1, payload, APPLIED_AT);
    }
}
