package io.haifa.agent.personalassistant.server.configuration.product;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties("haifa.personal")
public record PersonalAssistantProperties(
        Path dataDirectory,
        String continuationKeyBase64,
        Caller caller,
        List<ModelProvider> modelProviders,
        String defaultModelId,
        Web web,
        Mcp mcp,
        Execution execution,
        String localSkillRoot,
        String trustedScriptManifest) {
    @ConstructorBinding
    public PersonalAssistantProperties {
        if (dataDirectory == null) throw new IllegalArgumentException("dataDirectory is required");
        if (continuationKeyBase64 == null || continuationKeyBase64.isBlank()) {
            throw new IllegalArgumentException("HAIFA_PERSONAL_CONTINUATION_KEY is required");
        }
        if (caller == null || web == null || mcp == null || execution == null) {
            throw new IllegalArgumentException("caller, web, mcp, and execution configuration are required");
        }
        if (modelProviders == null || modelProviders.isEmpty()) {
            throw new IllegalArgumentException("modelProviders must not be empty");
        }
        modelProviders = List.copyOf(modelProviders);
        if (modelProviders.stream().map(ModelProvider::id).distinct().count() != modelProviders.size()) {
            throw new IllegalArgumentException("model provider ids must be unique");
        }
        List<ProviderModel> configuredModels = modelProviders.stream()
                .flatMap(provider -> provider.models().stream())
                .toList();
        if (configuredModels.stream().map(ProviderModel::id).distinct().count() != configuredModels.size()) {
            throw new IllegalArgumentException("model ids must be globally unique");
        }
        defaultModelId = text(defaultModelId, "defaultModelId");
        String selectedDefaultModelId = defaultModelId;
        if (configuredModels.stream().noneMatch(value -> value.id().equals(selectedDefaultModelId))) {
            throw new IllegalArgumentException("defaultModelId must identify a configured model");
        }
        localSkillRoot = localSkillRoot == null ? "" : localSkillRoot.trim();
        trustedScriptManifest = trustedScriptManifest == null ? "" : trustedScriptManifest.trim();
    }

    public record Web(
            boolean enabled,
            String credentialReference,
            long timeoutMillis,
            int searchMaximumResponseBytes,
            int fetchMaximumResponseBytes) {
        public Web {
            credentialReference = text(credentialReference, "web.credentialReference");
            if (!credentialReference.startsWith("env://") || credentialReference.length() == "env://".length()) {
                throw new IllegalArgumentException("web.credentialReference must use env://");
            }
            if (timeoutMillis < 1000 || timeoutMillis > 120_000) {
                throw new IllegalArgumentException("web.timeoutMillis must be between 1000 and 120000");
            }
            if (searchMaximumResponseBytes < 1024 || searchMaximumResponseBytes > 16 * 1024 * 1024) {
                throw new IllegalArgumentException("web.searchMaximumResponseBytes is out of range");
            }
            if (fetchMaximumResponseBytes < 1024 || fetchMaximumResponseBytes > 16 * 1024 * 1024) {
                throw new IllegalArgumentException("web.fetchMaximumResponseBytes is out of range");
            }
        }
    }

    public record Execution(
            long defaultTimeoutMillis,
            long maximumTimeoutMillis,
            int maximumOutputBytes,
            int maximumOutputLines,
            int maximumProcesses,
            boolean trustedHostEnabled,
            String pythonPath,
            String powerShellPath) {
        public Execution {
            if (defaultTimeoutMillis < 1000
                    || maximumTimeoutMillis < defaultTimeoutMillis
                    || maximumTimeoutMillis > 30_000) {
                throw new IllegalArgumentException("execution timeouts must be between 1000 and 30000 milliseconds");
            }
            if (maximumOutputBytes < 1024 || maximumOutputBytes > 1024 * 1024) {
                throw new IllegalArgumentException("execution.maximumOutputBytes is out of range");
            }
            if (maximumOutputLines < 1 || maximumOutputLines > 10_000) {
                throw new IllegalArgumentException("execution.maximumOutputLines is out of range");
            }
            if (maximumProcesses < 1 || maximumProcesses > 64) {
                throw new IllegalArgumentException("execution.maximumProcesses is out of range");
            }
            if (!trustedHostEnabled) {
                throw new IllegalArgumentException(
                        "Host Guarded execution requires explicit execution.trustedHostEnabled=true");
            }
            pythonPath = pythonPath == null ? "" : pythonPath.trim();
            powerShellPath = powerShellPath == null ? "" : powerShellPath.trim();
        }
    }

    public record Caller(String tenant, String principal, String reviewer) {
        public Caller {
            tenant = text(tenant, "tenant");
            principal = text(principal, "principal");
            reviewer = text(reviewer, "reviewer");
        }
    }

    public record ModelProvider(
            String id,
            String displayName,
            String mode,
            boolean allowDeterministic,
            URI endpoint,
            String credentialReference,
            List<ProviderModel> models) {
        @ConstructorBinding
        public ModelProvider {
            id = text(id, "modelProvider.id");
            displayName = text(displayName == null ? id : displayName, "modelProvider.displayName");
            mode = text(mode, "modelProvider.mode").toLowerCase(java.util.Locale.ROOT);
            if (!mode.equals("remote") && !mode.equals("deterministic")) {
                throw new IllegalArgumentException("modelProvider.mode must be remote or deterministic");
            }
            if (mode.equals("deterministic") && !allowDeterministic) {
                throw new IllegalArgumentException(
                        "deterministic model provider requires explicit allow-deterministic=true");
            }
            if (endpoint == null || !endpoint.isAbsolute()) {
                throw new IllegalArgumentException("modelProvider.endpoint must be absolute");
            }
            credentialReference = text(credentialReference, "modelProvider.credentialReference");
            models = List.copyOf(models == null ? List.of() : models);
            if (models.isEmpty()) {
                throw new IllegalArgumentException("modelProvider.models must not be empty");
            }
            if (models.stream().map(ProviderModel::id).distinct().count() != models.size()) {
                throw new IllegalArgumentException("model ids within a provider must be unique");
            }
        }
    }

    public record ProviderModel(String id, String displayName, String providerModelId) {
        public ProviderModel {
            id = text(id, "providerModel.id");
            displayName = text(displayName == null ? id : displayName, "providerModel.displayName");
            providerModelId = text(providerModelId, "providerModel.providerModelId");
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
            if (allowedTools.isEmpty() || allowedTools.size() > 32) {
                throw new IllegalArgumentException("mcp.allowedTools must contain 1 to 32 entries");
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
