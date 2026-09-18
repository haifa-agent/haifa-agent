package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.runtime.core.attempt.ExecutionAttemptId;
import io.haifa.agent.runtime.core.middleware.RuntimePhase;
import io.haifa.agent.runtime.core.trace.RuntimeTraceEvent;
import io.haifa.agent.runtime.core.trace.RuntimeTraceScope;
import io.haifa.agent.runtime.core.trace.RuntimeTraceStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliFailureDiagnosticSinkTest {
    @TempDir
    Path temp;

    @Test
    void storesBoundedStructuralDiagnosticsWithoutMessagesOrHostPaths() throws Exception {
        var sink = CliFailureDiagnosticSink.forDirectory(temp.resolve("diagnostics"));
        var failure = new IllegalStateException(
                "CANARY_SECRET at C:\\private\\workspace", new java.io.IOException("CANARY_CAUSE_SECRET"));
        failure.setStackTrace(new StackTraceElement[] {
            new StackTraceElement("example.Worker", "run", "C:\\Users\\private\\Worker.java", 42)
        });
        var event = new RuntimeTraceEvent(
                "trace-1",
                new AgentRunId("run-1"),
                Optional.of(new ExecutionAttemptId("attempt-1")),
                new AgentSessionId("session-1"),
                Optional.empty(),
                Optional.empty(),
                Optional.of("worker-1"),
                OptionalInt.empty(),
                RuntimePhase.ON_ERROR,
                "runtime.error",
                RuntimeTraceScope.ATTEMPT,
                RuntimeTraceStatus.FAILURE,
                Map.of("diagnosticId", "diagnostic-1", "errorCode", "TOOL_OUTCOME_UNKNOWN"),
                Instant.parse("2026-08-05T00:00:00Z"));

        sink.record(event, failure);

        String stored = Files.readString(temp.resolve("diagnostics/diagnostic-1.json"));
        assertThat(stored)
                .contains("diagnostic-1", "TOOL_OUTCOME_UNKNOWN", "java.lang.IllegalStateException", "Worker.java")
                .doesNotContain("CANARY_SECRET", "CANARY_CAUSE_SECRET", "C:\\\\Users", "private\\\\workspace");
    }
}
