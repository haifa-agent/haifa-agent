package io.haifa.agent.personalassistant.server.configuration.product;

import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelReasoningMode;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "haifa.personal", ignoreUnknownFields = false)
public record PersonalAssistantProperties(
        Path dataDirectory,
        String continuationKeyBase64,
        Caller caller,
        List<ModelProvider> modelProviders,
        String defaultModelId,
        boolean allowInsecureLoopbackModel,
        Web web,
        Mission mission,
        Research research,
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
        if (caller == null || web == null || mission == null || research == null || mcp == null || execution == null) {
            throw new IllegalArgumentException(
                    "caller, web, mission, research, mcp, and execution configuration are required");
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

    public record Mission(
            String plannerMode,
            int maxTasks,
            int maxDependencyDepth,
            int maxAcceptanceCriteria,
            int globalActiveTaskRuns,
            int maxAutoAttemptsPerTask,
            long maxWallClockMillis,
            long maxModelTokens,
            long maxToolCalls,
            int maxArtifacts,
            long maxTotalArtifactBytes,
            long dispatcherPollMillis,
            long dispatcherShutdownTimeoutMillis,
            int recoveryBatchSize,
            long dbWarningBytes,
            long dbStopBytes,
            long artifactWarningBytes,
            long artifactStopBytes) {
        public static final long MAX_WALL_CLOCK_MILLIS = 2 * 60 * 60_000L;

        public Mission {
            plannerMode = text(plannerMode, "mission.plannerMode").toLowerCase(java.util.Locale.ROOT);
            if (!plannerMode.equals("runtime") && !plannerMode.equals("deterministic-stub")) {
                throw new IllegalArgumentException("mission.plannerMode must be runtime or deterministic-stub");
            }
            if (maxTasks < 1 || maxTasks > 16) {
                throw new IllegalArgumentException("mission.maxTasks must be between 1 and 16");
            }
            if (maxDependencyDepth < 1 || maxDependencyDepth > 8) {
                throw new IllegalArgumentException("mission.maxDependencyDepth must be between 1 and 8");
            }
            if (maxAcceptanceCriteria < 1 || maxAcceptanceCriteria > 20) {
                throw new IllegalArgumentException("mission.maxAcceptanceCriteria must be between 1 and 20");
            }
            if (globalActiveTaskRuns != 1) {
                throw new IllegalArgumentException("mission.globalActiveTaskRuns must equal 1 in the MVP");
            }
            if (maxAutoAttemptsPerTask < 1 || maxAutoAttemptsPerTask > 2) {
                throw new IllegalArgumentException("mission.maxAutoAttemptsPerTask must be between 1 and 2");
            }
            if (maxWallClockMillis < 1 || maxWallClockMillis > MAX_WALL_CLOCK_MILLIS) {
                throw new IllegalArgumentException("mission.maxWallClockMillis must not exceed 2 hours");
            }
            if (maxModelTokens < 1 || maxModelTokens > 4_000_000) {
                throw new IllegalArgumentException("mission.maxModelTokens must be between 1 and 4000000");
            }
            if (maxToolCalls < 1 || maxToolCalls > 400) {
                throw new IllegalArgumentException("mission.maxToolCalls must be between 1 and 400");
            }
            if (maxArtifacts < 5 || maxArtifacts > 8) {
                throw new IllegalArgumentException("mission.maxArtifacts must be between 5 and 8");
            }
            if (maxTotalArtifactBytes < 1024 * 1024L || maxTotalArtifactBytes > 4 * 1024 * 1024L) {
                throw new IllegalArgumentException("mission.maxTotalArtifactBytes must be between 1 MiB and 4 MiB");
            }
            if (dispatcherPollMillis < 100 || dispatcherPollMillis > 500) {
                throw new IllegalArgumentException("mission.dispatcherPollMillis must be between 100 and 500");
            }
            if (dispatcherShutdownTimeoutMillis < 1_000 || dispatcherShutdownTimeoutMillis > 20_000) {
                throw new IllegalArgumentException(
                        "mission.dispatcherShutdownTimeoutMillis must be between 1000 and 20000");
            }
            if (recoveryBatchSize < 1 || recoveryBatchSize > 100) {
                throw new IllegalArgumentException("mission.recoveryBatchSize must be between 1 and 100");
            }
            if (dbWarningBytes < 1 || dbStopBytes < dbWarningBytes || dbStopBytes > 1024L * 1024 * 1024) {
                throw new IllegalArgumentException("mission database capacity thresholds are invalid");
            }
            if (artifactWarningBytes < 1
                    || artifactStopBytes < artifactWarningBytes
                    || artifactStopBytes > 2L * 1024 * 1024 * 1024) {
                throw new IllegalArgumentException("mission Artifact capacity thresholds are invalid");
            }
        }
    }

    public record Research(int maxSources, int maxSourceContentBytes, int maxTotalContentBytes) {
        public Research {
            if (maxSources < 2 || maxSources > 24) {
                throw new IllegalArgumentException("research.maxSources must be between 2 and 24");
            }
            if (maxSourceContentBytes < 1024 || maxSourceContentBytes > 262_144) {
                throw new IllegalArgumentException("research.maxSourceContentBytes is out of range");
            }
            if (maxTotalContentBytes < maxSourceContentBytes || maxTotalContentBytes > 2_097_152) {
                throw new IllegalArgumentException("research.maxTotalContentBytes is out of range");
            }
        }
    }

    public record Web(WebProvider search, WebProvider fetch) {
        public Web {
            if (search == null || fetch == null) {
                throw new IllegalArgumentException("web.search and web.fetch configuration are required");
            }
            if (!Set.of("aliyun", "brave", "tavily").contains(search.providerId())) {
                throw new IllegalArgumentException("web.search.providerId must be aliyun, brave, or tavily");
            }
            if (!Set.of("aliyun", "browserless", "tavily").contains(fetch.providerId())) {
                throw new IllegalArgumentException("web.fetch.providerId must be aliyun, browserless, or tavily");
            }
        }

        public boolean enabled() {
            return search.enabled() || fetch.enabled();
        }
    }

    public record WebProvider(
            boolean enabled,
            String providerId,
            URI endpoint,
            String credentialReference,
            long timeoutMillis,
            int maximumResponseBytes) {
        public WebProvider {
            providerId = identifier(providerId, "web.providerId");
            if (endpoint == null
                    || !endpoint.isAbsolute()
                    || endpoint.getHost() == null
                    || !endpoint.getScheme().equalsIgnoreCase("https")) {
                throw new IllegalArgumentException("web.endpoint must be an absolute HTTPS URI");
            }
            credentialReference = text(credentialReference, "web.credentialReference");
            if (!credentialReference.startsWith("env://") || credentialReference.length() == "env://".length()) {
                throw new IllegalArgumentException("web.credentialReference must use env://");
            }
            if (timeoutMillis < 1000 || timeoutMillis > 120_000) {
                throw new IllegalArgumentException("web.timeoutMillis must be between 1000 and 120000");
            }
            if (maximumResponseBytes < 1024 || maximumResponseBytes > 16 * 1024 * 1024) {
                throw new IllegalArgumentException("web.maximumResponseBytes is out of range");
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
            boolean nativeStreaming,
            URI endpoint,
            String credentialReference,
            List<ApiBinding> apiBindings,
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
            if (mode.equals("deterministic")) nativeStreaming = false;
            if (endpoint == null || !endpoint.isAbsolute()) {
                throw new IllegalArgumentException("modelProvider.endpoint must be absolute");
            }
            credentialReference = text(credentialReference, "modelProvider.credentialReference");
            apiBindings = List.copyOf(apiBindings == null ? List.of() : apiBindings);
            if (apiBindings.isEmpty())
                throw new IllegalArgumentException("modelProvider.apiBindings must not be empty");
            if (apiBindings.stream().map(ApiBinding::style).distinct().count() != apiBindings.size()) {
                throw new IllegalArgumentException("API styles within a provider must be unique");
            }
            models = List.copyOf(models == null ? List.of() : models);
            if (models.isEmpty()) {
                throw new IllegalArgumentException("modelProvider.models must not be empty");
            }
            if (models.stream().map(ProviderModel::id).distinct().count() != models.size()) {
                throw new IllegalArgumentException("model ids within a provider must be unique");
            }
            Set<String> styles =
                    apiBindings.stream().map(ApiBinding::style).collect(java.util.stream.Collectors.toSet());
            if (models.stream().anyMatch(model -> !styles.contains(model.style()))) {
                throw new IllegalArgumentException("model references an unbound API style");
            }
        }
    }

    public record ApiBinding(String style, String dialect, URI endpoint) {
        @ConstructorBinding
        public ApiBinding {
            style = identifier(style, "apiBinding.style");
            dialect = dialect == null || dialect.isBlank() ? "standard" : identifier(dialect, "apiBinding.dialect");
            if (endpoint != null && !endpoint.isAbsolute()) {
                throw new IllegalArgumentException("apiBinding.endpoint must be absolute");
            }
        }
    }

    public record ProviderModel(
            String id,
            String displayName,
            String modelDisplayName,
            String providerModelId,
            String style,
            Set<ModelCapability> capabilities,
            ModelReasoningMode reasoningMode,
            int contextWindow,
            int maxOutputTokens) {
        @ConstructorBinding
        public ProviderModel {
            id = text(id, "providerModel.id");
            displayName = text(displayName == null ? id : displayName, "providerModel.displayName");
            modelDisplayName =
                    text(modelDisplayName == null ? displayName : modelDisplayName, "providerModel.modelDisplayName");
            providerModelId = text(providerModelId, "providerModel.providerModelId");
            style = identifier(style, "providerModel.style");
            capabilities = Set.copyOf(capabilities == null ? Set.of() : capabilities);
            if (capabilities.isEmpty())
                throw new IllegalArgumentException("providerModel.capabilities must not be empty");
            reasoningMode = reasoningMode == null ? ModelReasoningMode.DISABLED : reasoningMode;
            if (reasoningMode != ModelReasoningMode.DISABLED && !capabilities.contains(ModelCapability.REASONING)) {
                throw new IllegalArgumentException("providerModel reasoning mode requires REASONING capability");
            }
            if (contextWindow < 1 || maxOutputTokens < 1 || maxOutputTokens > contextWindow) {
                throw new IllegalArgumentException("providerModel token limits are invalid");
            }
        }
    }

    private static String identifier(String value, String field) {
        String normalized = text(value, field);
        if (!normalized.matches("[a-z][a-z0-9-]{0,127}")) {
            throw new IllegalArgumentException(field + " must be lower-case kebab-case");
        }
        return normalized;
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
