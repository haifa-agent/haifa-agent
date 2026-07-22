package io.haifa.agent.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.credential.api.CredentialDefinitionId;
import io.haifa.agent.credential.api.CredentialExposureMode;
import io.haifa.agent.credential.api.CredentialRequirement;
import io.haifa.agent.mcp.client.McpCompatibilityReport;
import io.haifa.agent.mcp.client.SdkMcpClientFactory;
import io.haifa.agent.mcp.config.McpConnectionPolicy;
import io.haifa.agent.mcp.config.McpCredentialInjection;
import io.haifa.agent.mcp.config.McpProtocolProfile;
import io.haifa.agent.mcp.config.McpServerDefinition;
import io.haifa.agent.mcp.config.McpServerId;
import io.haifa.agent.mcp.config.McpToolImportPolicy;
import io.haifa.agent.mcp.config.StreamableHttpDefinition;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class UtilityMcpCompatibilityLiveIT {
    @Test
    void verifiesSdk2ClientAgainstUtilitySdk0183WireContract() throws Exception {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("HAIFA_UTILITY_MCP_TEST")));
        URI endpoint = URI.create(
                Optional.ofNullable(System.getenv("HAIFA_UTILITY_MCP_URL")).orElse("http://127.0.0.1:8091/mcp"));
        String approvedOrigin = Optional.ofNullable(System.getenv("HAIFA_UTILITY_MCP_ORIGIN"))
                .orElse(StreamableHttpDefinition.origin(endpoint));
        String token = System.getenv("HAIFA_UTILITY_MCP_TOKEN");
        List<McpCredentialInjection> injections = token == null || token.isBlank()
                ? List.of()
                : List.of(new McpCredentialInjection(
                        new CredentialRequirement(
                                new CredentialDefinitionId("utility-live-token"),
                                "utility compatibility",
                                Set.of("mcp:tools:list", "mcp:tools:call"),
                                CredentialExposureMode.HTTP_HEADER),
                        "Authorization",
                        "Bearer "));
        Set<String> expected = expectedTools();
        McpServerDefinition server = McpServerDefinition.create(
                new McpServerId("utility-live"),
                "Utility MCP compatibility target",
                true,
                McpProtocolProfile.FIXED_2025_11_25,
                new StreamableHttpDefinition(
                        endpoint,
                        true,
                        Set.of(approvedOrigin),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(15),
                        Duration.ofSeconds(30),
                        4 * 1024 * 1024,
                        32 * 1024),
                new McpToolImportPolicy(expected, Set.of(), "utility", Map.of(), Map.of(), Map.of(), Map.of()),
                new McpConnectionPolicy(
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(15),
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(5),
                        1),
                injections,
                "1.0.0");
        var leases = token == null || token.isBlank()
                ? List.<io.haifa.agent.credential.api.CredentialLease>of()
                : List.of(McpTestFixtures.lease("utility-live-binding", token));
        var client = new SdkMcpClientFactory().create(server, McpTestFixtures.IDENTITY);
        try {
            var snapshot = client.initialize(leases);
            List<String> names = new ArrayList<>();
            String cursor = null;
            do {
                var page = client.listTools(cursor, leases);
                names.addAll(page.tools().stream().map(tool -> tool.name()).toList());
                cursor = page.nextCursor().orElse(null);
            } while (cursor != null);
            var observer = io.haifa.agent.tool.api.ToolInvocationObserver.noop();
            var time = client.callTool("time_now", Map.of("timezone", "UTC"), leases, observer);
            var calculate = client.callTool("calculate", Map.of("expression", "1 + 2 * 3"), leases, observer);
            var invalid = client.callTool("time_now", Map.of("timezone", "Invalid/Timezone"), leases, observer);
            var report = new McpCompatibilityReport(
                    server.serverId(),
                    server.protocol().targetVersion(),
                    snapshot.negotiatedProtocolVersion(),
                    "2.0.0",
                    "0.18.3",
                    names.stream().sorted().toList(),
                    token == null || token.isBlank(),
                    token != null && !token.isBlank(),
                    Instant.now());

            assertThat(snapshot.negotiatedProtocolVersion()).isEqualTo("2025-11-25");
            assertThat(snapshot.toolsCapability()).isTrue();
            assertThat(snapshot.resourcesCapability()).isFalse();
            assertThat(snapshot.promptsCapability()).isFalse();
            assertThat(names).containsExactlyInAnyOrderElementsOf(expected);
            assertThat(time.error()).isFalse();
            assertThat(time.content()).isNotEmpty();
            assertThat(time.structuredContent()).isNotEmpty();
            assertThat(calculate.error()).isFalse();
            assertThat(invalid.error()).isTrue();
            assertThat(report.discoveredTools())
                    .containsExactlyElementsOf(expected.stream().sorted().toList());
            assertThat(report.clientSdkVersion()).isEqualTo("2.0.0");
            assertThat(report.serverCompatibilityBaseline()).isEqualTo("0.18.3");
            assertThat(report.getClass().getRecordComponents())
                    .extracting(java.lang.reflect.RecordComponent::getName)
                    .doesNotContain("credential", "secret", "rawResponse", "responseBody");
        } finally {
            client.close();
            leases.forEach(io.haifa.agent.credential.api.CredentialLease::close);
        }
    }

    private static Set<String> expectedTools() throws Exception {
        try (var input = UtilityMcpCompatibilityLiveIT.class.getResourceAsStream("/contracts/utility-tools-v1.json")) {
            Map<String, Object> contract = new ObjectMapper().readValue(input, new TypeReference<>() {});
            return Set.copyOf(contract.keySet());
        }
    }
}
