package io.haifa.agent.runtime.core.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.error.AgentError;
import io.haifa.agent.core.error.AgentErrorCode;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.step.AgentStepId;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.RuntimeIdempotencyKey;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.core.tool.ToolCallStatus;
import io.haifa.agent.core.tool.ToolExecutionError;
import io.haifa.agent.core.tool.ToolResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolCallViewProjectorTest {
    private static final Instant REQUESTED_AT = Instant.parse("2026-09-27T14:00:00Z");
    private static final Instant STARTED_AT = REQUESTED_AT.plusSeconds(1);
    private static final Instant COMPLETED_AT = REQUESTED_AT.plusSeconds(2);

    @Test
    void mapsExistingLifecycleStatusesWithoutInventingASecondState() {
        ToolCall requested = call("requested");
        ToolCall waiting = call("waiting");
        waiting.beginValidation();
        waiting.beginPolicyCheck();
        waiting.waitForApproval();
        ToolCall approved = call("approved");
        approved.beginValidation();
        approved.beginPolicyCheck();
        approved.waitForApproval();
        approved.approve();
        ToolCall running = running("running");
        ToolCall completed = running("completed");
        completed.complete(new ToolResult(true, "ok", Map.of(), List.of(), List.of(), false), COMPLETED_AT);
        ToolCall failed = running("failed");
        failed.fail(
                new ToolExecutionError(new AgentError(
                        AgentErrorCode.TOOL_INVOCATION_FAILED,
                        Map.of("safe", "bounded"),
                        "diagnostic-id",
                        COMPLETED_AT)),
                COMPLETED_AT);
        ToolCall cancelled = call("cancelled");
        cancelled.cancel(COMPLETED_AT);

        assertThat(List.of(requested, waiting, approved, running, completed, failed, cancelled))
                .allSatisfy(call ->
                        assertThat(ToolCallViewProjector.project(call).status()).isEqualTo(call.status()))
                .extracting(ToolCall::status)
                .containsExactly(
                        ToolCallStatus.REQUESTED,
                        ToolCallStatus.WAITING_APPROVAL,
                        ToolCallStatus.APPROVED,
                        ToolCallStatus.RUNNING,
                        ToolCallStatus.COMPLETED,
                        ToolCallStatus.FAILED,
                        ToolCallStatus.CANCELLED);
        assertThat(ToolCallViewProjector.project(failed).error()).contains(AgentErrorCode.TOOL_INVOCATION_FAILED);
    }

    private static ToolCall running(String id) {
        ToolCall call = call(id);
        call.beginValidation();
        call.beginPolicyCheck();
        call.start(STARTED_AT);
        return call;
    }

    @Test
    void delegationPreviewReflectsDisplayTruncationAndRedactionWithoutChangingStoredMetadata() {
        for (String output : List.of("x\n".repeat(300), "token: synthetic-private", "complete answer")) {
            ToolCall call = new ToolCall(
                    new ToolCallId("task-preview"),
                    new AgentRunId("run-projection"),
                    new AgentStepId("task-step"),
                    new ProviderToolCallCorrelationId("provider-task"),
                    new RuntimeIdempotencyKey("task-key"),
                    "task",
                    "1.0.0",
                    new ToolArguments("task.input", "1.0", Map.of()),
                    REQUESTED_AT);
            call.beginValidation();
            call.beginPolicyCheck();
            call.start(STARTED_AT);
            Map<String, Object> source = Map.of(
                    "outputPreview",
                    output,
                    "outputSha256",
                    "a".repeat(64),
                    "outputTruncated",
                    false,
                    "usage",
                    Map.of("inputTokens", 1));
            call.complete(new ToolResult(true, output, source, List.of(), List.of(), false), COMPLETED_AT);
            var view = ToolCallViewProjector.project(call).result().orElseThrow();
            var data = view.structuredData().values();
            assertThat(data.get("outputTruncated")).isEqualTo(!output.equals(data.get("outputPreview")));
            if (output.startsWith("token:")) {
                assertThat(data).doesNotContainKey("outputSha256");
                assertThat(data.get("outputPreview")).isEqualTo("token: [REDACTED]");
            } else {
                assertThat(data).containsEntry("outputSha256", "a".repeat(64));
                assertThat(view.structuredData().truncated()).isEqualTo(output.contains("\n"));
            }
            assertThat(call.result().orElseThrow().structuredData()).isEqualTo(source);
        }
    }

    @Test
    void completedDelegationsKeepExactNumericUsageIncludingZeroWithoutExemptingSecrets() {
        for (long count : List.of(0L, 7L)) {
            ToolCall task = usageCall("task", "COMPLETED", count);
            var usage = (Map<?, ?>) ToolCallViewProjector.project(task)
                    .result()
                    .orElseThrow()
                    .structuredData()
                    .values()
                    .get("usage");
            assertThat(usage.get("inputTokens")).isEqualTo(count);
            assertThat(usage.get("outputTokens")).isEqualTo(count);
            assertThat(usage.get("cachedInputTokens")).isEqualTo(0L);
            assertThat(usage.get("accessToken")).isEqualTo("[REDACTED]");
        }
    }

    @Test
    void failedChildrenMalformedCountsAndOrdinaryToolsDoNotExposeTokenNamedValues() {
        for (ToolCall call : List.of(
                usageCall("task", "FAILED", 0L),
                usageCall("task", "COMPLETED", "0"),
                usageCall("task", "COMPLETED", -1L),
                usageCall("task", "COMPLETED", 0.0),
                usageCall("ordinary", "COMPLETED", 0L))) {
            var usage = (Map<?, ?>) ToolCallViewProjector.project(call)
                    .result()
                    .orElseThrow()
                    .structuredData()
                    .values()
                    .get("usage");
            assertThat(usage.get("inputTokens")).isEqualTo("[REDACTED]");
            assertThat(usage.get("outputTokens")).isEqualTo("[REDACTED]");
            assertThat(usage.get("accessToken")).isEqualTo("[REDACTED]");
        }
    }

    private static ToolCall usageCall(String tool, String status, Object count) {
        ToolCall call = new ToolCall(
                new ToolCallId("usage-call"),
                new AgentRunId("usage-run"),
                new AgentStepId("usage-step"),
                new ProviderToolCallCorrelationId("usage-provider"),
                new RuntimeIdempotencyKey("usage-key"),
                tool,
                "1.0.0",
                new ToolArguments("usage.input", "1.0", Map.of()),
                REQUESTED_AT);
        call.beginValidation();
        call.beginPolicyCheck();
        call.start(STARTED_AT);
        call.complete(
                new ToolResult(
                        true,
                        "done",
                        Map.of(
                                "status",
                                status,
                                "usage",
                                Map.of(
                                        "inputTokens",
                                        count,
                                        "outputTokens",
                                        count,
                                        "cachedInputTokens",
                                        0L,
                                        "accessToken",
                                        123L)),
                        List.of(),
                        List.of(),
                        false),
                COMPLETED_AT);
        return call;
    }

    private static ToolCall call(String id) {
        return new ToolCall(
                new ToolCallId("tool-" + id),
                new AgentRunId("run-projection"),
                new AgentStepId("step-" + id),
                new ProviderToolCallCorrelationId("provider-" + id),
                new RuntimeIdempotencyKey("idempotency-" + id),
                "write",
                "1.0.0",
                new ToolArguments("write.input", "1.0", Map.of()),
                REQUESTED_AT);
    }
}
