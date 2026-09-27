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
