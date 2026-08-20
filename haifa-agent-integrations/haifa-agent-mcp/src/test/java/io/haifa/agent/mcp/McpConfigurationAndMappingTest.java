package io.haifa.agent.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.AssetRef;
import io.haifa.agent.mcp.config.McpProtocolProfile;
import io.haifa.agent.mcp.config.McpToolImportPolicy;
import io.haifa.agent.mcp.config.StdioDefinition;
import io.haifa.agent.mcp.config.StreamableHttpDefinition;
import io.haifa.agent.mcp.protocol.McpRemoteContent;
import io.haifa.agent.mcp.protocol.McpRemoteTool;
import io.haifa.agent.mcp.protocol.McpRemoteToolResult;
import io.haifa.agent.mcp.tool.InMemoryMcpToolBindingStore;
import io.haifa.agent.mcp.tool.McpContentMapper;
import io.haifa.agent.mcp.tool.McpToolDefinitionMapper;
import io.haifa.agent.tool.api.ToolDefinitionHash;
import io.haifa.agent.tool.api.ToolInvocationException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class McpConfigurationAndMappingTest {
    @Test
    void fixesProtocolAndRejectsUnsafeHttpAndHostPaths() {
        assertThatThrownBy(() -> new McpProtocolProfile("2024-11-05")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StreamableHttpDefinition(
                        URI.create("http://example.com/mcp"),
                        true,
                        Set.of("http://example.com:80"),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        4096,
                        4096))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StdioDefinition(
                        "C:\\tools\\server.exe",
                        List.of(),
                        "project",
                        Set.of(),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        4096,
                        4096))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void separatesRemoteDigestFromFrozenLocalDefinitionHashAndUsesConservativeDefaults() {
        var server = McpTestFixtures.httpServer(URI.create("http://127.0.0.1:8091/mcp"), Set.of("time_now"));
        var store = new InMemoryMcpToolBindingStore();
        var localHash = new ToolDefinitionHash("a".repeat(64));
        var mapper = new McpToolDefinitionMapper(ignored -> localHash, store);
        var remote = new McpRemoteTool(
                "time_now",
                "Time now",
                "Returns time",
                Map.of("type", "object", "properties", Map.of()),
                Map.of(),
                Map.of(),
                Map.of());

        var candidate = mapper.map(server, McpProtocolProfile.VERSION_2025_11_25, remote);

        assertThat(candidate.enabled()).isTrue();
        assertThat(candidate.alias().orElseThrow().value()).isEqualTo("utility_time_now");
        assertThat(candidate.remoteDefinitionDigest()).isNotEqualTo(localHash.value());
        assertThat(candidate.binding().orElseThrow().localDefinitionHash()).isEqualTo(localHash);
        assertThat(store.find(candidate.binding().orElseThrow().bindingReference()))
                .isPresent();
        assertThat(candidate.definition().orElseThrow().outputSchema().document())
                .containsEntry("additionalProperties", true);
    }

    @Test
    void mapsReviewedDottedRemoteNameThroughExplicitTrustedAliasAndDigestsTheMapping() {
        var endpoint = URI.create("http://127.0.0.1:8091/mcp");
        var aliases = Map.of("commerce.order.list_my_orders", "commerce_order_list_my_orders");
        var server = McpTestFixtures.httpServer(endpoint, aliases.keySet(), aliases);
        var withoutOverride = McpTestFixtures.httpServer(endpoint, Set.of("commerce_order_list_my_orders"));
        var mapper = new McpToolDefinitionMapper(
                ignored -> new ToolDefinitionHash("d".repeat(64)), new InMemoryMcpToolBindingStore());
        var remote = new McpRemoteTool(
                "commerce.order.list_my_orders",
                "List my orders",
                "Lists orders for the trusted caller",
                Map.of("type", "object"),
                Map.of("type", "object"),
                Map.of(),
                Map.of());

        var candidate = mapper.map(server, McpProtocolProfile.VERSION_2025_11_25, remote);

        assertThat(candidate.enabled()).isTrue();
        assertThat(candidate.alias().orElseThrow().value()).isEqualTo("commerce_order_list_my_orders");
        assertThat(server.bindingDigest()).isNotEqualTo(withoutOverride.bindingDigest());
    }

    @Test
    void rejectsUnknownMissingInvalidAndDuplicateAliasOverrides() {
        assertThatThrownBy(() -> policy(Set.of("reviewed"), Map.of("unknown", "safe_unknown")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the allowlist");
        assertThatThrownBy(() -> policy(Set.of("commerce.order.list_my_orders"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tool alias");
        assertThatThrownBy(() -> policy(Set.of("first", "second"), Map.of("first", "shared", "second", "shared")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique");
    }

    private static McpToolImportPolicy policy(Set<String> allowed, Map<String, String> aliases) {
        return new McpToolImportPolicy(allowed, Set.of(), "safe", Map.of(), Map.of(), Map.of(), Map.of(), aliases);
    }

    @Test
    void producesImportDiagnosticsForPatternRemoteRefOversizeAndDeniedTools() {
        var server = McpTestFixtures.httpServer(URI.create("http://localhost:8091/mcp"), Set.of("approved"));
        var mapper = new McpToolDefinitionMapper(
                ignored -> new ToolDefinitionHash("b".repeat(64)), new InMemoryMcpToolBindingStore());
        var remote = new McpRemoteTool(
                "denied",
                "Denied",
                "Denied",
                Map.of(
                        "type", "string",
                        "pattern", "a+",
                        "$ref", "https://example.com/schema"),
                Map.of("type", "object"),
                Map.of(),
                Map.of());

        var candidate = mapper.map(server, McpProtocolProfile.VERSION_2025_11_25, remote);

        assertThat(candidate.enabled()).isFalse();
        assertThat(candidate.diagnostics())
                .extracting(diagnostic -> diagnostic.code())
                .contains(
                        "MCP_TOOL_REVIEW_REQUIRED",
                        "MCP_SCHEMA_PATTERN_UNSUPPORTED",
                        "MCP_SCHEMA_REMOTE_REF_UNSUPPORTED");
    }

    @Test
    void mapsBusinessFailureWithoutApplyingSuccessSemanticsAndRejectsMedia() {
        var mapper = new McpContentMapper(value -> value.replace("secret", "[REDACTED]"));
        var failure = mapper.map(new McpRemoteToolResult(
                true,
                List.of(new McpRemoteContent(McpRemoteContent.Kind.TEXT, "secret failure", "text/plain", 0)),
                Map.of("error", "secret")));

        assertThat(failure.successful()).isFalse();
        assertThat(failure.summary()).isEqualTo("[REDACTED] failure");
        assertThat(failure.structuredData()).containsEntry("error", "[REDACTED]");
        assertThatThrownBy(() -> mapper.map(new McpRemoteToolResult(
                        false,
                        List.of(new McpRemoteContent(McpRemoteContent.Kind.IMAGE, "", "image/png", 12)),
                        Map.of())))
                .isInstanceOf(ToolInvocationException.class)
                .hasMessageContaining("Asset adapter");

        var externalizingMapper = new McpContentMapper(
                value -> value, content -> new AssetRef("asset-image", content.mimeType(), "mcp-image"));
        var mediaResult = externalizingMapper.map(new McpRemoteToolResult(
                false,
                List.of(new McpRemoteContent(McpRemoteContent.Kind.IMAGE, "aGVsbG8=", "image/png", 8)),
                Map.of()));
        assertThat(mediaResult.assets()).containsExactly(new AssetRef("asset-image", "image/png", "mcp-image"));
    }
}
