package io.haifa.example.runtime.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UtilityMcpRuntimePlatformTest {
    @Test
    void reviewsOnlyThePureLowRiskUnitConversionTool() {
        var server = UtilityMcpRuntimePlatform.serverDefinition(URI.create("http://127.0.0.1:20002/mcp"));
        var policy = server.importPolicy();

        assertThat(policy.allowedTools()).isEqualTo(Set.of(UtilityMcpRuntimePlatform.REMOTE_TOOL_NAME));
        assertThat(policy.riskOverrides()
                        .get(UtilityMcpRuntimePlatform.REMOTE_TOOL_NAME)
                        .name())
                .isEqualTo("LOW");
        assertThat(policy.idempotencyOverrides()
                        .get(UtilityMcpRuntimePlatform.REMOTE_TOOL_NAME)
                        .name())
                .isEqualTo("PURE");
        assertThat(policy.sideEffectOverrides().get(UtilityMcpRuntimePlatform.REMOTE_TOOL_NAME))
                .isEmpty();
        assertThat(policy.approvalOverrides()
                        .get(UtilityMcpRuntimePlatform.REMOTE_TOOL_NAME)
                        .name())
                .isEqualTo("NEVER");
    }
}
