package io.haifa.agent.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Public MCP Client declaration surface: safe defaults, validation and immutability. */
class McpServerSpecTest {
    private static final URI ENDPOINT = URI.create("https://partner.example.com/mcp");

    @Test
    void startsRequiredWithNoAllowedToolAndAPrefixDerivedFromTheConnectionName() {
        McpServerSpec spec = McpServerSpec.streamableHttp("enterprise-search", ENDPOINT);

        assertThat(spec.name()).isEqualTo("enterprise-search");
        assertThat(spec.allowedTools()).isEmpty();
        assertThat(spec.toolNamePrefix()).isEqualTo("enterprise_search");
        assertThat(spec.requirement()).isEqualTo(McpServerRequirement.REQUIRED);
    }

    @Test
    void keepsAllowedToolsInDeclarationOrderAndAccumulatesAcrossCalls() {
        McpServerSpec spec = McpServerSpec.streamableHttp("enterprise-search", ENDPOINT)
                .allowTools("search_courses", "search_policies")
                .allowTools(List.of("search_jobs", "search_courses"));

        assertThat(spec.allowedTools()).containsExactly("search_courses", "search_policies", "search_jobs");
    }

    @Test
    void neverMutatesAnEarlierSpec() {
        McpServerSpec base =
                McpServerSpec.streamableHttp("enterprise-search", ENDPOINT).allowTools("search_jobs");

        McpServerSpec derived = base.allowTools("search_courses")
                .toolNamePrefix("enterprise")
                .optional()
                .requestTimeout(Duration.ofSeconds(5));

        assertThat(base.allowedTools()).containsExactly("search_jobs");
        assertThat(base.toolNamePrefix()).isEqualTo("enterprise_search");
        assertThat(base.requirement()).isEqualTo(McpServerRequirement.REQUIRED);
        assertThat(derived.allowedTools()).containsExactly("search_jobs", "search_courses");
        assertThat(derived.toolNamePrefix()).isEqualTo("enterprise");
        assertThat(derived.requirement()).isEqualTo(McpServerRequirement.OPTIONAL);
    }

    @Test
    void rejectsConnectionNamesThatAreNotStableLowercaseIdentifiers() {
        assertThatThrownBy(() -> McpServerSpec.streamableHttp("Enterprise Search", ENDPOINT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lowercase stable identifier");
    }

    @Test
    void acceptsALongConnectionNameAndLetsItCarryAnExplicitPrefix() {
        String longName = "a-very-long-partner-connection-name-beyond-limits";

        McpServerSpec viaSetter =
                McpServerSpec.streamableHttp(longName, ENDPOINT).toolNamePrefix("enterprise");
        McpServerSpec viaFactory = McpServerSpec.streamableHttp(longName, ENDPOINT, "enterprise");

        assertEquals(longName, viaSetter.name());
        assertEquals("enterprise", viaSetter.toolNamePrefix());
        assertEquals("enterprise_search_jobs", viaSetter.localToolName("search_jobs"));
        assertEquals(longName, viaFactory.name());
        assertEquals("enterprise", viaFactory.toolNamePrefix());
    }

    @Test
    void reportsAMissingPrefixOnlyWhenOneIsActuallyNeeded() {
        McpServerSpec spec = McpServerSpec.streamableHttp("a-very-long-partner-connection-name-beyond-limits", ENDPOINT)
                .allowTools("search_jobs");

        assertThatThrownBy(spec::toolNamePrefix)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("declare one with toolNamePrefix(...)");
    }

    @Test
    void rejectsEndpointsThatCarryAQueryOrFragment() {
        assertThatThrownBy(() -> McpServerSpec.streamableHttp(
                        "enterprise-search", URI.create("https://partner.example.com/mcp?tenant=acme")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query or fragment");
        assertThatThrownBy(() -> McpServerSpec.streamableHttp(
                        "enterprise-search", URI.create("https://partner.example.com/mcp#section")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query or fragment");
    }

    @Test
    void rejectsCredentialHeadersOwnedByTheHttpOrMcpTransport() {
        McpServerSpec spec = McpServerSpec.streamableHttp("enterprise-search", ENDPOINT);

        for (String reserved : List.of(
                "Host",
                "Content-Length",
                "Connection",
                "Accept",
                "Content-Type",
                "MCP-Protocol-Version",
                "Mcp-Method",
                "Mcp-Session-Id",
                "Last-Event-ID")) {
            assertThatThrownBy(() -> spec.header(reserved, "PARTNER_MCP_TOKEN"))
                    .as("reserved header %s", reserved)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("owned by the HTTP or MCP transport");
        }
        assertEquals(
                "enterprise-search",
                spec.header("X-Api-Key", "PARTNER_MCP_TOKEN").name());
    }

    @Test
    void ordersCredentialHeadersDeterministicallyRegardlessOfDeclarationOrder() {
        McpServerSpec first = McpServerSpec.streamableHttp("enterprise-search", ENDPOINT)
                .allowTools("search_jobs")
                .header("X-Tenant", "PARTNER_TENANT")
                .bearerTokenFromEnvironment("PARTNER_MCP_TOKEN");
        McpServerSpec second = McpServerSpec.streamableHttp("enterprise-search", ENDPOINT)
                .allowTools("search_jobs")
                .bearerTokenFromEnvironment("PARTNER_MCP_TOKEN")
                .header("X-Tenant", "PARTNER_TENANT");

        assertEquals(bindingReference(first), bindingReference(second));
    }

    private static String bindingReference(McpServerSpec spec) {
        return spec.toServerDefinition().bindingReference();
    }

    @Test
    void rejectsToolNamePrefixesThatAreNotModelSafe() {
        McpServerSpec spec = McpServerSpec.streamableHttp("enterprise-search", ENDPOINT);

        assertThatThrownBy(() -> spec.toolNamePrefix("Enterprise"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("toolNamePrefix");
        assertThatThrownBy(() -> spec.toolNamePrefix("enterprise-search")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonPositiveTimeouts() {
        McpServerSpec spec = McpServerSpec.streamableHttp("enterprise-search", ENDPOINT);

        assertThatThrownBy(() -> spec.connectTimeout(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> spec.requestTimeout(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidHeaderAndEnvironmentVariableNames() {
        McpServerSpec spec = McpServerSpec.streamableHttp("enterprise-search", ENDPOINT);

        assertThatThrownBy(() -> spec.header("Bad Header", "PARTNER_MCP_TOKEN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("headerName");
        assertThatThrownBy(() -> spec.header("Authorization", "partner-mcp-token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("environmentVariableName");
    }

    @Test
    void keepsTheSecretOutOfTheSpecAndItsTextForm() {
        McpServerSpec spec = McpServerSpec.streamableHttp("enterprise-search", ENDPOINT)
                .allowTools("search_jobs")
                .bearerTokenFromEnvironment("PARTNER_MCP_TOKEN");

        assertThat(spec.toString()).doesNotContain("PARTNER_MCP_TOKEN").doesNotContain("Bearer");
    }
}
