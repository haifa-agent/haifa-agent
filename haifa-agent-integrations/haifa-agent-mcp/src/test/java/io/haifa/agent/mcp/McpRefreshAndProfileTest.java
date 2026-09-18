package io.haifa.agent.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.mcp.config.CodingAgentMcpProfile;
import io.haifa.agent.mcp.config.McpServerId;
import io.haifa.agent.mcp.tool.McpDiscoveryContext;
import io.haifa.agent.mcp.tool.McpToolCatalogCandidateSnapshot;
import io.haifa.agent.mcp.tool.McpToolRefreshCoordinator;
import io.haifa.agent.tool.api.ToolApprovalRequirement;
import io.haifa.agent.tool.api.ToolSideEffect;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class McpRefreshAndProfileTest {
    @Test
    void debouncesListChangedIntoANewCandidateSnapshotGeneration() throws Exception {
        McpServerId serverId = new McpServerId("utility");
        McpDiscoveryContext context =
                new McpDiscoveryContext(McpTestFixtures.TENANT, McpTestFixtures.PRINCIPAL, List.of());
        AtomicInteger discoveries = new AtomicInteger();
        AtomicReference<McpToolCatalogCandidateSnapshot> emitted = new AtomicReference<>();
        CountDownLatch refreshed = new CountDownLatch(1);
        try (var coordinator = new McpToolRefreshCoordinator(
                (id, ignored) -> {
                    discoveries.incrementAndGet();
                    return List.of();
                },
                Map.of(serverId, context),
                Clock.fixed(Instant.parse("2026-07-22T00:00:00Z"), ZoneOffset.UTC),
                Duration.ofMillis(30),
                (snapshot, error) -> {
                    assertThat(error).isNull();
                    emitted.set(snapshot);
                    refreshed.countDown();
                })) {
            coordinator.signal(serverId);
            coordinator.signal(serverId);
            coordinator.signal(serverId);

            assertThat(refreshed.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(discoveries).hasValue(1);
            assertThat(emitted.get().generation()).isEqualTo(1);
            assertThat(coordinator.latest(serverId)).contains(emitted.get());
        }
    }

    @Test
    void codingAgentProfileAllowsReviewedUtilitySubsetOnly() {
        var policy = CodingAgentMcpProfile.utilityPolicy();

        assertThat(policy.permits("calculate")).isTrue();
        assertThat(policy.permits("microsoft_docs_search")).isTrue();
        assertThat(policy.permits("weather_current")).isFalse();
        assertThat(policy.sideEffectOverrides().get("microsoft_docs_search"))
                .containsExactly(ToolSideEffect.NETWORK_ACCESS);
        assertThat(policy.approvalOverrides().get("microsoft_docs_search")).isEqualTo(ToolApprovalRequirement.POLICY);
    }
}
