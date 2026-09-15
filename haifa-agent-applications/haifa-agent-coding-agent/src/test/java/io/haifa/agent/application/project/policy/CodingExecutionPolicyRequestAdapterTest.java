package io.haifa.agent.application.project.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.RuntimeIdempotencyKey;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.policy.api.PolicyAction;
import io.haifa.agent.policy.api.PolicyContext;
import io.haifa.agent.policy.api.PolicyRequest;
import io.haifa.agent.policy.api.PolicyResource;
import io.haifa.agent.policy.api.PolicyRisk;
import io.haifa.agent.policy.api.PolicyRiskLevel;
import io.haifa.agent.policy.api.PolicySideEffect;
import io.haifa.agent.policy.api.PolicySubject;
import io.haifa.agent.runtime.core.decision.ToolRequest;
import io.haifa.agent.runtime.core.tool.ToolAuthorizationProtocolException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CodingExecutionPolicyRequestAdapterTest {

    @Test
    void keepsTheGenericExecutionBaselineForEveryOrdinaryCommand() {
        PolicyRequest baseline = baseline();
        for (String command : new String[] {
            "git status --short",
            "git -C docs status",
            "git -c color.ui=false rev-parse HEAD",
            "git grep -c credential.helper -- .",
            "git push origin feature",
            "git reset --hard HEAD",
            "gh pr view 42",
            "gh pr merge 42",
            "mvn test && echo done"
        }) {
            PolicyRequest adapted = CodingExecutionPolicyRequestAdapter.applyCredentialBoundary(
                    baseline, CodingExecutionPolicyRequestAdapter.EXECUTION_RUN, request(command));
            assertThat(adapted).as(command).isSameAs(baseline);
        }
    }

    @Test
    void leavesNonExecutionToolsUntouched() {
        PolicyRequest baseline = baseline();
        assertThat(CodingExecutionPolicyRequestAdapter.applyCredentialBoundary(
                        baseline, "file_read", request("git status")))
                .isSameAs(baseline);
    }

    @Test
    void failsClosedBeforePolicyForConfirmedCredentialEgressCommands() {
        for (String command : new String[] {
            "GH_TOKEN=value gh pr list",
            "git credential fill",
            "git --no-pager credential fill",
            "git -c color.ui=false credential fill",
            "gh auth token",
            "gh auth status --show-token",
            "gh auth status -t",
            "gh auth status --show-token=true",
            "gh --hostname github.com auth token",
            "git -c credential.helper=other status"
        }) {
            assertThatThrownBy(() -> adapt(command))
                    .as(command)
                    .isInstanceOf(ToolAuthorizationProtocolException.class)
                    .satisfies(failure -> assertThat(((ToolAuthorizationProtocolException) failure).reasonCode())
                            .isNotBlank());
        }
    }

    private static PolicyRequest adapt(String command) {
        return CodingExecutionPolicyRequestAdapter.applyCredentialBoundary(
                baseline(), CodingExecutionPolicyRequestAdapter.EXECUTION_RUN, request(command));
    }

    private static PolicyRequest baseline() {
        return new PolicyRequest(
                new PolicySubject(new TenantRef("tenant"), new PrincipalRef("user", "user"), "haifa-coding-agent"),
                PolicyContext.run("run", io.haifa.agent.policy.api.ApprovalMode.ASK),
                new PolicyAction("execution_run", "invoke"),
                new PolicyResource("tool", "execution_run@1", Optional.of("0".repeat(64)), "Execution"),
                new PolicyRisk(
                        PolicyRiskLevel.HIGH, Set.of(PolicySideEffect.PROCESS_EXECUTION), false, Optional.empty()));
    }

    private static ToolRequest request(String command) {
        return new ToolRequest(
                new ToolCallId("tool-call"),
                new ProviderToolCallCorrelationId("provider-call"),
                new RuntimeIdempotencyKey("idempotency"),
                "execution_run",
                "1.0.0",
                new ToolArguments(
                        "haifa.execution.run.input",
                        "1.0.0",
                        Map.of("command", command, "operationFamily", "INSPECT")));
    }
}
