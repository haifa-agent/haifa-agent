package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThatCode;

import io.haifa.agent.application.project.policy.CodingAgentExecutionPolicy;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.execution.api.ExecutionCommand;
import io.haifa.agent.execution.api.ExecutionCommandMode;
import io.haifa.agent.execution.api.ExecutionEnvironmentRef;
import io.haifa.agent.execution.api.ExecutionId;
import io.haifa.agent.execution.api.ExecutionLimits;
import io.haifa.agent.execution.api.ExecutionOrigin;
import io.haifa.agent.execution.api.ExecutionRequest;
import io.haifa.agent.execution.api.SandboxProfileRef;
import io.haifa.agent.execution.api.TrustedExecutionContext;
import io.haifa.agent.project.path.WorkspacePath;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CliRepositoryBaselineSupportTest {
    @Test
    void internalBoundedGitReadDoesNotRequireAnApprovalBearer() {
        WorkspaceId workspaceId = new WorkspaceId("workspace");
        var request = new ExecutionRequest(
                new ExecutionId("git-read"),
                "git-read-key",
                new TrustedExecutionContext(
                        new TenantRef("tenant"),
                        "run",
                        new PrincipalRef("actor", "user"),
                        Set.of("execution.run", "git.read"),
                        ExecutionOrigin.PRODUCT_INTERNAL,
                        Optional.empty()),
                workspaceId,
                WorkspacePath.root(workspaceId),
                new ExecutionCommand(ExecutionCommandMode.DIRECT, List.of("git", "status", "--porcelain=v1")),
                ExecutionEnvironmentRef.empty(),
                new ExecutionLimits(Duration.ofSeconds(15), 4096, 4096, 1),
                new SandboxProfileRef("git-read", "1"));

        assertThatCode(() -> new CodingAgentExecutionPolicy().authorize(request))
                .doesNotThrowAnyException();
    }
}
