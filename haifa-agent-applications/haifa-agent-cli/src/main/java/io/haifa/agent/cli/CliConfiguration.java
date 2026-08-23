package io.haifa.agent.cli;

import io.haifa.agent.application.project.persistence.ProjectPersistenceConfiguration;
import io.haifa.agent.application.project.policy.CodingApprovalThreshold;
import io.haifa.agent.model.api.ApiStyleId;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelReasoningMode;
import io.haifa.agent.model.openai.AliyunBailianProviderFactory;
import io.haifa.agent.model.openai.OpenAiCompatibleDialects;
import io.haifa.agent.model.openai.anthropic.AnthropicMessagesDialects;
import io.haifa.agent.skill.api.SkillAlias;
import io.haifa.agent.skill.api.SkillOrigin;
import io.haifa.agent.skill.api.SkillParserMode;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

record CliConfiguration(
        Model model,
        List<Model> availableModels,
        Set<String> enabledTools,
        List<McpServer> mcpServers,
        Web web,
        Skills skills,
        Execution execution,
        ApprovalMode approval,
        CodingApprovalThreshold approvalThreshold,
        Duration timeout,
        int maxIterations,
        long maxToolCalls,
        ProjectPersistenceConfiguration persistence) {
    private static final Set<String> DEFAULT_TOOLS = Set.of(
            "file.list",
            "file.stat",
            "file.read",
            "file.create",
            "file.write",
            "file.patch",
            "file.delete",
            "file.move",
            "execution.run");
    private static final Set<String> OPTIONAL_TOOLS = Set.of("file.search", "web.search", "web.fetch");
    private static final Set<String> SUPPORTED_TOOLS = java.util.stream.Stream.concat(
                    DEFAULT_TOOLS.stream(), OPTIONAL_TOOLS.stream())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    private static final Set<String> DEFAULT_ENVIRONMENT = Set.of("*");

    CliConfiguration {
        model = Objects.requireNonNull(model, "model must not be null");
        availableModels = List.copyOf(Objects.requireNonNull(availableModels, "availableModels must not be null"));
        String selectedModelId = model.id();
        if (availableModels.isEmpty()
                || availableModels.stream().map(Model::id).distinct().count() != availableModels.size()
                || availableModels.stream().noneMatch(value -> value.id().equals(selectedModelId))) {
            throw new IllegalArgumentException("models must be non-empty, uniquely identified, and contain default");
        }
        enabledTools =
                Set.copyOf(new LinkedHashSet<>(Objects.requireNonNull(enabledTools, "enabledTools must not be null")));
        mcpServers = List.copyOf(Objects.requireNonNull(mcpServers, "mcpServers must not be null"));
        web = Objects.requireNonNull(web, "web must not be null");
        skills = Objects.requireNonNull(skills, "skills must not be null");
        execution = Objects.requireNonNull(execution, "execution must not be null");
        persistence = Objects.requireNonNull(persistence, "persistence must not be null");
        if (!SUPPORTED_TOOLS.containsAll(enabledTools)) {
            throw new IllegalArgumentException("CLI supports only configured tools: " + SUPPORTED_TOOLS);
        }
        if (enabledTools.contains("web.search") != web.search().enabled()
                || enabledTools.contains("web.fetch") != web.fetch().enabled()) {
            throw new IllegalArgumentException("tools.enabled and web provider enabled flags must match");
        }
        if (mcpServers.stream().map(McpServer::id).distinct().count() != mcpServers.size()) {
            throw new IllegalArgumentException("MCP server ids must be unique");
        }
        approval = Objects.requireNonNull(approval, "approval must not be null");
        approvalThreshold = Objects.requireNonNull(approvalThreshold, "approvalThreshold must not be null");
        timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("timeout must be positive");
        if (maxIterations < 1 || maxToolCalls < 1)
            throw new IllegalArgumentException("runtime limits must be positive");
    }

    static CliConfiguration defaults() {
        Model responsesFlash = new Model(
                "deepseek",
                "DeepSeek",
                "deepseek-v4-flash",
                URI.create("https://api.deepseek.com"),
                URI.create("https://api.deepseek.com"),
                "env://DEEPSEEK_API_KEY",
                io.haifa.agent.model.api.ModelApiStyles.OPENAI_RESPONSES,
                "deepseek-openai-responses",
                true,
                null,
                null,
                "deepseek-responses-flash",
                "DeepSeek Responses Flash",
                Set.of(
                        ModelCapability.TEXT_CHAT,
                        ModelCapability.TOOL_CALLING,
                        ModelCapability.STRUCTURED_OUTPUT,
                        ModelCapability.REASONING),
                131_072,
                8_192);
        Model chatPro = new Model(
                "deepseek",
                "DeepSeek",
                "deepseek-v4-pro",
                URI.create("https://api.deepseek.com"),
                URI.create("https://api.deepseek.com"),
                "env://DEEPSEEK_API_KEY",
                io.haifa.agent.model.api.ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                OpenAiCompatibleDialects.DEEPSEEK,
                true,
                null,
                null,
                "deepseek-chat-pro",
                "DeepSeek Chat Pro",
                Set.of(
                        ModelCapability.TEXT_CHAT,
                        ModelCapability.TOOL_CALLING,
                        ModelCapability.STRUCTURED_OUTPUT,
                        ModelCapability.REASONING),
                131_072,
                8_192);
        Model anthropicFlash = new Model(
                "deepseek",
                "DeepSeek",
                "deepseek-v4-flash",
                URI.create("https://api.deepseek.com"),
                URI.create("https://api.deepseek.com/anthropic"),
                "env://DEEPSEEK_API_KEY",
                io.haifa.agent.model.api.ModelApiStyles.ANTHROPIC_MESSAGES,
                AnthropicMessagesDialects.DEEPSEEK,
                true,
                null,
                null,
                "deepseek-anthropic-flash",
                "DeepSeek Anthropic Messages Flash",
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING, ModelCapability.REASONING),
                131_072,
                8_192);
        return new CliConfiguration(
                responsesFlash,
                List.of(responsesFlash, chatPro, anthropicFlash),
                DEFAULT_TOOLS,
                List.of(),
                Web.defaults(),
                Skills.defaults(),
                new Execution(
                        "host-guarded",
                        "allow",
                        "auto",
                        null,
                        Duration.ofMinutes(2),
                        Duration.ofMinutes(30),
                        50 * 1024,
                        2000,
                        8,
                        DEFAULT_ENVIRONMENT,
                        List.of()),
                ApprovalMode.ASK,
                CodingApprovalThreshold.LOW,
                Duration.ofMinutes(5),
                50,
                32,
                ProjectPersistenceConfiguration.memory());
    }

    CliConfiguration(
            Model model,
            List<Model> availableModels,
            Set<String> enabledTools,
            List<McpServer> mcpServers,
            Web web,
            Skills skills,
            Execution execution,
            ApprovalMode approval,
            Duration timeout,
            int maxIterations,
            long maxToolCalls,
            ProjectPersistenceConfiguration persistence) {
        this(
                model,
                availableModels,
                enabledTools,
                mcpServers,
                web,
                skills,
                execution,
                approval,
                CodingApprovalThreshold.compatibleWith(policyMode(approval)),
                timeout,
                maxIterations,
                maxToolCalls,
                persistence);
    }

    CliConfiguration(
            Model model,
            Set<String> enabledTools,
            List<McpServer> mcpServers,
            Web web,
            Skills skills,
            Execution execution,
            ApprovalMode approval,
            Duration timeout,
            int maxIterations,
            long maxToolCalls) {
        this(
                model,
                List.of(model),
                enabledTools,
                mcpServers,
                web,
                skills,
                execution,
                approval,
                CodingApprovalThreshold.compatibleWith(policyMode(approval)),
                timeout,
                maxIterations,
                maxToolCalls,
                ProjectPersistenceConfiguration.memory());
    }

    CliConfiguration(
            Model model,
            Set<String> enabledTools,
            List<McpServer> mcpServers,
            Execution execution,
            ApprovalMode approval,
            Duration timeout,
            int maxIterations,
            long maxToolCalls) {
        this(
                model,
                enabledTools,
                mcpServers,
                Web.defaults(),
                Skills.defaults(),
                execution,
                approval,
                timeout,
                maxIterations,
                maxToolCalls);
    }

    CliConfiguration(
            Model model,
            Set<String> enabledTools,
            List<McpServer> mcpServers,
            Web web,
            Execution execution,
            ApprovalMode approval,
            Duration timeout,
            int maxIterations,
            long maxToolCalls) {
        this(
                model,
                enabledTools,
                mcpServers,
                web,
                Skills.defaults(),
                execution,
                approval,
                timeout,
                maxIterations,
                maxToolCalls);
    }

    CliConfiguration(
            Model model,
            Set<String> enabledTools,
            List<McpServer> mcpServers,
            Web web,
            Skills skills,
            Execution execution,
            ApprovalMode approval,
            Duration timeout,
            int maxIterations,
            long maxToolCalls,
            ProjectPersistenceConfiguration persistence) {
        this(
                model,
                List.of(model),
                enabledTools,
                mcpServers,
                web,
                skills,
                execution,
                approval,
                timeout,
                maxIterations,
                maxToolCalls,
                persistence);
    }

    private static io.haifa.agent.policy.api.ApprovalMode policyMode(ApprovalMode mode) {
        return io.haifa.agent.policy.api.ApprovalMode.valueOf(mode.name());
    }

    record Model(
            String providerId,
            String providerDisplayName,
            String modelId,
            URI providerEndpoint,
            URI endpoint,
            String credentialRef,
            ApiStyleId style,
            String dialect,
            boolean nativeStreaming,
            String workspaceId,
            String region,
            String id,
            String displayName,
            Set<ModelCapability> capabilities,
            int contextWindow,
            int maxOutputTokens,
            ModelReasoningMode reasoningMode) {
        Model(
                String providerId,
                String providerDisplayName,
                String modelId,
                URI providerEndpoint,
                URI endpoint,
                String credentialRef,
                ApiStyleId style,
                String dialect,
                boolean nativeStreaming,
                String workspaceId,
                String region,
                String id,
                String displayName,
                Set<ModelCapability> capabilities,
                int contextWindow,
                int maxOutputTokens) {
            this(
                    providerId,
                    providerDisplayName,
                    modelId,
                    providerEndpoint,
                    endpoint,
                    credentialRef,
                    style,
                    dialect,
                    nativeStreaming,
                    workspaceId,
                    region,
                    id,
                    displayName,
                    capabilities,
                    contextWindow,
                    maxOutputTokens,
                    ModelReasoningMode.DISABLED);
        }

        Model {
            providerId = text(providerId, "model.providerId");
            providerDisplayName = text(providerDisplayName, "model.providerDisplayName");
            modelId = text(modelId, "model.modelId");
            id = text(id, "model.id");
            displayName = text(displayName, "model.displayName");
            credentialRef = text(credentialRef, "model.credentialRef");
            style = Objects.requireNonNull(style, "model.style must not be null");
            dialect = text(dialect, "model.dialect");
            capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "model.capabilities must not be null"));
            reasoningMode = Objects.requireNonNull(reasoningMode, "model.reasoningMode must not be null");
            if (capabilities.isEmpty()) throw new IllegalArgumentException("model.capabilities must not be empty");
            if (reasoningMode != ModelReasoningMode.DISABLED && !capabilities.contains(ModelCapability.REASONING)) {
                throw new IllegalArgumentException("enabled model reasoning requires REASONING capability");
            }
            if (contextWindow < 1 || maxOutputTokens < 1 || maxOutputTokens > contextWindow) {
                throw new IllegalArgumentException("model token limits are invalid");
            }
            if (!credentialRef.startsWith("env://")) {
                throw new IllegalArgumentException("model.credentialRef must use env://");
            }
            providerEndpoint = normalizeEndpoint(
                    Objects.requireNonNull(providerEndpoint, "model.providerEndpoint must not be null"));
            if (dialect.equals(OpenAiCompatibleDialects.ALIYUN_BAILIAN)) {
                if (!providerId.equals(AliyunBailianProviderFactory.PROVIDER_ID.value())) {
                    throw new IllegalArgumentException("aliyun-bailian-openai-chat requires providerId="
                            + AliyunBailianProviderFactory.PROVIDER_ID.value());
                }
                var configuration = new AliyunBailianProviderFactory.ProviderConfiguration(
                        "cli-v1", workspaceId, region, new CredentialRef(credentialRef));
                URI derivedEndpoint = configuration.endpoint();
                if (!providerEndpoint.equals(derivedEndpoint)
                        || endpoint != null && !normalizeEndpoint(endpoint).equals(derivedEndpoint)) {
                    throw new IllegalArgumentException(
                            "model endpoints must match the endpoint derived from workspaceId and region");
                }
                workspaceId = configuration.workspaceId();
                region = configuration.region();
                endpoint = derivedEndpoint;
            } else {
                endpoint = normalizeEndpoint(Objects.requireNonNull(endpoint, "model.endpoint must not be null"));
                workspaceId = optionalText(workspaceId);
                region = optionalText(region);
            }
        }

        private static URI normalizeEndpoint(URI endpoint) {
            String value = endpoint.normalize().toString();
            while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
            return URI.create(value);
        }

        private static String optionalText(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }

    record Execution(
            String provider,
            String network,
            String shell,
            Path shellPath,
            Duration defaultTimeout,
            Duration maximumTimeout,
            int maxOutputBytes,
            int maxOutputLines,
            int maxProcesses,
            Set<String> inheritEnvironment,
            List<ExtraPathPolicy> extraPathPolicies) {
        private static final Set<String> PROVIDERS = Set.of("local-native", "host-guarded");
        private static final Set<String> NETWORK_MODES = Set.of("deny", "allow");
        private static final Set<String> SHELLS = Set.of("auto", "bash", "powershell");

        Execution {
            provider = text(provider, "execution.provider").toLowerCase(java.util.Locale.ROOT);
            if (!PROVIDERS.contains(provider)) {
                throw new IllegalArgumentException("execution.provider is unsupported");
            }
            network = text(network, "execution.network").toLowerCase(java.util.Locale.ROOT);
            if (!NETWORK_MODES.contains(network)) {
                throw new IllegalArgumentException("execution.network is unsupported");
            }
            if (provider.equals("host-guarded") && network.equals("deny")) {
                throw new IllegalArgumentException("execution.network deny is unavailable for host-guarded");
            }
            shell = text(shell, "execution.shell").toLowerCase(java.util.Locale.ROOT);
            if (!SHELLS.contains(shell)) throw new IllegalArgumentException("execution.shell is unsupported");
            if (shellPath != null) {
                if (!shellPath.isAbsolute()) throw new IllegalArgumentException("execution.shellPath must be absolute");
                if (shell.equals("auto")) {
                    throw new IllegalArgumentException("execution.shellPath requires bash or powershell");
                }
            }
            positive(defaultTimeout, "execution.defaultTimeout");
            positive(maximumTimeout, "execution.maximumTimeout");
            if (defaultTimeout.compareTo(maximumTimeout) > 0 || maximumTimeout.compareTo(Duration.ofMinutes(30)) > 0) {
                throw new IllegalArgumentException("execution timeout configuration is out of range");
            }
            if (maxOutputBytes < 1024 || maxOutputBytes > 1024 * 1024) {
                throw new IllegalArgumentException("execution.maxOutputBytes is out of range");
            }
            if (maxOutputLines < 1 || maxOutputLines > 10_000) {
                throw new IllegalArgumentException("execution.maxOutputLines is out of range");
            }
            if (maxProcesses < 1 || maxProcesses > 64) {
                throw new IllegalArgumentException("execution.maxProcesses is out of range");
            }
            inheritEnvironment = Set.copyOf(
                    Objects.requireNonNull(inheritEnvironment, "execution.inheritEnvironment must not be null"));
            if (inheritEnvironment.stream()
                    .anyMatch(name -> !name.equals("*") && !name.matches("[A-Za-z_][A-Za-z0-9_]*"))) {
                throw new IllegalArgumentException("execution.inheritEnvironment contains an invalid name");
            }
            if (inheritEnvironment.stream()
                    .map(name -> name.toUpperCase(java.util.Locale.ROOT))
                    .anyMatch(Execution::looksSensitive)) {
                throw new IllegalArgumentException("execution.inheritEnvironment contains a secret-like name");
            }
            extraPathPolicies = List.copyOf(
                    Objects.requireNonNull(extraPathPolicies, "execution.extraPathPolicies must not be null"));
            if (extraPathPolicies.size() > 32
                    || extraPathPolicies.stream()
                                    .map(ExtraPathPolicy::id)
                                    .distinct()
                                    .count()
                            != extraPathPolicies.size()) {
                throw new IllegalArgumentException("execution.extraPathPolicies is invalid");
            }
            if (provider.equals("host-guarded") && !extraPathPolicies.isEmpty()) {
                throw new IllegalArgumentException("execution.extraPathPolicies requires local-native");
            }
        }

        private static boolean looksSensitive(String name) {
            return name.contains("API_KEY")
                    || name.contains("ACCESS_KEY")
                    || name.contains("PRIVATE_KEY")
                    || name.contains("PASSWORD")
                    || name.contains("SECRET")
                    || name.contains("TOKEN")
                    || name.contains("CREDENTIAL")
                    || name.endsWith("_PROXY")
                    || name.equals("NO_PROXY")
                    || (name.endsWith("_AUTH_SOCK") && !name.equals("SSH_AUTH_SOCK"))
                    || name.equals("DOCKER_HOST")
                    || name.equals("KUBECONFIG");
        }

        private static void positive(Duration value, String field) {
            Objects.requireNonNull(value, field + " must not be null");
            if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(field + " must be positive");
        }
    }

    record ExtraPathPolicy(String id, Path path, boolean readOnly) {
        ExtraPathPolicy {
            id = text(id, "execution.extraPathPolicies.id");
            if (!id.matches("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")) {
                throw new IllegalArgumentException("execution.extraPathPolicies.id is invalid");
            }
            Path configured = Objects.requireNonNull(path, "execution.extraPathPolicies.path must not be null");
            if (!configured.isAbsolute()) {
                throw new IllegalArgumentException("execution.extraPathPolicies.path must be absolute");
            }
            path = configured.normalize();
            if (path.getParent() == null) {
                throw new IllegalArgumentException("execution.extraPathPolicies.path cannot be a filesystem root");
            }
        }
    }

    record Web(WebProvider search, WebProvider fetch) {
        Web {
            search = Objects.requireNonNull(search, "web.search must not be null");
            fetch = Objects.requireNonNull(fetch, "web.fetch must not be null");
            if (!Set.of("aliyun", "brave", "tavily").contains(search.providerId())) {
                throw new IllegalArgumentException("web.search.provider must be aliyun, brave, or tavily");
            }
            if (!Set.of("aliyun", "browserless", "tavily").contains(fetch.providerId())) {
                throw new IllegalArgumentException("web.fetch.provider must be aliyun, browserless, or tavily");
            }
        }

        static Web defaults() {
            return new Web(
                    new WebProvider(
                            false,
                            "aliyun",
                            io.haifa.agent.web.provider.AliyunSearchProvider.DEFAULT_ENDPOINT,
                            "env://ALIYUN_IQS_API_KEY",
                            Duration.ofSeconds(30),
                            2 * 1024 * 1024),
                    new WebProvider(
                            false,
                            "aliyun",
                            io.haifa.agent.web.provider.AliyunFetchProvider.DEFAULT_ENDPOINT,
                            "env://ALIYUN_IQS_API_KEY",
                            Duration.ofSeconds(30),
                            4 * 1024 * 1024));
        }
    }

    record WebProvider(
            boolean enabled,
            String providerId,
            URI endpoint,
            String credentialRef,
            Duration timeout,
            int maxResponseBytes) {
        WebProvider {
            providerId = text(providerId, "web providerId").toLowerCase(java.util.Locale.ROOT);
            endpoint = Objects.requireNonNull(endpoint, "web endpoint must not be null")
                    .normalize();
            if (!endpoint.isAbsolute()
                    || endpoint.getHost() == null
                    || !endpoint.getScheme().equalsIgnoreCase("https")) {
                throw new IllegalArgumentException("web endpoint must be an absolute HTTPS URI");
            }
            credentialRef = text(credentialRef, "web credentialRef");
            if (!credentialRef.startsWith("env://") || credentialRef.length() == "env://".length()) {
                throw new IllegalArgumentException("web credentialRef must use env://");
            }
            Objects.requireNonNull(timeout, "web timeout must not be null");
            if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(2)) > 0) {
                throw new IllegalArgumentException("web timeout is out of range");
            }
            if (maxResponseBytes < 1024 || maxResponseBytes > 16 * 1024 * 1024) {
                throw new IllegalArgumentException("web maxResponseBytes is out of range");
            }
        }
    }

    record Skills(Set<String> allowedAliases, List<LocalSkillDirectory> localDirectories) {
        Skills {
            Objects.requireNonNull(allowedAliases, "skill allowedAliases must not be null");
            allowedAliases = allowedAliases.stream()
                    .map(SkillAlias::new)
                    .map(SkillAlias::value)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            localDirectories =
                    List.copyOf(Objects.requireNonNull(localDirectories, "skill localDirectories must not be null"));
            if (localDirectories.stream()
                            .map(LocalSkillDirectory::id)
                            .distinct()
                            .count()
                    != localDirectories.size()) {
                throw new IllegalArgumentException("skill local directory ids must be unique");
            }
            if (localDirectories.stream()
                            .map(LocalSkillDirectory::root)
                            .distinct()
                            .count()
                    != localDirectories.size()) {
                throw new IllegalArgumentException("skill local directory roots must be unique");
            }
        }

        static Skills defaults() {
            return new Skills(
                    Set.of("task-planning", "result-verification", "git", "github", "git-delivery"), List.of());
        }
    }

    record LocalSkillDirectory(String id, Path root, int priority, SkillParserMode parserMode, SkillOrigin origin) {
        LocalSkillDirectory {
            id = text(id, "skill local directory id").toLowerCase(java.util.Locale.ROOT);
            if (!id.matches("[a-z][a-z0-9-]{0,63}")) {
                throw new IllegalArgumentException("skill local directory id must be lowercase kebab-case");
            }
            Path configuredRoot = Objects.requireNonNull(root, "skill local directory root must not be null");
            if (!configuredRoot.isAbsolute()) {
                throw new IllegalArgumentException("skill local directory root must be absolute");
            }
            root = configuredRoot.normalize();
            if (priority < 0 || priority > 10_000) {
                throw new IllegalArgumentException("skill local directory priority is out of range");
            }
            parserMode = Objects.requireNonNull(parserMode, "skill parserMode must not be null");
            origin = Objects.requireNonNull(origin, "skill origin must not be null");
            if (origin != SkillOrigin.CREATED && origin != SkillOrigin.IMPORTED) {
                throw new IllegalArgumentException("skill local directory origin must be CREATED or IMPORTED");
            }
        }
    }

    record McpServer(
            String id,
            String displayName,
            URI endpoint,
            boolean allowLoopbackHttp,
            Set<String> allowedTools,
            String aliasNamespace,
            String policyProfile,
            Duration connectTimeout,
            Duration requestTimeout,
            Duration idleTimeout,
            int maxBodyBytes,
            int maxHeaderBytes,
            int maxReconnectAttempts) {
        McpServer {
            id = text(id, "mcp server id");
            displayName = text(displayName, "mcp server displayName");
            endpoint = Objects.requireNonNull(endpoint, "mcp server endpoint must not be null");
            allowedTools = Set.copyOf(Objects.requireNonNull(allowedTools, "mcp allowedTools must not be null"));
            if (allowedTools.isEmpty()) throw new IllegalArgumentException("mcp allowedTools must not be empty");
            aliasNamespace = text(aliasNamespace, "mcp aliasNamespace");
            policyProfile = text(policyProfile, "mcp policyProfile");
            if (!Set.of("conservative", "utility").contains(policyProfile)) {
                throw new IllegalArgumentException("mcp policyProfile must be conservative or utility");
            }
            positive(connectTimeout, "mcp connectTimeout");
            positive(requestTimeout, "mcp requestTimeout");
            positive(idleTimeout, "mcp idleTimeout");
            if (maxBodyBytes < 1024 || maxBodyBytes > 16 * 1024 * 1024) {
                throw new IllegalArgumentException("mcp maxBodyBytes is out of range");
            }
            if (maxHeaderBytes < 1024 || maxHeaderBytes > 256 * 1024) {
                throw new IllegalArgumentException("mcp maxHeaderBytes is out of range");
            }
            if (maxReconnectAttempts < 0 || maxReconnectAttempts > 8) {
                throw new IllegalArgumentException("mcp maxReconnectAttempts is out of range");
            }
        }

        private static void positive(Duration value, String field) {
            Objects.requireNonNull(value, field + " must not be null");
            if (value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException(field + " must be positive");
            }
        }
    }

    static String text(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }

    static Set<String> defaultTools() {
        return DEFAULT_TOOLS;
    }
}
