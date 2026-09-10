package io.haifa.agent.runtime.core.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.RuntimeIdempotencyKey;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.policy.api.PolicyDigest;
import io.haifa.agent.runtime.core.decision.ToolRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DefaultToolPolicyRequestAdapterTest {

    @Test
    void executionDigestUsesFrozenCapabilityRatherThanModelAlias() {
        var request = request("execution_run", "1.0.0", Map.of("command", "echo ok", "workdir", "."));

        assertThat(DefaultToolPolicyRequestAdapter.resourceDigest("execution.run", request))
                .isEqualTo(PolicyDigest.sha256Fields(List.of("echo ok", ".", "[0]")));
    }

    @Test
    void executionDigestCanonicalizesDeclaredExpectedExitCodes() {
        var request = request(
                "execution_run",
                "1.0.0",
                Map.of(
                        "command",
                        "git diff --no-index before after",
                        "workdir",
                        ".",
                        "expectedExitCodes",
                        List.of(1, 0)));

        assertThat(DefaultToolPolicyRequestAdapter.resourceDigest("execution.run", request))
                .isEqualTo(PolicyDigest.sha256Fields(List.of("git diff --no-index before after", ".", "[0, 1]")));
    }

    @Test
    void executionDigestBindsStructuredWorkspaceTargetWhenPresent() {
        var request = request(
                "execution_run",
                "2.0.0",
                Map.of(
                        "command", "git status --short", "workspaceRef", "workspace-docs", "relativeWorkdir", "docs"));

        assertThat(DefaultToolPolicyRequestAdapter.resourceDigest("execution.run", request))
                .isEqualTo(PolicyDigest.sha256Fields(List.of("git status --short", "workspace-docs", "docs", "[0]")));
    }

    private static ToolRequest request(String alias, String version, Map<String, Object> arguments) {
        return new ToolRequest(
                new ToolCallId("call"),
                new ProviderToolCallCorrelationId("provider-call"),
                new RuntimeIdempotencyKey("key"),
                alias,
                version,
                new ToolArguments("execution.input", version, arguments));
    }
}
