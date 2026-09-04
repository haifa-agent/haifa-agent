package io.haifa.agent.cli;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.haifa.agent.application.project.persistence.ProjectPersistenceConfiguration;
import io.haifa.agent.application.project.persistence.ProjectPersistenceMode;
import io.haifa.agent.application.project.persistence.ProjectPersistenceProtection;
import io.haifa.agent.application.project.policy.CodingApprovalThreshold;
import io.haifa.agent.model.api.ApiStyleId;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelApiBindingDefinition;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ModelReasoningMode;
import io.haifa.agent.model.core.ModelCatalogDeployment;
import io.haifa.agent.model.core.PackagedModelCatalog;
import io.haifa.agent.model.gemini.GeminiDialects;
import io.haifa.agent.model.openai.AliyunBailianProviderFactory;
import io.haifa.agent.model.openai.OpenAiCompatibleDialects;
import io.haifa.agent.skill.api.SkillOrigin;
import io.haifa.agent.skill.api.SkillParserMode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class CliConfigurationLoader {
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
    private final java.util.function.Function<String, String> modelEnvironment;

    CliConfigurationLoader() {
        this(System::getenv);
    }

    CliConfigurationLoader(java.util.function.Function<String, String> modelEnvironment) {
        this.modelEnvironment = Objects.requireNonNull(modelEnvironment, "modelEnvironment must not be null");
    }

    CliConfiguration load(CliArguments arguments, Path workspace) {
        return load(
                arguments,
                workspace,
                userConfiguration(),
                Path.of(".haifa-agent").resolve("config.yaml"));
    }

    CliConfiguration load(
            CliArguments arguments, Path workspace, Optional<Path> userConfiguration, Path workspaceConfiguration) {
        Objects.requireNonNull(arguments, "arguments must not be null");
        Objects.requireNonNull(workspace, "workspace must not be null");
        Objects.requireNonNull(userConfiguration, "userConfiguration must not be null");
        Objects.requireNonNull(workspaceConfiguration, "workspaceConfiguration must not be null");
        if (workspaceConfiguration.isAbsolute()) {
            throw new IllegalArgumentException("workspace configuration path must be relative");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        if (arguments.config().isPresent()) {
            values.putAll(read(arguments.config().orElseThrow()));
        } else {
            userConfiguration.filter(Files::isRegularFile).map(this::read).ifPresent(values::putAll);
            Path local = workspace.resolve(workspaceConfiguration);
            if (Files.isRegularFile(local)) values.putAll(read(local));
        }
        return resolve(values, arguments);
    }

    private CliConfiguration resolve(Map<String, Object> source, CliArguments arguments) {
        CliConfiguration defaults = CliConfiguration.defaults();
        List<CliConfiguration.Model> configuredModels;
        String defaultModelId;
        if (source.containsKey("model")) {
            throw new IllegalArgumentException(
                    "configuration model is unsupported; use models.providers and models.default");
        }
        if (source.containsKey("models")) {
            Map<String, Object> models = object(source, "models");
            configuredModels = models(models);
            defaultModelId = text(models, "default", configuredModels.getFirst().id());
        } else {
            configuredModels = defaults.availableModels();
            defaultModelId = defaults.model().id();
        }
        if (!"true".equalsIgnoreCase(modelEnvironment.apply("HAIFA_ANTIGRAVITY_LOCAL_COMPAT_TEST"))) {
            configuredModels = configuredModels.stream()
                    .filter(model -> !GeminiDialects.ANTIGRAVITY_DIRECT.equals(model.dialect()))
                    .toList();
        }
        String selectedModelId =
                arguments.model().orElseGet(() -> environment("HAIFA_MODEL_ID").orElse(defaultModelId));
        CliConfiguration.Model selectedModel = configuredModels.stream()
                .filter(value -> value.id().equals(selectedModelId))
                .findFirst()
                .orElseThrow(
                        () -> new IllegalArgumentException("configured model id is unavailable: " + selectedModelId));
        Set<String> tools = stringSet(object(source, "tools").get("enabled"), defaults.enabledTools());
        List<CliConfiguration.McpServer> mcpServers = mcpServers(object(source, "mcp"));
        CliConfiguration.Web web = web(object(source, "web"), defaults.web());
        CliConfiguration.Skills skills = skills(object(source, "skills"), defaults.skills());
        CliConfiguration.Execution execution = execution(object(source, "execution"), defaults.execution());
        Map<String, Object> approvalConfiguration = object(source, "approval");
        ApprovalMode approval = arguments
                .approval()
                .orElseGet(() -> ApprovalMode.parse(
                        text(approvalConfiguration, "mode", defaults.approval().name())));
        CodingApprovalThreshold approvalThreshold;
        if (arguments.approval().isPresent()) {
            approvalThreshold = compatibleThreshold(approval);
        } else if (approvalConfiguration.containsKey("threshold")) {
            approvalThreshold = CodingApprovalThreshold.parse(text(approvalConfiguration, "threshold", ""));
            if (approval == ApprovalMode.DENY) {
                throw new IllegalArgumentException("approval.threshold cannot be used with approval.mode=deny");
            }
            if (approvalConfiguration.containsKey("mode") && approvalThreshold != compatibleThreshold(approval)) {
                throw new IllegalArgumentException(
                        "approval.mode and approval.threshold conflict; configure threshold alone or use the "
                                + "compatible ask=low/auto=never mapping");
            }
        } else {
            approvalThreshold = compatibleThreshold(approval);
        }
        Map<String, Object> runtime = object(source, "runtime");
        ProjectPersistenceConfiguration persistence = persistence(object(source, "persistence"));
        Duration timeout = arguments
                .timeout()
                .orElseGet(() -> Duration.ofMillis(
                        number(runtime, "maxWallTimeMillis", defaults.timeout().toMillis())));
        return new CliConfiguration(
                selectedModel,
                configuredModels,
                tools,
                mcpServers,
                web,
                skills,
                execution,
                approval,
                approvalThreshold,
                timeout,
                Math.toIntExact(number(runtime, "maxIterations", defaults.maxIterations())),
                number(runtime, "maxModelCalls", defaults.maxModelCalls()),
                number(runtime, "maxToolCalls", defaults.maxToolCalls()),
                persistence);
    }

    private static CodingApprovalThreshold compatibleThreshold(ApprovalMode approval) {
        return CodingApprovalThreshold.compatibleWith(io.haifa.agent.policy.api.ApprovalMode.valueOf(approval.name()));
    }

    private List<CliConfiguration.Model> models(Map<String, Object> source) {
        Object configured = source.get("providers");
        if (!(configured instanceof List<?> providers) || providers.isEmpty()) {
            throw new IllegalArgumentException("configuration models.providers must be a non-empty list");
        }
        if (providers.stream().allMatch(this::isCatalogDeploymentProvider)) {
            return catalogModels(providers);
        }
        List<CliConfiguration.Model> result = new ArrayList<>();
        Set<String> providerIds = new java.util.LinkedHashSet<>();
        for (Object entry : providers) {
            if (!(entry instanceof Map<?, ?> rawProvider)) {
                throw new IllegalArgumentException("configuration models.providers must contain objects");
            }
            Map<String, Object> provider = stringObject(rawProvider, "configuration models.providers");
            String providerId = text(provider, "id", "");
            if (!providerIds.add(providerId)) {
                throw new IllegalArgumentException("configuration contains duplicate model provider id: " + providerId);
            }
            reject(provider, "dialectId", "dialectVersion", "adapterType");
            boolean nativeStreaming =
                    requiredBoolean(provider, "nativeStreaming", "configuration models.providers[].nativeStreaming");
            String endpoint =
                    expandEnvironment(requiredText(provider, "endpoint", "configuration models.providers[].endpoint"));
            java.net.URI providerEndpoint = java.net.URI.create(endpoint);
            String credentialRef =
                    requiredText(provider, "credentialRef", "configuration models.providers[].credentialRef");
            Map<ApiStyleId, Binding> bindings = bindings(provider);
            Object configuredModels = provider.get("models");
            if (!(configuredModels instanceof List<?> providerModels) || providerModels.isEmpty()) {
                throw new IllegalArgumentException("configuration models.providers[].models must be a non-empty list");
            }
            for (Object configuredModel : providerModels) {
                if (!(configuredModel instanceof Map<?, ?> rawModel)) {
                    throw new IllegalArgumentException("configuration models.providers[].models must contain objects");
                }
                Map<String, Object> model = stringObject(rawModel, "configuration models.providers[].models");
                reject(model, "endpoint", "credentialRef", "nativeStreaming", "dialect", "dialectVersion");
                ApiStyleId style =
                        new ApiStyleId(requiredText(model, "style", "configuration models.providers[].models[].style"));
                Binding binding = bindings.get(style);
                if (binding == null) {
                    throw new IllegalArgumentException("model references an unbound API style: " + style.value());
                }
                result.add(new CliConfiguration.Model(
                        providerId,
                        text(provider, "displayName", providerId),
                        expandEnvironment(text(model, "providerModelId", "")),
                        providerEndpoint,
                        java.net.URI.create(
                                binding.endpoint() == null ? endpoint : expandEnvironment(binding.endpoint())),
                        credentialRef,
                        style,
                        binding.dialect(),
                        nativeStreaming,
                        expandedNullable(provider, "workspaceId"),
                        expandedNullable(provider, "region"),
                        text(model, "id", ""),
                        text(model, "displayName", text(model, "id", "")),
                        capabilities(model),
                        Math.toIntExact(number(model, "contextWindow", -1)),
                        Math.toIntExact(number(model, "maxOutputTokens", -1)),
                        enumValue(
                                ModelReasoningMode.class,
                                text(model, "reasoningMode", ModelReasoningMode.DISABLED.name()),
                                "model reasoning mode"),
                        expandedNullable(provider, "originator"),
                        expandedNullable(provider, "userAgent")));
            }
        }
        return List.copyOf(result);
    }

    private boolean isCatalogDeploymentProvider(Object value) {
        return value instanceof Map<?, ?> provider && provider.containsKey("allowedBindings");
    }

    private List<CliConfiguration.Model> catalogModels(List<?> providers) {
        List<ModelCatalogDeployment.Provider> deployment = new ArrayList<>();
        Map<String, Map<String, Object>> sourceProviders = new LinkedHashMap<>();
        for (Object raw : providers) {
            Map<String, Object> provider = stringObject((Map<?, ?>) raw, "configuration models.providers");
            reject(provider, "displayName", "apiBindings", "models", "dialectId", "dialectVersion", "adapterType");
            String id = text(provider, "id", "");
            if (sourceProviders.putIfAbsent(id, provider) != null) {
                throw new IllegalArgumentException("configuration contains duplicate model provider id: " + id);
            }
            Set<ModelDefinitionId> allowed = stringSet(
                            provider.get("allowedBindings"),
                            Set.of(),
                            "configuration models.providers[].allowedBindings")
                    .stream()
                    .map(ModelDefinitionId::new)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (allowed.isEmpty()) {
                throw new IllegalArgumentException(
                        "configuration models.providers[].allowedBindings must be non-empty");
            }
            deployment.add(new ModelCatalogDeployment.Provider(
                    new ModelProviderId(id),
                    java.net.URI.create(expandEnvironment(
                            requiredText(provider, "endpoint", "configuration models.providers[].endpoint"))),
                    new CredentialRef(
                            requiredText(provider, "credentialRef", "configuration models.providers[].credentialRef")),
                    requiredBoolean(provider, "nativeStreaming", "configuration models.providers[].nativeStreaming"),
                    !provider.containsKey("enabled")
                            || requiredBoolean(provider, "enabled", "configuration models.providers[].enabled"),
                    allowed,
                    bindingEndpointOverrides(provider, allowed)));
        }
        var projection = PackagedModelCatalog.load(CliConfigurationLoader.class.getClassLoader())
                .project(new ModelCatalogDeployment(deployment));
        Map<String, io.haifa.agent.model.api.ModelProviderDefinition> projectedProviders =
                projection.providers().stream()
                        .collect(java.util.stream.Collectors.toUnmodifiableMap(
                                value -> value.id().value(), value -> value));
        return projection.bindings().stream()
                .map(binding -> {
                    var definition = binding.definition();
                    var provider =
                            projectedProviders.get(definition.providerId().value());
                    Map<String, Object> source =
                            sourceProviders.get(provider.id().value());
                    String workspaceId = expandedNullable(source, "workspaceId");
                    String region = expandedNullable(source, "region");
                    java.net.URI providerEndpoint = provider.endpoint();
                    java.net.URI endpoint = provider.apiBindings().stream()
                            .filter(value -> value.style().equals(definition.style()))
                            .findFirst()
                            .flatMap(value -> value.endpoint())
                            .orElse(provider.endpoint());
                    if (binding.apiBinding().dialect().equals(OpenAiCompatibleDialects.ALIYUN_BAILIAN)) {
                        var configuration = new AliyunBailianProviderFactory.ProviderConfiguration(
                                "cli-v1", workspaceId, region, provider.credentialRef());
                        workspaceId = configuration.workspaceId();
                        region = configuration.region();
                        providerEndpoint = configuration.endpoint();
                        endpoint = configuration.endpoint();
                    }
                    ModelReasoningMode configuredReasoningMode = enumValue(
                            ModelReasoningMode.class,
                            text(source, "reasoningMode", ModelReasoningMode.DISABLED.name()),
                            "model reasoning mode");
                    return new CliConfiguration.Model(
                            provider.id().value(),
                            provider.displayName(),
                            definition.providerModelId(),
                            providerEndpoint,
                            endpoint,
                            provider.credentialRef().value(),
                            definition.style(),
                            binding.apiBinding().dialect(),
                            provider.nativeStreaming(),
                            workspaceId,
                            region,
                            definition.id().value(),
                            definition.displayName(),
                            definition.capabilities(),
                            definition.contextWindow(),
                            definition.maxOutputTokens(),
                            definition.capabilities().contains(ModelCapability.REASONING)
                                    ? configuredReasoningMode
                                    : ModelReasoningMode.DISABLED,
                            expandedNullable(source, "originator"),
                            expandedNullable(source, "userAgent"));
                })
                .toList();
    }

    private Map<ModelDefinitionId, java.net.URI> bindingEndpointOverrides(
            Map<String, Object> provider, Set<ModelDefinitionId> allowed) {
        Object raw = provider.get("bindingEndpointOverrides");
        if (raw == null) return Map.of();
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(
                    "configuration models.providers[].bindingEndpointOverrides must be an object");
        }
        Map<String, Object> values = stringObject(map, "configuration models.providers[].bindingEndpointOverrides");
        Map<ModelDefinitionId, java.net.URI> result = new LinkedHashMap<>();
        values.forEach((bindingId, endpoint) -> {
            ModelDefinitionId id = new ModelDefinitionId(bindingId);
            if (!allowed.contains(id) || !(endpoint instanceof String value) || value.isBlank()) {
                throw new IllegalArgumentException("invalid model binding endpoint override: " + bindingId);
            }
            result.put(id, java.net.URI.create(expandEnvironment(value)));
        });
        return Map.copyOf(result);
    }

    private static Map<ApiStyleId, Binding> bindings(Map<String, Object> provider) {
        Object configured = provider.get("apiBindings");
        if (!(configured instanceof List<?> values) || values.isEmpty()) {
            throw new IllegalArgumentException("configuration models.providers[].apiBindings must be a non-empty list");
        }
        Map<ApiStyleId, Binding> result = new LinkedHashMap<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> raw)) {
                throw new IllegalArgumentException("configuration models.providers[].apiBindings must contain objects");
            }
            Map<String, Object> binding = stringObject(raw, "configuration models.providers[].apiBindings");
            reject(binding, "id", "version", "styleVersion", "dialectVersion", "credentialRef", "nativeStreaming");
            ApiStyleId style = new ApiStyleId(
                    requiredText(binding, "style", "configuration models.providers[].apiBindings[].style"));
            String dialect = nullableText(binding, "dialect");
            Binding definition = new Binding(
                    dialect == null ? ModelApiBindingDefinition.STANDARD_DIALECT : dialect,
                    nullableText(binding, "endpoint"));
            if (result.putIfAbsent(style, definition) != null) {
                throw new IllegalArgumentException("duplicate API style binding: " + style.value());
            }
        }
        return Map.copyOf(result);
    }

    private static Set<ModelCapability> capabilities(Map<String, Object> model) {
        Set<String> names = stringSet(
                model.get("capabilities"), Set.of(), "configuration models.providers[].models[].capabilities");
        if (names.isEmpty()) {
            throw new IllegalArgumentException("model capabilities must be a non-empty list");
        }
        java.util.EnumSet<ModelCapability> result = java.util.EnumSet.noneOf(ModelCapability.class);
        names.forEach(name -> result.add(enumValue(ModelCapability.class, name, "model capability")));
        return Set.copyOf(result);
    }

    private static void reject(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            if (source.containsKey(key))
                throw new IllegalArgumentException("unsupported model configuration field: " + key);
        }
    }

    private String expandEnvironment(String value) {
        if (!value.startsWith("${") || !value.endsWith("}")) return value;
        String expression = value.substring(2, value.length() - 1);
        int separator = expression.indexOf(':');
        String name = separator < 0 ? expression : expression.substring(0, separator);
        String fallback = separator < 0 ? null : expression.substring(separator + 1);
        if (!name.matches("[A-Z][A-Z0-9_]*")) {
            throw new IllegalArgumentException("invalid environment placeholder in model configuration");
        }
        return Optional.ofNullable(modelEnvironment.apply(name))
                .map(String::trim)
                .filter(configured -> !configured.isEmpty())
                .orElseGet(() -> {
                    if (fallback == null || fallback.isBlank()) {
                        throw new IllegalArgumentException("model environment variable is unavailable: " + name);
                    }
                    return fallback;
                });
    }

    private String expandedNullable(Map<String, Object> source, String key) {
        String value = nullableText(source, key);
        return value == null ? null : expandEnvironment(value);
    }

    private record Binding(String dialect, String endpoint) {}

    private ProjectPersistenceConfiguration persistence(Map<String, Object> source) {
        String configuredMode = environment("HAIFA_PERSISTENCE_MODE").orElseGet(() -> text(source, "mode", "MEMORY"));
        ProjectPersistenceMode mode = ProjectPersistenceMode.parse(configuredMode);
        String database =
                environment("HAIFA_SQLITE_DATABASE_PATH").orElseGet(() -> nullableText(source, "databasePath"));
        String transcript =
                environment("HAIFA_TRANSCRIPT_ROOT").orElseGet(() -> nullableText(source, "transcriptRoot"));
        String protector =
                environment("HAIFA_CONTINUATION_PROTECTOR_REF").orElseGet(() -> nullableText(source, "protectorRef"));
        String defaultProtection = mode == ProjectPersistenceMode.MEMORY
                ? ProjectPersistenceProtection.NONE.name()
                : protector == null
                        ? ProjectPersistenceProtection.NONE.name()
                        : ProjectPersistenceProtection.AES_GCM.name();
        String configuredProtection = environment("HAIFA_PERSISTENCE_PROTECTION")
                .orElseGet(() -> text(source, "protection", defaultProtection));
        return new ProjectPersistenceConfiguration(
                mode,
                ProjectPersistenceProtection.parse(configuredProtection),
                optionalPath(database),
                optionalPath(transcript),
                Optional.ofNullable(protector),
                Math.toIntExact(number(
                        source, "busyTimeoutMillis", ProjectPersistenceConfiguration.DEFAULT_BUSY_TIMEOUT_MILLIS)),
                Math.toIntExact(number(
                        source, "maximumPayloadBytes", ProjectPersistenceConfiguration.DEFAULT_MAXIMUM_PAYLOAD_BYTES)));
    }

    private static Optional<Path> optionalPath(String value) {
        return value == null ? Optional.empty() : Optional.of(Path.of(value));
    }

    private CliConfiguration.Skills skills(Map<String, Object> source, CliConfiguration.Skills defaults) {
        Set<String> allowed =
                stringSet(source.get("allowed"), defaults.allowedAliases(), "configuration skills.allowed");
        Object configured = source.get("localDirectories");
        if (configured == null) return new CliConfiguration.Skills(allowed, defaults.localDirectories());
        if (!(configured instanceof List<?> directories)) {
            throw new IllegalArgumentException("configuration skills.localDirectories must be a list");
        }
        List<CliConfiguration.LocalSkillDirectory> result = new ArrayList<>();
        for (Object item : directories) {
            if (!(item instanceof Map<?, ?> raw)) {
                throw new IllegalArgumentException("configuration skills.localDirectories must contain objects");
            }
            Map<String, Object> directory = new LinkedHashMap<>();
            raw.forEach((key, value) -> directory.put(String.valueOf(key), value));
            String id = requiredText(directory, "id", "configuration skill local directory id");
            String root =
                    expandEnvironment(requiredText(directory, "root", "configuration skill local directory root"));
            SkillParserMode parserMode = enumValue(
                    SkillParserMode.class,
                    text(directory, "parserMode", SkillParserMode.STRICT.name()),
                    "configuration skill parserMode");
            SkillOrigin origin = enumValue(
                    SkillOrigin.class,
                    text(directory, "origin", SkillOrigin.CREATED.name()),
                    "configuration skill origin");
            result.add(new CliConfiguration.LocalSkillDirectory(
                    id,
                    Path.of(root),
                    Math.toIntExact(nonNegativeNumber(directory, "priority", 100)),
                    parserMode,
                    origin));
        }
        return new CliConfiguration.Skills(allowed, result);
    }

    private static CliConfiguration.Web web(Map<String, Object> source, CliConfiguration.Web defaults) {
        return new CliConfiguration.Web(
                webProvider(object(source, "search"), "search", defaults.search()),
                webProvider(object(source, "fetch"), "fetch", defaults.fetch()));
    }

    private static CliConfiguration.WebProvider webProvider(
            Map<String, Object> source, String operation, CliConfiguration.WebProvider defaults) {
        String providerId = text(source, "provider", defaults.providerId()).toLowerCase(java.util.Locale.ROOT);
        String endpoint = nullableText(source, "endpoint");
        if (endpoint == null)
            endpoint = defaultWebEndpoint(operation, providerId).toString();
        String credentialRef = nullableText(source, "credentialRef");
        if (credentialRef == null) credentialRef = defaultWebCredential(providerId);
        return new CliConfiguration.WebProvider(
                bool(source, "enabled", defaults.enabled()),
                providerId,
                java.net.URI.create(endpoint),
                credentialRef,
                Duration.ofMillis(
                        number(source, "timeoutMillis", defaults.timeout().toMillis())),
                Math.toIntExact(number(source, "maxResponseBytes", defaults.maxResponseBytes())));
    }

    private static java.net.URI defaultWebEndpoint(String operation, String providerId) {
        if (operation.equals("fetch")) {
            return switch (providerId) {
                case "aliyun" -> io.haifa.agent.web.provider.AliyunFetchProvider.DEFAULT_ENDPOINT;
                case "browserless" -> io.haifa.agent.web.provider.BrowserlessFetchProvider.DEFAULT_ENDPOINT;
                case "tavily" -> io.haifa.agent.web.provider.TavilyFetchProvider.DEFAULT_ENDPOINT;
                default -> throw new IllegalArgumentException("web.fetch.provider is unsupported");
            };
        }
        return switch (providerId) {
            case "aliyun" -> io.haifa.agent.web.provider.AliyunSearchProvider.DEFAULT_ENDPOINT;
            case "brave" -> io.haifa.agent.web.provider.BraveWebSearchProvider.DEFAULT_ENDPOINT;
            case "tavily" -> io.haifa.agent.web.provider.TavilyWebSearchProvider.DEFAULT_ENDPOINT;
            default -> throw new IllegalArgumentException("web.search.provider is unsupported");
        };
    }

    private static String defaultWebCredential(String providerId) {
        return switch (providerId) {
            case "aliyun" -> "env://ALIYUN_IQS_API_KEY";
            case "brave" -> "env://BRAVE_SEARCH_API_KEY";
            case "tavily" -> "env://TAVILY_API_KEY";
            case "browserless" -> "env://BROWSERLESS_TOKEN";
            default -> throw new IllegalArgumentException("web providerId is unsupported");
        };
    }

    private static CliConfiguration.Execution execution(
            Map<String, Object> source, CliConfiguration.Execution defaults) {
        String shell = text(source, "shell", defaults.shell());
        String shellPathValue = nullableText(source, "shellPath");
        java.nio.file.Path shellPath = shellPathValue == null ? null : java.nio.file.Path.of(shellPathValue);
        return new CliConfiguration.Execution(
                text(source, "provider", defaults.provider()),
                text(source, "network", defaults.network()),
                shell,
                shellPath,
                Duration.ofMillis(number(
                        source,
                        "defaultTimeoutMillis",
                        defaults.defaultTimeout().toMillis())),
                Duration.ofMillis(number(
                        source, "maxTimeoutMillis", defaults.maximumTimeout().toMillis())),
                Math.toIntExact(number(source, "maxOutputBytes", defaults.maxOutputBytes())),
                Math.toIntExact(number(source, "maxOutputLines", defaults.maxOutputLines())),
                Math.toIntExact(number(source, "maxProcesses", defaults.maxProcesses())),
                stringSet(source.get("inheritEnvironment"), defaults.inheritEnvironment()),
                extraPathPolicies(source.get("extraPathPolicies"), defaults.extraPathPolicies()));
    }

    private static List<CliConfiguration.ExtraPathPolicy> extraPathPolicies(
            Object configured, List<CliConfiguration.ExtraPathPolicy> defaults) {
        if (configured == null) return defaults;
        if (!(configured instanceof List<?> values)) {
            throw new IllegalArgumentException("configuration execution.extraPathPolicies must be a list");
        }
        List<CliConfiguration.ExtraPathPolicy> result = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> raw)) {
                throw new IllegalArgumentException("configuration execution.extraPathPolicies must contain objects");
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            raw.forEach((key, item) -> entry.put(String.valueOf(key), item));
            result.add(new CliConfiguration.ExtraPathPolicy(
                    requiredText(entry, "id", "execution extra path policy id"),
                    java.nio.file.Path.of(requiredText(entry, "path", "execution extra path policy path")),
                    bool(entry, "readOnly", true)));
        }
        return List.copyOf(result);
    }

    private List<CliConfiguration.McpServer> mcpServers(Map<String, Object> mcp) {
        Object configured = mcp.get("servers");
        if (configured == null) return List.of();
        if (!(configured instanceof List<?> servers)) {
            throw new IllegalArgumentException("configuration mcp.servers must be a list");
        }
        List<CliConfiguration.McpServer> result = new ArrayList<>();
        for (Object item : servers) {
            if (!(item instanceof Map<?, ?> raw)) {
                throw new IllegalArgumentException("configuration mcp.servers must contain objects");
            }
            Map<String, Object> server = new LinkedHashMap<>();
            raw.forEach((key, value) -> server.put(String.valueOf(key), value));
            String id = requiredText(server, "id", "configuration mcp server id");
            String displayName = text(server, "displayName", id);
            String endpoint = expandEnvironment(requiredText(server, "endpoint", "configuration mcp server endpoint"));
            result.add(new CliConfiguration.McpServer(
                    id,
                    displayName,
                    java.net.URI.create(endpoint),
                    bool(server, "allowLoopbackHttp", false),
                    stringSet(server.get("allowedTools"), Set.of()),
                    text(server, "aliasNamespace", id.replace('-', '_')),
                    text(server, "policyProfile", "conservative"),
                    Duration.ofMillis(number(server, "connectTimeoutMillis", 5000)),
                    Duration.ofMillis(number(server, "requestTimeoutMillis", 15000)),
                    Duration.ofMillis(number(server, "idleTimeoutMillis", 30000)),
                    Math.toIntExact(number(server, "maxBodyBytes", 4 * 1024 * 1024)),
                    Math.toIntExact(number(server, "maxHeaderBytes", 32 * 1024)),
                    Math.toIntExact(nonNegativeNumber(server, "maxReconnectAttempts", 1))));
        }
        return List.copyOf(result);
    }

    private Map<String, Object> read(Path path) {
        try {
            Map<String, Object> values = yaml.readValue(Files.readAllBytes(path), new TypeReference<>() {});
            return values == null ? Map.of() : Map.copyOf(values);
        } catch (IOException exception) {
            throw new IllegalArgumentException("unable to read configuration: " + path.getFileName());
        }
    }

    private static Optional<Path> userConfiguration() {
        String home = System.getProperty("user.home");
        return home == null || home.isBlank()
                ? Optional.empty()
                : Optional.of(Path.of(home, ".haifa-agent", "config.yaml"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value == null) return Map.of();
        if (!(value instanceof Map<?, ?> raw))
            throw new IllegalArgumentException("configuration " + key + " must be an object");
        Map<String, Object> values = new LinkedHashMap<>();
        raw.forEach((entryKey, entryValue) -> values.put(String.valueOf(entryKey), entryValue));
        return values;
    }

    private static Map<String, Object> stringObject(Map<?, ?> raw, String field) {
        Map<String, Object> values = new LinkedHashMap<>();
        raw.forEach((entryKey, entryValue) -> values.put(String.valueOf(entryKey), entryValue));
        if (values.isEmpty()) throw new IllegalArgumentException(field + " must not contain empty objects");
        return values;
    }

    private static String text(Map<String, Object> source, String key, String fallback) {
        Object value = source.get(key);
        if (value == null) return fallback;
        if (!(value instanceof String text))
            throw new IllegalArgumentException("configuration " + key + " must be text");
        return CliConfiguration.text(text, "configuration " + key);
    }

    private static String requiredText(Map<String, Object> source, String key, String field) {
        Object value = source.get(key);
        if (!(value instanceof String text)) throw new IllegalArgumentException(field + " must be text");
        return CliConfiguration.text(text, field);
    }

    private static String nullableText(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value == null) return null;
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("configuration " + key + " must be text");
        }
        return CliConfiguration.text(text, "configuration " + key);
    }

    private static boolean bool(Map<String, Object> source, String key, boolean fallback) {
        Object value = source.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Boolean flag)) {
            throw new IllegalArgumentException("configuration " + key + " must be boolean");
        }
        return flag;
    }

    private static boolean requiredBoolean(Map<String, Object> source, String key, String field) {
        Object value = source.get(key);
        if (!(value instanceof Boolean flag)) throw new IllegalArgumentException(field + " must be boolean");
        return flag;
    }

    private static long number(Map<String, Object> source, String key, long fallback) {
        Object value = source.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Number number) || number.longValue() < 1) {
            throw new IllegalArgumentException("configuration " + key + " must be a positive number");
        }
        return number.longValue();
    }

    private static long nonNegativeNumber(Map<String, Object> source, String key, long fallback) {
        Object value = source.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Number number) || number.longValue() < 0) {
            throw new IllegalArgumentException("configuration " + key + " must be a non-negative number");
        }
        return number.longValue();
    }

    private static Set<String> stringSet(Object value, Set<String> fallback) {
        return stringSet(value, fallback, "configuration tools.enabled");
    }

    private static Set<String> stringSet(Object value, Set<String> fallback, String field) {
        if (value == null) return fallback;
        if (!(value instanceof List<?> values)) throw new IllegalArgumentException(field + " must be a list");
        List<String> names = new ArrayList<>();
        for (Object item : values) {
            if (!(item instanceof String name) || name.isBlank()) {
                throw new IllegalArgumentException(field + " must contain names");
            }
            names.add(name.trim());
        }
        return Set.copyOf(names);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String field) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(field + " is unsupported");
        }
    }

    private Optional<String> environment(String key) {
        return Optional.ofNullable(modelEnvironment.apply(key))
                .map(String::trim)
                .filter(value -> !value.isEmpty());
    }
}
