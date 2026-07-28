package io.haifa.agent.personalassistant.server.configuration.product;

import java.net.URI;
import java.nio.file.Path;
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

    public record Mcp(String address, int port) {
        public Mcp {
            address = text(address, "mcp.address");
            if (!address.equals("127.0.0.1") && !address.equals("localhost")) {
                throw new IllegalArgumentException("MCP stub must bind to loopback");
            }
            if (port < 20002 || port > 65535) {
                throw new IllegalArgumentException("MCP port must be between 20002 and 65535");
            }
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
