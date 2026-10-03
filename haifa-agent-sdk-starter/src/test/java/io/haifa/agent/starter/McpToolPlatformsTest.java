package io.haifa.agent.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.credential.api.CredentialBroker;
import io.haifa.agent.sdk.api.HaifaAgentException;
import io.haifa.agent.sdk.api.HaifaAgents;
import io.haifa.agent.sdk.contribution.CredentialPlatformContribution;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Public MCP assembly facade: owned no-op, single-apply ownership, and fail-closed connect. */
class McpToolPlatformsTest {
    private static final URI ENDPOINT = URI.create("https://partner.example.com/mcp");
    private static final TenantRef TENANT = new TenantRef("public");
    private static final PrincipalRef PRINCIPAL = new PrincipalRef("default-public-user", "user");

    @Test
    void connectsTheDeclaredServersThroughTheSharedStarterPath() {
        var mcp = new FakeMcpServer().serving("enterprise-search", "search_jobs");

        try (var platform = McpToolPlatforms.connect(List.of(jobs()), TENANT, PRINCIPAL, name -> "test-secret", mcp)) {
            assertThat(platform.toolNames()).containsExactly("enterprise_search_jobs");
            platform.applyTo(HaifaAgents.builder());

            assertThat(mcp.openClients()).isEqualTo(1);
        }
        assertThat(mcp.closedClients()).isEqualTo(1);
    }

    @Test
    void anEmptyDeclarationIsAnOwnedNoOp() {
        try (var platform = McpToolPlatforms.connect(List.of(), TENANT, PRINCIPAL)) {
            assertThat(platform.toolNames()).isEmpty();
            assertThatThrownBy(() -> platform.toolNames().add("invented"))
                    .isInstanceOf(UnsupportedOperationException.class);
            platform.applyTo(HaifaAgents.builder());
            platform.close();
        }
    }

    @Test
    void aLegacyImplementationWithoutAnAuthoritativeSnapshotFailsClosed() {
        var platform = new McpToolPlatforms.McpToolPlatform() {
            @Override
            public void applyTo(io.haifa.agent.sdk.api.HaifaAgentBuilder builder) {}

            @Override
            public void close() {}
        };
        assertThatThrownBy(platform::toolNames).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsApplyingTheSameConnectedPlatformTwice() {
        var mcp = new FakeMcpServer().serving("enterprise-search", "search_jobs");

        try (var platform = McpToolPlatforms.connect(List.of(jobs()), TENANT, PRINCIPAL, name -> "test-secret", mcp)) {
            platform.applyTo(HaifaAgents.builder());

            assertThatThrownBy(() -> platform.applyTo(HaifaAgents.builder())).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void releasesAHealthyConnectionWhenARequiredServerFailsTheConnect() {
        var mcp = new FakeMcpServer()
                .serving("healthy-status", "get_status")
                .serving("broken-status", "get_status")
                .failing("broken-status");
        McpServerSpec healthy = McpServerSpec.streamableHttp("healthy-status", ENDPOINT)
                .allowTools("get_status")
                .toolNamePrefix("healthy")
                .readOnly();
        McpServerSpec broken = McpServerSpec.streamableHttp("broken-status", ENDPOINT)
                .allowTools("get_status")
                .toolNamePrefix("broken")
                .readOnly()
                .required();

        assertThatThrownBy(() -> McpToolPlatforms.connect(
                        List.of(healthy, broken), TENANT, PRINCIPAL, name -> "test-secret", mcp))
                .isInstanceOf(HaifaAgentException.class)
                .extracting("code")
                .isEqualTo("MCP_SERVER_UNAVAILABLE");

        assertThat(mcp.openClients()).isEqualTo(2);
        assertThat(mcp.closedClients()).isEqualTo(1);
    }

    @Test
    void refusesToReplaceTheHostCredentialBroker() {
        var mcp = new FakeMcpServer().serving("enterprise-search", "search_jobs");
        var builder = HaifaAgents.builder().credentials(hostCredentials());

        try (var platform = McpToolPlatforms.connect(List.of(jobs()), TENANT, PRINCIPAL, name -> "test-secret", mcp)) {
            assertThatThrownBy(() -> platform.applyTo(builder))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("credential contribution is already set");
        }
    }

    @Test
    void aRejectedApplyLeavesThePlatformUnappliedAndOpen() {
        var mcp = new FakeMcpServer().serving("enterprise-search", "search_jobs");
        var hostBuilder = HaifaAgents.builder().credentials(hostCredentials());

        try (var platform = McpToolPlatforms.connect(List.of(jobs()), TENANT, PRINCIPAL, name -> "test-secret", mcp)) {
            assertThatThrownBy(() -> platform.applyTo(hostBuilder)).isInstanceOf(IllegalStateException.class);
            assertThat(platform.toolNames()).containsExactly("enterprise_search_jobs");

            assertThat(mcp.closedClients()).isZero();
            platform.applyTo(HaifaAgents.builder());
            assertThatThrownBy(() -> platform.applyTo(HaifaAgents.builder()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already applied");
        }
    }

    @Test
    void refusesAHostCredentialBrokerAfterTheMcpPlatformIsApplied() {
        var mcp = new FakeMcpServer().serving("enterprise-search", "search_jobs");
        var builder = HaifaAgents.builder();

        try (var platform = McpToolPlatforms.connect(List.of(jobs()), TENANT, PRINCIPAL, name -> "test-secret", mcp)) {
            platform.applyTo(builder);

            assertThatThrownBy(() -> builder.credentials(hostCredentials()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("credential contribution is already set");
        }
    }

    private static CredentialPlatformContribution hostCredentials() {
        CredentialBroker broker = id -> Optional.empty();
        return new CredentialPlatformContribution(broker);
    }

    private static McpServerSpec jobs() {
        return McpServerSpec.streamableHttp("enterprise-search", ENDPOINT)
                .allowTools("search_jobs")
                .toolNamePrefix("enterprise")
                .readOnly();
    }
}
