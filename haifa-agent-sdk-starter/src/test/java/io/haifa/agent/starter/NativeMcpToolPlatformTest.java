package io.haifa.agent.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.sdk.api.HaifaAgentException;
import io.haifa.agent.sdk.contribution.ToolRegistration;
import io.haifa.agent.tool.api.ToolApprovalRequirement;
import io.haifa.agent.tool.api.ToolExecutionMode;
import io.haifa.agent.tool.api.ToolIdempotency;
import io.haifa.agent.tool.api.ToolRisk;
import io.haifa.agent.tool.api.ToolSideEffect;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** Native MCP Client connection, discovery, governance and lifecycle behaviour. */
class NativeMcpToolPlatformTest {
    private static final URI ENDPOINT = URI.create("https://partner.example.com/mcp");
    private static final TenantRef TENANT = new TenantRef("public");
    private static final PrincipalRef PRINCIPAL = new PrincipalRef("default-public-user", "user");
    private static final Map<String, String> ENVIRONMENT = Map.of("PARTNER_MCP_TOKEN", "partner-token");

    @Test
    void importsExactlyTheAllowlistedToolsUnderTheDeclaredPrefix() {
        var mcp = new FakeMcpServer()
                .serving("enterprise-search", "search_courses", "search_policies", "search_jobs", "delete_index");

        try (var platform = connect(mcp, search().readOnly())) {
            assertThat(platform.registrations())
                    .extracting(registration -> registration.alias().value())
                    .containsExactly(
                            "enterprise_search_courses", "enterprise_search_policies", "enterprise_search_jobs");
            assertThat(platform.diagnostics()).isEmpty();
        }
    }

    @Test
    void appliesTheReadOnlyGovernancePresetAsALocalDecision() {
        var mcp = new FakeMcpServer().serving("enterprise-search", "search_jobs");

        try (var platform = connect(mcp, jobs().readOnly())) {
            var definition = platform.registrations().get(0).definition();

            assertThat(definition.risk()).isEqualTo(ToolRisk.LOW);
            assertThat(definition.idempotency()).isEqualTo(ToolIdempotency.IDEMPOTENT);
            assertThat(definition.sideEffects()).containsExactly(ToolSideEffect.NETWORK_ACCESS);
            assertThat(definition.approvalRequirement()).isEqualTo(ToolApprovalRequirement.POLICY);
            assertThat(definition.executionMode()).isEqualTo(ToolExecutionMode.REMOTE_PROVIDER);
            assertThat(definition.resources().networkHosts()).containsExactly("partner.example.com");
        }
    }

    @Test
    void keepsTheConservativeDefaultGovernanceWhenReadOnlyIsNotDeclared() {
        var mcp = new FakeMcpServer().serving("enterprise-search", "search_jobs");

        try (var platform = connect(mcp, jobs())) {
            var definition = platform.registrations().get(0).definition();

            assertThat(definition.risk()).isEqualTo(ToolRisk.HIGH);
            assertThat(definition.idempotency()).isEqualTo(ToolIdempotency.UNKNOWN);
            assertThat(definition.sideEffects())
                    .containsExactlyInAnyOrder(ToolSideEffect.NETWORK_ACCESS, ToolSideEffect.EXTERNAL_SYSTEM_MUTATION);
            assertThat(definition.approvalRequirement()).isEqualTo(ToolApprovalRequirement.ALWAYS);
        }
    }

    @Test
    void failsClosedWhenARequiredServerCannotConnect() {
        var mcp =
                new FakeMcpServer().serving("enterprise-search", "search_jobs").failing("enterprise-search");

        assertThatThrownBy(() -> connect(mcp, jobs().readOnly().required()))
                .isInstanceOf(HaifaAgentException.class)
                .extracting("code")
                .isEqualTo("MCP_SERVER_UNAVAILABLE");
    }

    @Test
    void failsClosedWhenARequiredToolIsMissingAndNeverRegistersAnUnavailableTool() {
        var mcp = new FakeMcpServer().serving("enterprise-search", "search_courses");

        assertThatThrownBy(() -> connect(mcp, search().readOnly().required()))
                .isInstanceOf(HaifaAgentException.class)
                .satisfies(failure -> {
                    var haifa = (HaifaAgentException) failure;
                    assertThat(haifa.code()).isEqualTo("MCP_TOOL_UNAVAILABLE");
                    assertThat(haifa.getMessage()).contains("search_jobs (not discovered)");
                });
    }

    @Test
    void degradesAnOptionalServerToNoToolsWithASafeDiagnostic() {
        var mcp =
                new FakeMcpServer().serving("enterprise-search", "search_jobs").failing("enterprise-search");

        try (var platform = connect(mcp, jobs().readOnly().optional())) {
            assertThat(platform.registrations()).isEmpty();
            assertThat(platform.diagnostics()).singleElement().satisfies(diagnostic -> {
                assertThat(diagnostic.code()).isEqualTo("MCP_SERVER_UNAVAILABLE");
                assertThat(diagnostic.safeMessage()).contains("enterprise-search");
            });
        }
    }

    @Test
    void injectsTheDeclaredCredentialHeaderAndKeepsTheSecretOutOfTheToolDefinition() {
        var mcp = new FakeMcpServer().serving("enterprise-search", "search_jobs");

        try (var platform = connect(
                mcp, jobs().bearerTokenFromEnvironment("PARTNER_MCP_TOKEN").readOnly())) {
            ToolRegistration registration = platform.registrations().get(0);

            assertThat(mcp.authorizationValues()).contains("Authorization: Bearer partner-token");
            assertThat(registration.definition().credentialRequirements())
                    .extracting(requirement -> requirement.credentialId())
                    .containsExactly("mcp:enterprise-search:authorization");
            assertThat(registration.definition().toString()).doesNotContain("partner-token");
            assertThat(platform.credentials().requireSecret("mcp:enterprise-search:authorization"))
                    .isEqualTo("partner-token");
        }
    }

    @Test
    void failsClosedWhenARequiredCredentialIsNotConfigured() {
        var mcp = new FakeMcpServer().serving("enterprise-search", "search_jobs");

        assertThatThrownBy(() -> NativeMcpToolPlatform.connect(
                        List.of(jobs().bearerTokenFromEnvironment("PARTNER_MCP_TOKEN")
                                .readOnly()),
                        TENANT,
                        PRINCIPAL,
                        name -> null,
                        mcp))
                .isInstanceOf(HaifaAgentException.class)
                .extracting("code")
                .isEqualTo("MCP_CREDENTIAL_UNAVAILABLE");
    }

    @Test
    void closesEveryConnectionOnceEvenWhenCloseIsCalledRepeatedly() {
        var mcp = new FakeMcpServer().serving("enterprise-search", "search_jobs");

        var platform = connect(mcp, jobs().readOnly());
        platform.close();
        platform.close();

        assertThat(mcp.openClients()).isEqualTo(1);
        assertThat(mcp.closedClients()).isEqualTo(1);
    }

    @Test
    void rejectsAServerDeclaredWithoutAnyAllowedTool() {
        var mcp = new FakeMcpServer().serving("enterprise-search", "search_jobs");

        assertThatThrownBy(() -> connect(mcp, McpServerSpec.streamableHttp("enterprise-search", ENDPOINT)))
                .isInstanceOf(HaifaAgentException.class)
                .extracting("code")
                .isEqualTo("MCP_SERVER_CONFIGURATION_INVALID");
    }

    @Test
    void rejectsTheSameConnectionNameDeclaredTwice() {
        var mcp = new FakeMcpServer().serving("enterprise-search", "search_jobs");

        assertThatThrownBy(() -> NativeMcpToolPlatform.connect(
                        List.of(jobs().readOnly(), jobs().readOnly()), TENANT, PRINCIPAL, environment(), mcp))
                .isInstanceOf(HaifaAgentException.class)
                .extracting("code")
                .isEqualTo("MCP_SERVER_NAME_CONFLICT");
    }

    @Test
    void reportsANameConflictEvenWhenTheFirstDeclarationWasAlreadyUnusable() {
        var mcp = new FakeMcpServer().serving("enterprise-search", "search_jobs");
        McpServerSpec withoutCredential = jobs().bearerTokenFromEnvironment("PARTNER_MCP_TOKEN")
                .readOnly()
                .optional();

        assertThatThrownBy(() -> NativeMcpToolPlatform.connect(
                        List.of(withoutCredential, jobs().readOnly()), TENANT, PRINCIPAL, name -> null, mcp))
                .isInstanceOf(HaifaAgentException.class)
                .extracting("code")
                .isEqualTo("MCP_SERVER_NAME_CONFLICT");
    }

    @Test
    void skipsAnOptionalServerWhoseCredentialIsMissingWithoutBreakingTheOtherServers() {
        var mcp = new FakeMcpServer()
                .serving("enterprise-search", "search_jobs")
                .serving("legacy-search", "search_legacy");
        McpServerSpec legacy = McpServerSpec.streamableHttp(
                        "legacy-search", URI.create("https://legacy.example.com/mcp"))
                .allowTools("search_legacy")
                .toolNamePrefix("legacy")
                .bearerTokenFromEnvironment("LEGACY_MCP_TOKEN")
                .readOnly()
                .optional();

        try (var platform = NativeMcpToolPlatform.connect(
                List.of(legacy, jobs().readOnly()), TENANT, PRINCIPAL, environment(), mcp)) {
            assertThat(platform.registrations())
                    .extracting(registration -> registration.alias().value())
                    .containsExactly("enterprise_search_jobs");
            assertThat(platform.diagnostics()).singleElement().satisfies(diagnostic -> {
                assertThat(diagnostic.code()).isEqualTo("MCP_CREDENTIAL_UNAVAILABLE");
                assertThat(diagnostic.safeMessage()).contains("legacy-search");
            });
        }
    }

    private static NativeMcpToolPlatform connect(FakeMcpServer mcp, McpServerSpec spec) {
        return NativeMcpToolPlatform.connect(List.of(spec), TENANT, PRINCIPAL, environment(), mcp);
    }

    private static Function<String, String> environment() {
        return ENVIRONMENT::get;
    }

    private static McpServerSpec search() {
        return McpServerSpec.streamableHttp("enterprise-search", ENDPOINT)
                .allowTools("search_courses", "search_policies", "search_jobs")
                .toolNamePrefix("enterprise");
    }

    private static McpServerSpec jobs() {
        return McpServerSpec.streamableHttp("enterprise-search", ENDPOINT)
                .allowTools("search_jobs")
                .toolNamePrefix("enterprise");
    }
}
