package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.application.project.policy.CodingAgentPolicyAssembly;
import io.haifa.agent.application.project.policy.CodingApprovalThreshold;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.execution.api.ExecutionCommand;
import io.haifa.agent.execution.api.ExecutionCommandMode;
import io.haifa.agent.execution.api.ExecutionEnvironmentRef;
import io.haifa.agent.execution.api.ExecutionId;
import io.haifa.agent.execution.api.ExecutionLimits;
import io.haifa.agent.execution.api.ExecutionRequest;
import io.haifa.agent.execution.api.SandboxProfileRef;
import io.haifa.agent.execution.api.TrustedExecutionContext;
import io.haifa.agent.policy.api.ApprovalMode;
import io.haifa.agent.policy.api.PolicyDecisionId;
import io.haifa.agent.policy.api.PolicyEffect;
import io.haifa.agent.project.path.WorkspacePath;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CliRepositoryBaselineSupportTest {
    @Test
    void internalBoundedGitReadDoesNotCreateASecondAskPrompt() {
        AtomicInteger ids = new AtomicInteger();
        var policy = CodingAgentPolicyAssembly.create(
                ApprovalMode.ASK,
                CodingApprovalThreshold.LOW,
                Clock.fixed(Instant.parse("2026-09-03T00:00:00Z"), ZoneOffset.UTC),
                () -> "policy-" + ids.incrementAndGet());
        WorkspaceId workspaceId = new WorkspaceId("workspace");
        var request = new ExecutionRequest(
                new ExecutionId("git-read"),
                "git-read-key",
                new TrustedExecutionContext(
                        new TenantRef("tenant"),
                        "run",
                        new PrincipalRef("actor", "user"),
                        Set.of("execution.run", "git.read"),
                        "pending"),
                workspaceId,
                WorkspacePath.root(workspaceId),
                new ExecutionCommand(ExecutionCommandMode.DIRECT, List.of("git", "status", "--porcelain=v1")),
                ExecutionEnvironmentRef.empty(),
                new ExecutionLimits(Duration.ofSeconds(15), 4096, 4096, 1),
                new SandboxProfileRef("git-read", "1"));

        String decisionRef = CliRepositoryBaselineSupport.authorize(policy, request);

        assertThat(policy.decisionsStore().find(new PolicyDecisionId(decisionRef)))
                .get()
                .extracting(decision -> decision.effect())
                .isEqualTo(PolicyEffect.ALLOW);
    }
}
