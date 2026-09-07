package io.haifa.agent.application.project.policy;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.execution.api.ExecutionCommand;
import io.haifa.agent.execution.api.ExecutionCommandMode;
import io.haifa.agent.execution.api.ExecutionEnvironmentRef;
import io.haifa.agent.execution.api.ExecutionId;
import io.haifa.agent.execution.api.ExecutionLimits;
import io.haifa.agent.execution.api.ExecutionOrigin;
import io.haifa.agent.execution.api.ExecutionRequest;
import io.haifa.agent.execution.api.SandboxProfileRef;
import io.haifa.agent.execution.api.TrustedExecutionContext;
import io.haifa.agent.execution.core.ExecutionRejectedException;
import io.haifa.agent.project.path.WorkspacePath;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CodingAgentExecutionPolicyTest {
    private final CodingAgentExecutionPolicy policy = new CodingAgentExecutionPolicy();

    @Test
    void allowsOnlyTheThreeCurrentlySupportedProductOrigins() {
        assertThatCode(() -> policy.authorize(request(
                        ExecutionOrigin.RUNTIME_TOOL,
                        Optional.of(new ToolCallId("tool-call")),
                        Set.of("execution.run"),
                        "sh")))
                .doesNotThrowAnyException();
        assertThatCode(() -> policy.authorize(
                        request(ExecutionOrigin.PRODUCT_USER_COMMAND, Optional.empty(), Set.of("execution.run"), "sh")))
                .doesNotThrowAnyException();
        assertThatCode(() -> policy.authorize(request(
                        ExecutionOrigin.PRODUCT_INTERNAL,
                        Optional.empty(),
                        Set.of("execution.run", "git.read"),
                        "git")))
                .doesNotThrowAnyException();
    }

    @Test
    void failsClosedForUnknownOrMalformedProductEntrypoints() {
        assertThatThrownBy(() -> policy.authorize(
                        request(ExecutionOrigin.PRODUCT_INTERNAL, Optional.empty(), Set.of("execution.run"), "git")))
                .isInstanceOf(ExecutionRejectedException.class)
                .hasMessageContaining("does not authorize");
        assertThatThrownBy(() -> policy.authorize(request(
                        ExecutionOrigin.PRODUCT_INTERNAL,
                        Optional.empty(),
                        Set.of("execution.run", "git.read"),
                        "powershell")))
                .isInstanceOf(ExecutionRejectedException.class);
    }

    private static ExecutionRequest request(
            ExecutionOrigin origin, Optional<ToolCallId> toolCallId, Set<String> capabilities, String executable) {
        WorkspaceId workspaceId = new WorkspaceId("workspace");
        return new ExecutionRequest(
                new ExecutionId("execution"),
                "idempotency",
                new TrustedExecutionContext(
                        new TenantRef("tenant"),
                        "run",
                        new PrincipalRef("actor", "user"),
                        capabilities,
                        origin,
                        toolCallId),
                workspaceId,
                WorkspacePath.root(workspaceId),
                new ExecutionCommand(ExecutionCommandMode.DIRECT, List.of(executable, "--version")),
                ExecutionEnvironmentRef.empty(),
                new ExecutionLimits(Duration.ofSeconds(10), 4096, 4096, 1),
                new SandboxProfileRef("test", "1"));
    }
}
