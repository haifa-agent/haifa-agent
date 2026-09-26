package io.haifa.agent.runtime.core.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.api.RunInputId;
import io.haifa.agent.runtime.api.RunInputReceiptStatus;
import io.haifa.agent.runtime.api.RunInputSubmission;
import io.haifa.agent.runtime.api.RuntimeApiErrorCode;
import io.haifa.agent.runtime.api.RuntimeContractException;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class InMemoryRunInputPortTest {
    private static final Instant NOW = Instant.parse("2026-07-26T00:00:00Z");

    @Test
    void recordsAcceptDuplicateConflictAndAppliedPosition() {
        InMemoryRunInputPort port = new InMemoryRunInputPort();
        RunInputSubmission input = input("input-1", "key-1", "steer");

        assertThat(port.accept(input, "tenant|user|owner", NOW).newlyAccepted()).isTrue();
        assertThat(port.accept(input, "tenant|user|owner", NOW.plusSeconds(1)).newlyAccepted())
                .isFalse();
        assertThat(port.pending(input.runId(), 10)).hasSize(1);

        RunInputRecord applied = port.markApplied(input.inputId(), "attempt-1", 2, NOW.plusSeconds(2));
        assertThat(applied.status()).isEqualTo(RunInputReceiptStatus.APPLIED);
        assertThat(applied.attemptId()).contains("attempt-1");
        assertThat(applied.iteration()).hasValue(2);
        assertThat(port.markApplied(input.inputId(), "attempt-ignored", 9, NOW.plusSeconds(3)))
                .isEqualTo(applied);

        assertThatThrownBy(() ->
                        port.accept(input("input-2", "key-1", "changed"), "tenant|user|owner", NOW.plusSeconds(4)))
                .isInstanceOf(RuntimeContractException.class)
                .extracting("code")
                .isEqualTo(RuntimeApiErrorCode.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void matchesRetriesByIntentAndSettlesRejectedInputOnce() {
        InMemoryRunInputPort port = new InMemoryRunInputPort();
        RunInputSubmission input = input("input-1", "key-1", "steer");
        port.accept(input, "tenant|user|owner", NOW);
        RunInputSubmission retry = new RunInputSubmission(
                new RunInputId("input-retry"),
                input.runId(),
                OptionalLong.of(7),
                input.contents(),
                "key-1",
                NOW.plusSeconds(9));

        assertThat(port.findExisting(retry, "tenant|user|owner"))
                .get()
                .satisfies(record -> assertThat(record.submission().inputId()).isEqualTo(input.inputId()));
        assertThat(port.findExisting(retry, "tenant|user|other")).isEmpty();

        RunInputRecord rejected = port.markRejected(input.inputId(), "run-cancelled");
        assertThat(rejected.status()).isEqualTo(RunInputReceiptStatus.REJECTED);
        assertThat(rejected.reasonCode()).contains("run-cancelled");
        assertThat(port.markRejected(input.inputId(), "run-failed")).isEqualTo(rejected);
        assertThat(port.pending(input.runId(), 10)).isEmpty();
        assertThatThrownBy(() -> port.markApplied(input.inputId(), "attempt-1", 1, NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> port.markRejected(input.inputId(), "Not A Token"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static RunInputSubmission input(String inputId, String key, String text) {
        return new RunInputSubmission(
                new RunInputId(inputId),
                new AgentRunId("run-1"),
                OptionalLong.empty(),
                List.of(new TextPart(text, "text/plain")),
                key,
                NOW);
    }
}
