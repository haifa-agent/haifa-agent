package io.haifa.agent.runtime.core.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RuntimeApiErrorCode;
import io.haifa.agent.runtime.api.RuntimeContractException;
import java.nio.charset.StandardCharsets;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class OpaqueRunEventCursorCodecTest {
    private final OpaqueRunEventCursorCodec codec =
            new OpaqueRunEventCursorCodec("task-02-test-signing-key-material".getBytes(StandardCharsets.UTF_8));

    @Test
    void roundTripsBeforeFirstAndExclusiveSequenceWithoutExposingRunId() {
        AgentRunId runId = new AgentRunId("run-sensitive-reference");

        String beforeFirst = codec.encode(RunEventCursor.beforeFirst(runId));
        String after = codec.encode(new RunEventCursor(runId, "1", OptionalLong.of(42)));

        assertThat(beforeFirst).doesNotContain(runId.value());
        assertThat(after).doesNotContain(runId.value());
        assertThat(codec.decode(beforeFirst, runId, "1")).isEqualTo(RunEventCursor.beforeFirst(runId));
        assertThat(codec.decode(after, runId, "1")).isEqualTo(new RunEventCursor(runId, "1", OptionalLong.of(42)));
    }

    @Test
    void rejectsTamperingWrongRunAndUnsupportedFeedVersionWithStableCodes() {
        AgentRunId runId = new AgentRunId("run");
        String encoded = codec.encode(new RunEventCursor(runId, "1", OptionalLong.of(2)));

        assertCode(() -> codec.decode(encoded + "a", runId, "1"), RuntimeApiErrorCode.CURSOR_INVALID);
        assertCode(() -> codec.decode(encoded, new AgentRunId("another-run"), "1"), RuntimeApiErrorCode.CURSOR_INVALID);
        assertCode(() -> codec.decode(encoded, runId, "2"), RuntimeApiErrorCode.CONTRACT_VERSION_UNSUPPORTED);
    }

    private static void assertCode(Runnable action, RuntimeApiErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(RuntimeContractException.class, exception -> assertThat(exception.code())
                        .isEqualTo(expected));
    }
}
