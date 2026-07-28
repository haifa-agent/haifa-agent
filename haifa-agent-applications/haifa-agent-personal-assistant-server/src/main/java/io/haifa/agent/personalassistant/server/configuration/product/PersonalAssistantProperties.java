package io.haifa.agent.personalassistant.server.configuration.product;

import java.net.URI;
import java.nio.file.Path;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("haifa.personal")
public record PersonalAssistantProperties(
        Path dataDirectory, String continuationKeyBase64, Caller caller, Model model, Mcp mcp, String localSkillRoot) {
    public PersonalAssistantProperties {
        if (dataDirectory == null) throw new IllegalArgumentException("dataDirectory is required");
        if (continuationKeyBase64 == null || continuationKeyBase64.isBlank()) {
            throw new IllegalArgumentException("HAIFA_PERSONAL_CONTINUATION_KEY is required");
        }
        if (caller == null || model == null || mcp == null) {
            throw new IllegalArgumentException("caller, model, and mcp configuration are required");
        }
        localSkillRoot = localSkillRoot == null ? "" : localSkillRoot.trim();
    }

    public record Caller(String tenant, String principal, String reviewer) {
        public Caller {
            tenant = text(tenant, "tenant");
            principal = text(principal, "principal");
            reviewer = text(reviewer, "reviewer");
        }
    }

    public record Model(
            String mode, boolean allowDeterministic, URI endpoint, String providerModelId, String credentialReference) {
        public Model {
            mode = text(mode, "model.mode").toLowerCase(java.util.Locale.ROOT);
            if (!mode.equals("remote") && !mode.equals("deterministic")) {
                throw new IllegalArgumentException("model.mode must be remote or deterministic");
            }
            if (mode.equals("deterministic") && !allowDeterministic) {
                throw new IllegalArgumentException("deterministic model requires explicit allow-deterministic=true");
            }
            if (endpoint == null || !endpoint.isAbsolute()) {
                throw new IllegalArgumentException("model.endpoint must be absolute");
            }
            providerModelId = text(providerModelId, "model.providerModelId");
            credentialReference = text(credentialReference, "model.credentialReference");
        }
    }

    public record Mcp(
            String mode,
            String address,
            int port,
            URI endpoint,
            Set<String> allowedTools,
            String aliasNamespace,
            String serverId,
            String displayName) {
        public Mcp {
            mode = text(mode, "mcp.mode").toLowerCase(java.util.Locale.ROOT);
            if (!mode.equals("embedded-echo") && !mode.equals("external")) {
                throw new IllegalArgumentException("mcp.mode must be embedded-echo or external");
            }
            address = text(address, "mcp.address");
            if (!address.equals("127.0.0.1") && !address.equals("localhost")) {
                throw new IllegalArgumentException("MCP stub must bind to loopback");
            }
            if (port < 20002 || port > 65535) {
                throw new IllegalArgumentException("MCP port must be between 20002 and 65535");
            }
            if (endpoint == null || !endpoint.isAbsolute()) {
                throw new IllegalArgumentException("mcp.endpoint must be absolute");
            }
            String host = endpoint.getHost();
            if (!"http".equalsIgnoreCase(endpoint.getScheme())
                    || host == null
                    || !Set.of("127.0.0.1", "localhost", "::1", "0:0:0:0:0:0:0:1")
                            .contains(host.toLowerCase(java.util.Locale.ROOT))) {
                throw new IllegalArgumentException("mcp.endpoint must be loopback HTTP");
            }
            if (endpoint.getPort() < 20002) {
                throw new IllegalArgumentException("mcp.endpoint port must be 20002 or higher");
            }
            allowedTools = Set.copyOf(allowedTools == null ? Set.of() : allowedTools);
            if (allowedTools.isEmpty() || allowedTools.size() > 16) {
                throw new IllegalArgumentException("mcp.allowedTools must contain 1 to 16 entries");
            }
            if (mode.equals("embedded-echo") && !allowedTools.equals(Set.of("echo"))) {
                throw new IllegalArgumentException("embedded-echo mode only allows the echo Tool");
            }
            aliasNamespace = text(aliasNamespace, "mcp.aliasNamespace");
            if (!aliasNamespace.matches("[a-z][a-z0-9_]{0,31}")) {
                throw new IllegalArgumentException("mcp.aliasNamespace is invalid");
            }
            serverId = text(serverId, "mcp.serverId");
            displayName = text(displayName, "mcp.displayName");
        }
    }

    private static String text(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 256) {
            throw new IllegalArgumentException(field + " must contain 1 to 256 characters");
        }
        return normalized;
    }
}
