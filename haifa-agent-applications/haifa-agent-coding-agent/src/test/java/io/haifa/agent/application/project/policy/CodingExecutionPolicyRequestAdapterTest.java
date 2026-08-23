package io.haifa.agent.application.project.policy;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.RuntimeIdempotencyKey;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.policy.api.ApprovalMode;
import io.haifa.agent.policy.api.PolicyAction;
import io.haifa.agent.policy.api.PolicyContext;
import io.haifa.agent.policy.api.PolicyRequest;
import io.haifa.agent.policy.api.PolicyResource;
import io.haifa.agent.policy.api.PolicyRisk;
import io.haifa.agent.policy.api.PolicyRiskLevel;
import io.haifa.agent.policy.api.PolicySideEffect;
import io.haifa.agent.policy.api.PolicySubject;
import io.haifa.agent.runtime.core.decision.ToolRequest;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CodingExecutionPolicyRequestAdapterTest {
    @Test
    void resolvesDirectGitAndGithubCommandsIntoEffectivePolicyRisk() {
        PolicyRequest localRead = adapt("git status --short");
        PolicyRequest localWrite = adapt("git add src/Main.java");
        PolicyRequest networkRead = adapt("gh pr list --repo owner/repo");
        PolicyRequest fetch = adapt("git fetch origin");
        PolicyRequest ghApi = adapt("gh api repos/owner/repo");
        PolicyRequest externalWrite = adapt("git push origin feature");

        assertThat(localRead.risk().level()).isEqualTo(PolicyRiskLevel.LOW);
        assertThat(localRead.risk().sideEffects()).containsExactly(PolicySideEffect.PROCESS_EXECUTION);
        assertThat(localWrite.risk().level()).isEqualTo(PolicyRiskLevel.MEDIUM);
        assertThat(localWrite.risk().sideEffects())
                .contains(PolicySideEffect.PROCESS_EXECUTION, PolicySideEffect.FILE_WRITE);
        assertThat(networkRead.risk().level()).isEqualTo(PolicyRiskLevel.MEDIUM);
        assertThat(networkRead.risk().sideEffects()).contains(PolicySideEffect.NETWORK_ACCESS);
        assertThat(fetch.risk().level()).isEqualTo(PolicyRiskLevel.MEDIUM);
        assertThat(fetch.risk().sideEffects()).contains(PolicySideEffect.FILE_WRITE, PolicySideEffect.NETWORK_ACCESS);
        assertThat(ghApi.risk().level()).isEqualTo(PolicyRiskLevel.HIGH);
        assertThat(externalWrite.risk().level()).isEqualTo(PolicyRiskLevel.HIGH);
        assertThat(externalWrite.risk().sideEffects())
                .contains(PolicySideEffect.NETWORK_ACCESS, PolicySideEffect.EXTERNAL_SYSTEM_MUTATION);
    }

    @Test
    void treatsCompositionAsHighRiskButPreservesHardDenialsAsCritical() {
        PolicyRequest compound = adapt("git status && git push origin feature");
        PolicyRequest protectedOverride = adapt("GH_TOKEN=value gh pr list && echo done");

        assertThat(compound.risk().level()).isEqualTo(PolicyRiskLevel.HIGH);
        assertThat(compound.risk().sideEffects())
                .contains(
                        PolicySideEffect.FILE_WRITE,
                        PolicySideEffect.NETWORK_ACCESS,
                        PolicySideEffect.EXTERNAL_SYSTEM_MUTATION);
        assertThat(protectedOverride.risk().level()).isEqualTo(PolicyRiskLevel.CRITICAL);
    }

    @Test
    void sendsShellCompositionWrappersAndUnknownGitToHighRiskPolicy() {
        for (String command : Set.of(
                "git status; git log -1",
                "git status && git log -1",
                "git status || git log -1",
                "git status | Out-String",
                "git status > status.txt",
                "git status\ngit log -1",
                "$(git status)",
                "powershell -Command \"git status\"",
                "git frobnicate")) {
            assertThat(adapt(command).risk().level()).as(command).isEqualTo(PolicyRiskLevel.HIGH);
        }
        assertThat(adapt("git push --force origin feature").risk().level()).isEqualTo(PolicyRiskLevel.HIGH);
    }

    @Test
    void freezesTheClassifierAssessmentWithoutChangingTheInvocationResourceDigest() {
        PolicyRequest status = adapt("git status --short");
        PolicyRequest push = adapt("git push origin feature");

        assertThat(status.resource()).isEqualTo(baseline().resource());
        assertThat(status.context().securityConfigurationDigest()).isPresent();
        assertThat(status.context().securityConfigurationDigest())
                .isNotEqualTo(push.context().securityConfigurationDigest());
    }

    @Test
    void keepsGenericCommandsAtTheStaticExecutionBaseline() {
        PolicyRequest generic = adapt("mvn test && echo done");

        assertThat(generic.risk().level()).isEqualTo(PolicyRiskLevel.HIGH);
        assertThat(generic.risk().sideEffects()).containsExactly(PolicySideEffect.PROCESS_EXECUTION);
    }

    private static PolicyRequest adapt(String command) {
        return CodingExecutionPolicyRequestAdapter.withEffectiveExecutionRisk(
                baseline(), CodingExecutionPolicyRequestAdapter.EXECUTION_RUN, request(command));
    }

    private static PolicyRequest baseline() {
        return new PolicyRequest(
                new PolicySubject(new TenantRef("tenant"), new PrincipalRef("user", "user"), "haifa-coding-agent"),
                PolicyContext.run("run", ApprovalMode.ASK),
                new PolicyAction("execution.run", "invoke"),
                new PolicyResource("tool", "execution.run@1", Optional.of("0".repeat(64)), "Execution"),
                new PolicyRisk(
                        PolicyRiskLevel.HIGH, Set.of(PolicySideEffect.PROCESS_EXECUTION), false, Optional.empty()));
    }

    private static ToolRequest request(String command) {
        return new ToolRequest(
                new ToolCallId("tool-call"),
                new ProviderToolCallCorrelationId("provider-call"),
                new RuntimeIdempotencyKey("idempotency"),
                "execution.run",
                "1.0.0",
                new ToolArguments(
                        "haifa.execution.run.input",
                        "1.0.0",
                        Map.of("command", command, "operationFamily", "INSPECT")));
    }
}
