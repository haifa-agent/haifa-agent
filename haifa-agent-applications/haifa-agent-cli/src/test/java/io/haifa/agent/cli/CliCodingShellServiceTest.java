package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.application.project.product.coding.CodingShellPlan;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.policy.api.PolicyEffect;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CliCodingShellServiceTest {
    @Test
    void treatsTheTypedCommandAsTheAskDecisionApprovalButStillHonorsDeny() {
        assertThat(CliCodingShellService.planState(PolicyEffect.ALLOW)).isEqualTo(CodingShellPlan.State.READY);
        assertThat(CliCodingShellService.planState(PolicyEffect.ASK)).isEqualTo(CodingShellPlan.State.READY);
        assertThat(CliCodingShellService.planState(PolicyEffect.DENY)).isEqualTo(CodingShellPlan.State.DENIED);
    }

    @Test
    void includesSanitizedStructuredOutputWhenTheToolHeadlineOmitsIt() {
        ToolResult result = new ToolResult(
                true,
                "Command succeeded (exit 0)",
                Map.of("output", "D:/workspace/haifa-agent\n"),
                List.of(),
                List.of(),
                false);

        assertThat(CliCodingShellService.displaySummary(result))
                .isEqualTo("Command succeeded (exit 0)\nD:/workspace/haifa-agent");
    }

    @Test
    void doesNotDuplicateOutputAlreadyPresentInTheSafeSummary() {
        ToolResult result = new ToolResult(
                true,
                "Command succeeded (exit 0)\nD:/workspace/haifa-agent",
                Map.of("output", "D:/workspace/haifa-agent"),
                List.of(),
                List.of(),
                false);

        assertThat(CliCodingShellService.displaySummary(result))
                .isEqualTo("Command succeeded (exit 0)\nD:/workspace/haifa-agent");
    }
}
