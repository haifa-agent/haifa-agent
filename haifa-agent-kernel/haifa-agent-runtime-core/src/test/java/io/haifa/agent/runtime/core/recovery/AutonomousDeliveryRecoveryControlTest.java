package io.haifa.agent.runtime.core.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.error.AgentError;
import io.haifa.agent.core.error.AgentErrorCode;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.step.AgentStep;
import io.haifa.agent.core.step.AgentStepError;
import io.haifa.agent.core.step.AgentStepId;
import io.haifa.agent.core.step.AgentStepType;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.RuntimeIdempotencyKey;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.runtime.core.loop.AgentLoopContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Budget and safe terminal summaries remain independent of model-owned task strategy. */
class AutonomousDeliveryRecoveryControlTest {
    private static final Instant NOW = Instant.parse("2026-07-29T00:00:00Z");

    @Test
    void terminalFailureSummaryReportsCompletedWorkAndManualNextStepWithoutRawArguments() {
        ToolCall compiled = requested(
                "compiled", Map.of("purpose", "生成并编译静态文件服务器", "content", "CANARY_RAW_SCRIPT_MUST_NOT_APPEAR"));
        compiled.beginValidation();
        compiled.beginPolicyCheck();
        compiled.start(NOW.plusSeconds(1));
        compiled.complete(
                new ToolResult(true, "compiled", Map.of("status", "SUCCEEDED"), List.of(), List.of(), false),
                NOW.plusSeconds(2));

        ToolCall blocked =
                requested("blocked", Map.of("purpose", "后台启动服务器", "content", "CANARY_BLOCKED_COMMAND_MUST_NOT_APPEAR"));
        blocked.beginValidation();
        blocked.cancel(NOW.plusSeconds(3));

        AgentStep failedStep = new AgentStep(
                new AgentStepId("failed-step"),
                new AgentRunId("run-1"),
                null,
                null,
                AgentStepType.TOOL_EXECUTION,
                2,
                NOW);
        failedStep.start(NOW.plusSeconds(1));
        failedStep.fail(
                new AgentStepError(new AgentError(
                        AgentErrorCode.TOOL_REQUEST_REJECTED,
                        Map.of(
                                "reason",
                                "ARGUMENTS_INVALID",
                                "repairHint",
                                "Repair the tool arguments: $.content is blocked by the execution safety policy."),
                        "step-diagnostic",
                        NOW.plusSeconds(2))),
                NOW.plusSeconds(2));
        AgentError runError = new AgentError(
                AgentErrorCode.TOOL_OUTCOME_UNKNOWN, Map.of("attempts", 4), "run-diagnostic", NOW.plusSeconds(3));

        String summary = TerminalFailureSummary.create(runError, List.of(compiled, blocked), List.of(failedStep));

        assertThat(summary)
                .contains(
                        "任务未完全完成",
                        "已完成：",
                        "生成并编译静态文件服务器",
                        "未完成：",
                        "后台启动服务器",
                        "工具请求被执行安全策略拒绝",
                        "请在确认安全后手动完成",
                        "TOOL_OUTCOME_UNKNOWN",
                        "run-diagnostic")
                .doesNotContain("CANARY_RAW_SCRIPT", "CANARY_BLOCKED_COMMAND");
    }

    @Test
    void budgetThresholdsRemainBoundedAndAreNotRepeatedAfterRestore() {
        var context = new AgentLoopContext(1);
        var half = new RunBudgetSnapshot(5, 5, 5, 5_000, -1, -1, 0, "MODEL_CALLS", 5, 10, 50);
        assertThat(context.updateBudgetSnapshot(half)).containsExactly(50);
        assertThat(context.updateBudgetSnapshot(half)).isEmpty();
        var restored = new AgentLoopContext(2);
        restored.restoreBudgetThresholds(half);
        assertThat(restored.updateBudgetSnapshot(half)).isEmpty();
        var low = new RunBudgetSnapshot(1, 1, 1, 1_000, -1, -1, 0, "MODEL_CALLS", 9, 10, 10);
        assertThat(restored.updateBudgetSnapshot(low)).containsExactlyInAnyOrder(25, 10);
        assertThat(restored.budgetSnapshot()).contains(low);
    }

    @Test
    void promptTextOmitsUnconfiguredTokenQuotas() {
        var withTokens = new RunBudgetSnapshot(10, 5, 20, 30_000, 100_000, 20_000, 2, "MODEL_CALLS", 5, 10, 50);
        assertThat(withTokens.promptText())
                .isEqualTo(
                        "Remaining resource budget: modelCalls=10, toolCalls=5, iterations=20, wallTimeMillis=30000, "
                                + "inputTokens=100000, outputTokens=20000, completionRepairAttempts=2.");

        var withoutTokens = new RunBudgetSnapshot(10, 5, 20, 30_000, -1L, -1L, 2, "MODEL_CALLS", 5, 10, 50);
        assertThat(withoutTokens.promptText())
                .isEqualTo(
                        "Remaining resource budget: modelCalls=10, toolCalls=5, iterations=20, wallTimeMillis=30000, "
                                + "completionRepairAttempts=2.");
    }

    private static ToolCall requested(String id, Map<String, Object> arguments) {
        return new ToolCall(
                new ToolCallId(id),
                new AgentRunId("run-1"),
                new AgentStepId("step-1"),
                new ProviderToolCallCorrelationId("provider-" + id),
                new RuntimeIdempotencyKey("key-" + id),
                "execution.run",
                "1.0.0",
                new ToolArguments("execution.input", "1", arguments),
                NOW);
    }
}
