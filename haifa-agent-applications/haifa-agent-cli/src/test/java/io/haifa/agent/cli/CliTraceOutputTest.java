package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.step.AgentStepId;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.runtime.core.attempt.ExecutionAttemptId;
import io.haifa.agent.runtime.core.middleware.RuntimePhase;
import io.haifa.agent.runtime.core.trace.RuntimeTraceEvent;
import io.haifa.agent.runtime.core.trace.RuntimeTraceScope;
import io.haifa.agent.runtime.core.trace.RuntimeTraceStatus;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliTraceOutputTest {
    @TempDir
    Path temp;

    @Test
    void summaryEmitsOnlyKeyEventsAndClassifiesSkillCalls() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var trace = CliTraceOutput.open(
                Optional.of(CliTraceMode.SUMMARY),
                Optional.empty(),
                new PrintStream(bytes, true, StandardCharsets.UTF_8))) {
            trace.accept(event("context.built", Optional.empty(), Map.of("estimatedInputTokens", 42)));
            trace.accept(event(
                    "tool.execute",
                    Optional.of(new ToolCallId("tool-call-1")),
                    Map.of("toolName", "skill.load", "providerId", "haifa-runtime-skill")));
            trace.accept(event(
                    "tool.persisted",
                    Optional.of(new ToolCallId("tool-call-1")),
                    Map.of("successful", true, "truncated", false, "externalized", false)));
            trace.accept(event(
                    "runtime.error",
                    Optional.empty(),
                    Map.of(
                            "errorCode",
                            "RUNTIME_EXECUTION_FAILED",
                            "exceptionType",
                            "SqliteStoreException",
                            "rootExceptionType",
                            "IllegalArgumentException")));
        }

        String output = bytes.toString(StandardCharsets.UTF_8);
        assertThat(output)
                .doesNotContain("context.built")
                .contains("skill started")
                .contains("toolName=skill.load")
                .contains("skill completed")
                .contains("successful=true")
                .contains("toolCallId=tool-call-1")
                .contains("runtime failed")
                .contains("errorCode=RUNTIME_EXECUTION_FAILED")
                .contains("rootExceptionType=IllegalArgumentException");
    }

    @Test
    void detailUsesOnlyBoundedSingleLineSafeValues() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var trace = CliTraceOutput.open(
                Optional.of(CliTraceMode.DETAIL),
                Optional.empty(),
                new PrintStream(bytes, true, StandardCharsets.UTF_8))) {
            trace.accept(event(
                    "context.built",
                    Optional.empty(),
                    Map.of(
                            "sourceIds",
                            java.util.List.of(
                                    "line one\nline two\u001B[31m red",
                                    Map.of("credentialValue", "must-not-appear", "count", 2)))));
        }

        String output = bytes.toString(StandardCharsets.UTF_8);
        assertThat(output.lines()).hasSize(1);
        assertThat(output)
                .contains("operation=context.built")
                .contains("line one line two red")
                .contains("\"count\":2")
                .doesNotContain("credentialValue")
                .doesNotContain("must-not-appear")
                .doesNotContain("\u001B");
    }

    @Test
    void jsonlIsOneMachineReadableObjectPerEvent() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var trace = CliTraceOutput.open(
                Optional.of(CliTraceMode.JSONL),
                Optional.empty(),
                new PrintStream(bytes, true, StandardCharsets.UTF_8))) {
            trace.accept(event(
                    "model.invoke",
                    Optional.empty(),
                    Map.of("providerId", "deepseek", "inputTokens", 12, "outputTokens", 3)));
        }

        String output = bytes.toString(StandardCharsets.UTF_8).trim();
        assertThat(output.lines()).hasSize(2);
        var json = new ObjectMapper().readTree(output.lines().findFirst().orElseThrow());
        assertThat(json.path("recordType").asText()).isEqualTo("trace.event");
        assertThat(json.path("schema").asText()).isEqualTo("haifa-runtime-trace");
        assertThat(json.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(json.path("producer").asText()).isEqualTo("haifa-agent-cli");
        assertThat(json.path("streamId").asText()).matches("ts_[A-Za-z0-9_-]{22}");
        assertThat(json.path("sequence").asLong()).isEqualTo(1);
        assertThat(json.path("operation").asText()).isEqualTo("model.invoke");
        assertThat(json.path("runId").asText()).isEqualTo("run-1");
        assertThat(json.path("attributes").path("providerId").asText()).isEqualTo("deepseek");
        assertThat(json.path("attributes").path("inputTokens").asInt()).isEqualTo(12);
        var completed =
                new ObjectMapper().readTree(output.lines().skip(1).findFirst().orElseThrow());
        assertThat(completed.path("operation").asText()).isEqualTo("stream.completed");
        assertThat(completed.path("sequence").asLong()).isEqualTo(2);
        assertThat(completed.path("attributes").path("writtenEvents").asLong()).isEqualTo(2);
        assertThat(completed.path("attributes").path("cleanClose").asBoolean()).isTrue();
    }

    @Test
    void traceFileIsExplicitlyTruncatedAndReceivesSelectedFormat() throws Exception {
        Path target = temp.resolve("runtime.trace");
        Files.writeString(target, "stale", StandardCharsets.UTF_8);

        try (var trace = CliTraceOutput.open(
                Optional.of(CliTraceMode.JSONL), Optional.of(target), new PrintStream(new ByteArrayOutputStream()))) {
            trace.accept(event("loop.iteration", Optional.empty(), Map.of("iteration", 1)));
        }

        String output = Files.readString(target, StandardCharsets.UTF_8);
        assertThat(output).doesNotContain("stale");
        assertThat(new ObjectMapper()
                        .readTree(output.lines().findFirst().orElseThrow())
                        .path("operation")
                        .asText())
                .isEqualTo("loop.iteration");
        assertThat(new ObjectMapper()
                        .readTree(output.lines().skip(1).findFirst().orElseThrow())
                        .path("operation")
                        .asText())
                .isEqualTo("stream.completed");
    }

    @Test
    void jsonlUsesAnOperationAllowlistAndKeepsUnknownOperationsVisible() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var trace = CliTraceOutput.open(
                Optional.of(CliTraceMode.JSONL),
                Optional.empty(),
                new PrintStream(bytes, true, StandardCharsets.UTF_8))) {
            trace.accept(event(
                    "model.invoke",
                    Optional.empty(),
                    Map.of("providerId", "deepseek", "providerMessage", "must-not-appear", "unexpected", "drop-me")));
            trace.accept(event("future.operation", Optional.empty(), Map.of("providerId", "drop-me")));
        }

        var lines = bytes.toString(StandardCharsets.UTF_8).lines().toList();
        var known = new ObjectMapper().readTree(lines.get(0));
        var unknown = new ObjectMapper().readTree(lines.get(1));
        assertThat(known.path("attributes").toString()).isEqualTo("{\"providerId\":\"deepseek\"}");
        assertThat(unknown.path("operation").asText()).isEqualTo("future.operation");
        assertThat(unknown.path("attributes").isEmpty()).isTrue();
        assertThat(bytes.toString(StandardCharsets.UTF_8)).doesNotContain("must-not-appear", "drop-me");
    }

    @Test
    void terminalTraceWithoutAFileDoesNotWriteAcrossTheFullScreenUi() {
        try (var trace = CliTraceOutput.openForTerminal(Optional.of(CliTraceMode.DETAIL), Optional.empty())) {
            trace.accept(event("context.built", Optional.empty(), Map.of("estimatedInputTokens", 42)));
        }
    }

    @Test
    void terminalTraceStillWritesWhenAnExplicitFileIsConfigured() throws Exception {
        Path target = temp.resolve("terminal.trace");

        try (var trace = CliTraceOutput.openForTerminal(Optional.of(CliTraceMode.DETAIL), Optional.of(target))) {
            trace.accept(event("context.built", Optional.empty(), Map.of("estimatedInputTokens", 42)));
        }

        assertThat(Files.readString(target, StandardCharsets.UTF_8))
                .contains("operation=context.built")
                .contains("estimatedInputTokens=42");
    }

    @Test
    void rejectsMissingParentAndDirectoryTargets() {
        assertThatThrownBy(() -> CliTraceOutput.open(
                        Optional.of(CliTraceMode.JSONL),
                        Optional.of(temp.resolve("missing").resolve("trace.jsonl")),
                        System.err))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parent");
        assertThatThrownBy(() -> CliTraceOutput.open(Optional.of(CliTraceMode.JSONL), Optional.of(temp), System.err))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("regular");
    }

    private static RuntimeTraceEvent event(
            String operation, Optional<ToolCallId> toolCallId, Map<String, Object> attributes) {
        return new RuntimeTraceEvent(
                "trace-1",
                new AgentRunId("run-1"),
                Optional.of(new ExecutionAttemptId("attempt-1")),
                new AgentSessionId("session-1"),
                Optional.of(new AgentStepId("step-1")),
                toolCallId,
                Optional.of("worker-1"),
                OptionalInt.of(1),
                RuntimePhase.AFTER_MODEL_CALL,
                operation,
                toolCallId.isPresent() ? RuntimeTraceScope.TOOL_CALL : RuntimeTraceScope.STEP,
                RuntimeTraceStatus.SUCCESS,
                attributes,
                Instant.parse("2026-07-24T00:00:00Z"));
    }
}
