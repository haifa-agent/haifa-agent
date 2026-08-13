package io.haifa.agent.personalassistant.server.configuration.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ApiStyleId;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelAdapterCoordinate;
import io.haifa.agent.model.api.ModelApiBindingDefinition;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinition;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelProviderDefinition;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ModelStatus;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.model.api.ProviderStatus;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.model.core.ImmutableModelCatalog;
import io.haifa.agent.model.core.InMemoryProviderHealthRegistry;
import io.haifa.agent.model.core.ModelAccessPolicy;
import io.haifa.agent.model.core.ModelAvailabilityRequest;
import io.haifa.agent.model.core.ModelSelectionRequest;
import io.haifa.agent.model.core.StaticModelPlatform;
import io.haifa.agent.model.openai.EnvironmentCredentialResolver;
import io.haifa.agent.model.openai.OpenAiCompatibleChatModel;
import io.haifa.agent.model.openai.OpenAiCompatibleDialects;
import io.haifa.agent.model.openai.anthropic.AnthropicMessagesDialects;
import io.haifa.agent.model.openai.anthropic.AnthropicMessagesModel;
import io.haifa.agent.model.openai.responses.OpenAiResponsesModel;
import io.haifa.agent.personalassistant.application.PersonalModelCatalog;
import io.haifa.agent.personalassistant.application.PersonalModelOption;
import io.haifa.agent.personalassistant.application.mission.MissionModelBinding;
import io.haifa.agent.personalassistant.application.product.PersonalAssistantProfile;
import io.haifa.agent.personalassistant.server.configuration.product.PersonalAssistantProperties;
import io.haifa.agent.personalassistant.server.observability.LoggingAgentChatModel;
import io.haifa.agent.sdk.contribution.ModelContribution;
import io.haifa.agent.sdk.contribution.SdkContributionMetadata;
import io.haifa.agent.sdk.contribution.ShellPlatformContribution;
import io.haifa.agent.sdk.product.ProductCapabilities;
import io.haifa.agent.sdk.product.ProductContributionCoordinate;
import io.haifa.agent.sdk.product.ProductProviderSuitability;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Creates either the production remote adapter or an explicitly enabled deterministic acceptance model. */
public final class PersonalModelFactory {
    private PersonalModelFactory() {}

    public static Platform createPlatform(
            List<PersonalAssistantProperties.ModelProvider> configured,
            String defaultModelId,
            ObjectMapper mapper,
            ShellPlatformContribution shell) {
        return createPlatform(configured, defaultModelId, false, mapper, shell);
    }

    public static Platform createPlatform(
            List<PersonalAssistantProperties.ModelProvider> configured,
            String defaultModelId,
            boolean allowInsecureLoopbackModel,
            ObjectMapper mapper,
            ShellPlatformContribution shell) {
        List<PersonalAssistantProperties.ModelProvider> providers = List.copyOf(configured);
        if (providers.isEmpty()) throw new IllegalArgumentException("at least one Personal model provider is required");
        validateEndpoints(providers, allowInsecureLoopbackModel);
        boolean deterministic = providers.stream().anyMatch(value -> "deterministic".equals(value.mode()));
        if (deterministic
                && (providers.size() != 1
                        || providers.getFirst().models().size() != 1
                        || !"deterministic".equals(providers.getFirst().mode()))) {
            throw new IllegalArgumentException("deterministic acceptance model cannot enter the production model list");
        }
        List<ConfiguredModel> models = providers.stream()
                .flatMap(provider -> provider.models().stream().map(model -> new ConfiguredModel(provider, model)))
                .toList();
        ConfiguredModel selected = models.stream()
                .filter(value -> value.model().id().equals(defaultModelId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("default Personal model is unavailable"));
        Map<String, ResolvedModelSnapshot> snapshots = models.stream()
                .collect(java.util.stream.Collectors.toMap(
                        value -> value.model().id(),
                        value -> snapshot(value.provider(), value.model()),
                        (left, right) -> {
                            throw new IllegalArgumentException("duplicate Personal model id");
                        },
                        java.util.LinkedHashMap::new));
        ResolvedModelSnapshot snapshot = snapshots.get(selected.model().id());
        Map<ModelAdapterCoordinate, AgentChatModel> adapters =
                adapters(snapshots, selected, deterministic, mapper, shell, allowInsecureLoopbackModel);
        ModelContribution contribution = new ModelContribution(
                new SdkContributionMetadata(
                        new ProductContributionCoordinate("haifa-personal-model", "1.0.0"),
                        ProductCapabilities.MODEL,
                        snapshot.configurationDigest(),
                        deterministic ? ProductProviderSuitability.DEVELOPMENT : ProductProviderSuitability.PRODUCTION,
                        deterministic ? "Explicit offline acceptance model" : "OpenAI-compatible Personal model"),
                adapters,
                snapshot,
                snapshots);
        StaticModelPlatform modelPlatform = modelPlatform(providers, adapters);
        TenantRef tenant = new TenantRef("personal-product");
        PrincipalRef principal = new PrincipalRef("personal-user", "user");
        PersonalModelCatalog catalog = new PersonalModelCatalog() {
            @Override
            public String defaultModelId() {
                return selected.model().id();
            }

            @Override
            public List<PersonalModelOption> available() {
                return modelPlatform
                        .listAvailable(new ModelAvailabilityRequest(
                                tenant, principal, Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING)))
                        .stream()
                        .flatMap(provider -> provider.models().stream()
                                .map(value -> new PersonalModelOption(
                                        value.id().value(),
                                        value.displayName(),
                                        provider.id().value(),
                                        provider.displayName(),
                                        value.capabilities().stream()
                                                .map(Enum::name)
                                                .collect(java.util.stream.Collectors.toSet()),
                                        value.contextWindow())))
                        .toList();
            }

            @Override
            public java.util.Optional<PersonalModelOption> find(String modelId) {
                java.util.Optional<PersonalModelOption> value = available().stream()
                        .filter(model -> model.id().equals(modelId))
                        .findFirst();
                value.ifPresent(ignored -> modelPlatform.select(new ModelSelectionRequest(
                        tenant,
                        principal,
                        new ModelDefinitionId(modelId),
                        Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING))));
                return value;
            }

            @Override
            public java.util.Optional<MissionModelBinding> binding(String modelId) {
                return find(modelId).map(value -> {
                    ResolvedModelSnapshot frozen = snapshots.get(value.id());
                    return new MissionModelBinding(
                            value.id(),
                            value.displayName(),
                            value.providerId(),
                            value.providerDisplayName(),
                            frozen.configurationDigest());
                });
            }
        };
        return new Platform(contribution, catalog);
    }

    private static ResolvedModelSnapshot snapshot(
            PersonalAssistantProperties.ModelProvider provider, PersonalAssistantProperties.ProviderModel model) {
        PersonalAssistantProperties.ApiBinding binding = binding(provider, model.style());
        ApiStyleId style = new ApiStyleId(model.style());
        URI endpoint = binding.endpoint() == null ? provider.endpoint() : binding.endpoint();
        Map<String, Object> providerOptions = providerOptions(binding, endpoint);
        Map<String, Object> invocationOptions = invocationOptions(binding, model.reasoningMode());
        return ResolvedModelSnapshot.create(
                new ModelProviderId(provider.id()),
                "1.0.0",
                new ModelDefinitionId(model.id()),
                "1.0.0",
                model.providerModelId(),
                ModelApiStyles.adapterType(style),
                "1.0.0",
                style,
                binding.dialect(),
                endpoint,
                new CredentialRef(provider.credentialReference()),
                provider.nativeStreaming(),
                model.capabilities(),
                model.contextWindow(),
                model.maxOutputTokens(),
                providerOptions,
                invocationOptions);
    }

    private static StaticModelPlatform modelPlatform(
            List<PersonalAssistantProperties.ModelProvider> configured,
            Map<ModelAdapterCoordinate, AgentChatModel> adapters) {
        List<ModelProviderDefinition> providers = configured.stream()
                .map(provider -> {
                    ModelProviderId providerId = new ModelProviderId(provider.id());
                    List<ModelDefinition> models = provider.models().stream()
                            .map(model -> new ModelDefinition(
                                    new ModelDefinitionId(model.id()),
                                    "1.0.0",
                                    providerId,
                                    model.providerModelId(),
                                    model.displayName(),
                                    ModelStatus.ACTIVE,
                                    model.capabilities(),
                                    model.contextWindow(),
                                    model.maxOutputTokens(),
                                    invocationOptions(binding(provider, model.style()), model.reasoningMode()),
                                    Map.of(),
                                    new ApiStyleId(model.style())))
                            .toList();
                    return new ModelProviderDefinition(
                            providerId,
                            "1.0.0",
                            provider.displayName(),
                            provider.endpoint(),
                            new CredentialRef(provider.credentialReference()),
                            provider.nativeStreaming(),
                            ProviderStatus.ACTIVE,
                            provider.apiBindings().stream()
                                    .map(binding -> new ModelApiBindingDefinition(
                                            new ApiStyleId(binding.style()), binding.dialect(), binding.endpoint()))
                                    .toList(),
                            models,
                            Map.of(),
                            Map.of());
                })
                .toList();
        return new StaticModelPlatform(
                new ImmutableModelCatalog(providers),
                ModelAccessPolicy.allowAll(),
                adapters.keySet().stream()
                        .collect(java.util.stream.Collectors.toUnmodifiableMap(
                                ModelAdapterCoordinate::type, ModelAdapterCoordinate::version)),
                new InMemoryProviderHealthRegistry());
    }

    private static Map<String, Object> providerOptions(PersonalAssistantProperties.ApiBinding binding, URI endpoint) {
        if (ModelApiStyles.DETERMINISTIC_CHAT.value().equals(binding.style())) return Map.of();
        Map<String, Object> options = new LinkedHashMap<>();
        if (ModelApiStyles.OPENAI_CHAT_COMPLETIONS.value().equals(binding.style())) {
            options.putAll(OpenAiCompatibleDialects.configuredOptions(binding.dialect(), endpoint));
        }
        if (OpenAiCompatibleDialects.DEEPSEEK.equals(binding.dialect())
                || AnthropicMessagesDialects.DEEPSEEK.equals(binding.dialect())) {
            options.put("thinking", "disabled");
        }
        return Map.copyOf(options);
    }

    private static Map<String, Object> invocationOptions(
            PersonalAssistantProperties.ApiBinding binding, io.haifa.agent.model.api.ModelReasoningMode reasoningMode) {
        if (OpenAiCompatibleDialects.ALIYUN_BAILIAN.equals(binding.dialect())) {
            return OpenAiCompatibleDialects.configuredInvocationOptions(binding.dialect(), reasoningMode);
        }
        return OpenAiCompatibleDialects.DEEPSEEK.equals(binding.dialect())
                        || AnthropicMessagesDialects.DEEPSEEK.equals(binding.dialect())
                ? Map.of("thinking", reasoningMode.name().toLowerCase(java.util.Locale.ROOT))
                : Map.of();
    }

    private static PersonalAssistantProperties.ApiBinding binding(
            PersonalAssistantProperties.ModelProvider provider, String style) {
        return provider.apiBindings().stream()
                .filter(candidate -> candidate.style().equals(style))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("model references an unbound API style"));
    }

    private static Map<ModelAdapterCoordinate, AgentChatModel> adapters(
            Map<String, ResolvedModelSnapshot> snapshots,
            ConfiguredModel selected,
            boolean deterministic,
            ObjectMapper mapper,
            ShellPlatformContribution shell,
            boolean allowInsecureLoopbackModel) {
        if (deterministic) {
            AgentChatModel model = new LoggingAgentChatModel(
                    new DeterministicAcceptanceModel(selected.model().providerModelId(), shell));
            return Map.of(
                    ModelAdapterCoordinate.from(snapshots.get(selected.model().id())), model);
        }
        HttpClient http =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        EnvironmentCredentialResolver credentials = new EnvironmentCredentialResolver();
        Map<ModelAdapterCoordinate, AgentChatModel> result = new LinkedHashMap<>();
        snapshots.values().stream().map(ModelAdapterCoordinate::from).distinct().forEach(coordinate -> {
            AgentChatModel adapter =
                    switch (coordinate.type()) {
                        case ModelApiStyles.OPENAI_CHAT_ADAPTER ->
                            new OpenAiCompatibleChatModel(
                                    coordinate.type(),
                                    coordinate.version(),
                                    http,
                                    mapper,
                                    credentials,
                                    allowInsecureLoopbackModel,
                                    4 * 1024 * 1024);
                        case ModelApiStyles.OPENAI_RESPONSES_ADAPTER ->
                            new OpenAiResponsesModel(
                                    http, mapper, credentials, allowInsecureLoopbackModel, 4 * 1024 * 1024);
                        case ModelApiStyles.ANTHROPIC_MESSAGES_ADAPTER ->
                            new AnthropicMessagesModel(
                                    http, mapper, credentials, allowInsecureLoopbackModel, 4 * 1024 * 1024);
                        default ->
                            throw new IllegalArgumentException(
                                    "unsupported Personal model adapter: " + coordinate.type());
                    };
            result.put(coordinate, new LoggingAgentChatModel(adapter));
        });
        return Map.copyOf(result);
    }

    private static void validateEndpoints(
            List<PersonalAssistantProperties.ModelProvider> providers, boolean allowInsecureLoopbackModel) {
        for (PersonalAssistantProperties.ModelProvider provider : providers) {
            List<URI> endpoints = new java.util.ArrayList<>();
            endpoints.add(provider.endpoint());
            provider.apiBindings().stream()
                    .map(PersonalAssistantProperties.ApiBinding::endpoint)
                    .filter(java.util.Objects::nonNull)
                    .forEach(endpoints::add);
            for (URI endpoint : endpoints) {
                if ("https".equalsIgnoreCase(endpoint.getScheme())) continue;
                String host = endpoint.getHost();
                boolean loopback = host != null
                        && Set.of("localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1")
                                .contains(host.toLowerCase(java.util.Locale.ROOT));
                if (!allowInsecureLoopbackModel || !"http".equalsIgnoreCase(endpoint.getScheme()) || !loopback) {
                    throw new IllegalArgumentException(
                            "Personal model HTTP endpoint requires explicit loopback-only opt-in");
                }
            }
        }
    }

    private record ConfiguredModel(
            PersonalAssistantProperties.ModelProvider provider, PersonalAssistantProperties.ProviderModel model) {}

    public record Platform(ModelContribution contribution, PersonalModelCatalog catalog) {}

    /**
     * Test-only-by-configuration model. Markers select one public tool alias, and a following TOOL message
     * always terminates. It never becomes the default production mode.
     */
    private static final class DeterministicAcceptanceModel implements AgentChatModel {
        private final String modelId;
        private final String operatingSystem;
        private final String scriptLanguage;
        private final AtomicLong sequence = new AtomicLong();

        private DeterministicAcceptanceModel(String modelId, ShellPlatformContribution shell) {
            this.modelId = modelId;
            this.operatingSystem = shell.operatingSystem();
            this.scriptLanguage = "WINDOWS".equals(operatingSystem) ? "powershell" : "bash";
            if (!shell.scriptLanguages().contains(scriptLanguage)) {
                throw new IllegalArgumentException(
                        "deterministic acceptance model requires configured script language " + scriptLanguage);
            }
        }

        @Override
        public AgentChatResponse invoke(io.haifa.agent.model.api.AgentChatRequest request) {
            long current = sequence.incrementAndGet();
            String prompt = request.messages().stream()
                    .filter(message -> message.role() == ModelMessageRole.USER)
                    .map(io.haifa.agent.model.api.ModelMessage::content)
                    .reduce((left, right) -> right)
                    .orElse("");
            String visibleContext = request.messages().stream()
                    .map(io.haifa.agent.model.api.ModelMessage::content)
                    .collect(java.util.stream.Collectors.joining("\n"));
            if (prompt.contains("[mission-research-synthesis]")) {
                java.util.regex.Matcher ids = java.util.regex.Pattern.compile(
                                "Real completed Task IDs in result order: \\[([^]]*)]")
                        .matcher(prompt);
                java.util.List<String> taskIds = ids.find()
                        ? java.util.Arrays.stream(ids.group(1).split(","))
                                .map(String::trim)
                                .filter(value -> !value.isBlank())
                                .toList()
                        : java.util.List.of("task-1");
                java.util.regex.Matcher sourceIds = java.util.regex.Pattern.compile(
                                "\\\"sourceId\\\":\\\"([^\\\"]+)\\\"")
                        .matcher(prompt);
                java.util.List<String> settledSourceIds = new java.util.ArrayList<>();
                while (sourceIds.find()) {
                    if (!settledSourceIds.contains(sourceIds.group(1))) settledSourceIds.add(sourceIds.group(1));
                }
                String primarySource = settledSourceIds.isEmpty() ? "source-1" : settledSourceIds.getFirst();
                String secondarySource = settledSourceIds.size() < 2 ? primarySource : settledSourceIds.get(1);
                String findings = taskIds.stream()
                        .map(taskId -> "<!-- haifa-task: " + taskId + " -->\n### " + taskId
                                + "\nThe settled fixture evidence supports this bounded finding [["
                                + primarySource + "]] and an independent cross-check [[" + secondarySource
                                + "]].")
                        .collect(java.util.stream.Collectors.joining("\n\n"));
                String report =
                        """
                        # Deterministic research report
                        <!-- haifa-section: executive-summary -->
                        ## Executive summary
                        The researched finding is supported by two independently fetched fixtures, subject to bounded offline limitations.
                        <!-- haifa-section: scope-method -->
                        ## Scope, assumptions, and method
                        The acceptance report uses only settled Mission evidence and distinguishes fetched facts from remaining uncertainty.
                        <!-- haifa-section: task-findings -->
                        ## Task findings
                        %s
                        <!-- haifa-section: synthesis -->
                        ## Integrated analysis
                        The independent fixture sources agree on the material result while external freshness remains outside this offline run.
                        <!-- haifa-section: conclusions -->
                        ## Conclusions and recommendations
                        The bounded acceptance conclusion is supported; refresh external evidence before relying on it in production.
                        <!-- haifa-section: risks-unknowns -->
                        ## Risks, unknowns, and open questions
                        External freshness, provider variance, and live network behavior were intentionally not evaluated by this fixture.
                        <!-- haifa-section: sources -->
                        ## Sources
                        - [[%s]] Primary deterministic fixture evidence.
                        - [[%s]] Independent deterministic fixture evidence.
                        """
                                .formatted(findings, primarySource, secondarySource);
                return response(current, report, List.of(), ModelFinishReason.STOP);
            }
            if (prompt.contains("[mission-synthesis]")) {
                boolean partial = !prompt.contains("Failed or cancelled Task items: []");
                String result = "{\"schemaVersion\":\"pa.mission-final-result/v1\","
                        + "\"directAnswer\":\"All Mission tasks completed successfully.\","
                        + "\"completedItems\":[\"Settled Mission tasks\"],\"failedItems\":"
                        + (partial ? "[\"One or more Mission tasks\"]" : "[]") + ","
                        + "\"artifactRefs\":[],\"sourceRefs\":[],\"unverifiedClaims\":[],"
                        + "\"unresolvedQuestions\":[],\"residualRisks\":[],"
                        + "\"completionKind\":\"" + (partial ? "PARTIAL" : "COMPLETE") + "\"}";
                return response(current, result, List.of(), ModelFinishReason.STOP);
            }
            if (prompt.contains("Task type: RESEARCH") || visibleContext.contains("Task type: RESEARCH")) {
                boolean reusesDependencies = visibleContext.contains("\"dependencies\":[{");
                long toolResults = reusesDependencies
                        ? 3
                        : request.messages().stream()
                                .filter(message -> message.role() == ModelMessageRole.TOOL)
                                .count();
                if (toolResults == 0) {
                    return tool(
                            current,
                            PersonalAssistantProfile.WEB_SEARCH_ALIAS,
                            Map.of("query", "deterministic deep research evidence", "maxResults", 2));
                }
                if (toolResults == 1) {
                    return tool(
                            current,
                            PersonalAssistantProfile.WEB_FETCH_ALIAS,
                            Map.of("url", "https://research.stub/source-1", "maxCharacters", 4000));
                }
                if (toolResults == 2) {
                    return tool(
                            current,
                            PersonalAssistantProfile.WEB_FETCH_ALIAS,
                            Map.of("url", "https://research.stub/source-2", "maxCharacters", 4000));
                }
                return response(
                        current,
                        """
                        {"schemaVersion":"pa.research-task-result/v1",
                        "brief":"Bounded deterministic research task",
                        "queries":[{"query":"deterministic deep research evidence","phase":"DISCOVER"},
                        {"query":"independent deterministic research corroboration","phase":"CROSS_CHECK"}],
                        "sources":[
                        {"sourceId":"source-1","locator":"https://research.stub/source-1",
                        "normalizedLocator":"https://research.stub/source-1",
                        "locatorDigest":"sha256:1d0076d5314fa605319d168505842186fb1f6d3f534ee25bc2a9fc79a8b97980",
                        "title":"Primary research fixture","safetyType":"DEVELOPMENT_STUB",
                        "fetchedAt":"2026-08-08T00:00:00Z","publishedAt":"2026-01-15T00:00:00Z",
                        "status":"FETCHED","excerpt":"Primary evidence supports the fixture finding.",
                        "contentDigest":"sha256:9f00cea97901fba126e5aecc2f4a33adb3763cbdef57aa21ebf816f94198437b"},
                        {"sourceId":"source-2","locator":"https://research.stub/source-2",
                        "normalizedLocator":"https://research.stub/source-2",
                        "locatorDigest":"sha256:abe06c90ad15ca62760beee68928ade4e5ff04b28d3077a63dccbe599e2d7da5",
                        "title":"Independent research fixture","safetyType":"DEVELOPMENT_STUB",
                        "fetchedAt":"2026-08-08T00:00:00Z","publishedAt":"2026-02-01T00:00:00Z",
                        "status":"FETCHED","excerpt":"Independent evidence corroborates the primary finding.",
                        "contentDigest":"sha256:2badb1b783b31c475f4112dba70fd85edbd4721e5c0b326ab83cb292a36be30a"}],
                        "claims":[{"claimId":"claim-1","claim":"The primary finding is independently corroborated.",
                        "supportingSourceIds":["source-1","source-2"],"opposingSourceIds":[],
                        "limitations":"Offline fixtures do not establish external freshness.","unverified":false,
                        "quotedSpans":[]}],"artifactRefs":[],
                        "unresolvedQuestions":["The offline fixture cannot establish external freshness."],
                        "stopReason":"SUFFICIENT_EVIDENCE",
                        "limitsUsed":{"searchCalls":%d,"fetchCalls":%d,"sources":2,"contentBytes":%d}}
                        """
                                .formatted(
                                        reusesDependencies ? 0 : 1,
                                        reusesDependencies ? 0 : 2,
                                        reusesDependencies ? 0 : 164),
                        List.of(),
                        ModelFinishReason.STOP);
            }
            if (request.messages().getLast().role() == ModelMessageRole.TOOL) {
                return response(current, "The requested capability completed.", List.of(), ModelFinishReason.STOP);
            }
            String alias;
            Map<String, Object> arguments;
            if (prompt.contains("CPU使用率") || prompt.contains("[execution-cpu]")) {
                alias = PersonalAssistantProfile.EXECUTION_TOOL_ALIAS;
                arguments = Map.of(
                        "mode",
                        "SCRIPT",
                        "language",
                        scriptLanguage,
                        "content",
                        cpuObservationScript(),
                        "purpose",
                        "读取当前系统 CPU 使用率与逻辑处理器数量",
                        "timeoutMillis",
                        10_000);
            } else if (prompt.contains("[execution-disk]")) {
                alias = PersonalAssistantProfile.EXECUTION_TOOL_ALIAS;
                arguments = Map.of(
                        "mode",
                        "COMMAND",
                        "content",
                        "Get-PSDrive -PSProvider FileSystem | Select-Object Name, "
                                + "@{N='Used(GB)';E={[math]::Round($_.Used/1GB,2)}}, "
                                + "@{N='Free(GB)';E={[math]::Round($_.Free/1GB,2)}}, "
                                + "@{N='Total(GB)';E={[math]::Round(($_.Used+$_.Free)/1GB,2)}} "
                                + "| Format-Table -AutoSize",
                        "purpose",
                        "Inspect filesystem drive usage",
                        "timeoutMillis",
                        5_000);
            } else if (prompt.contains("[execution-command]")) {
                alias = PersonalAssistantProfile.EXECUTION_TOOL_ALIAS;
                arguments = Map.of(
                        "mode",
                        "COMMAND",
                        "content",
                        "$PSVersionTable.PSVersion.ToString()",
                        "purpose",
                        "读取当前 PowerShell 版本",
                        "timeoutMillis",
                        5_000);
            } else if (prompt.contains("[execution-script]")) {
                alias = PersonalAssistantProfile.EXECUTION_TOOL_ALIAS;
                arguments = Map.of(
                        "mode",
                        "SCRIPT",
                        "language",
                        scriptLanguage,
                        "content",
                        argumentEchoScript(),
                        "args",
                        List.of("first argument", "second'argument"),
                        "purpose",
                        argumentEchoPurpose(),
                        "timeoutMillis",
                        5_000);
            } else if (prompt.contains("[execution-timeout]")) {
                alias = PersonalAssistantProfile.EXECUTION_TOOL_ALIAS;
                arguments = Map.of(
                        "mode",
                        "SCRIPT",
                        "language",
                        scriptLanguage,
                        "content",
                        timeoutScript(),
                        "purpose",
                        "验证执行超时与进程终止",
                        "timeoutMillis",
                        1_000);
            } else if (prompt.contains("[skill]")) {
                alias = PersonalAssistantProfile.SKILL_LOAD_ALIAS;
                arguments = Map.of("skill", PersonalAssistantProfile.BUNDLED_SKILL_ALIAS);
            } else if (prompt.contains("[mcp]")) {
                alias = PersonalAssistantProfile.MCP_TOOL_ALIAS;
                arguments = Map.of("text", "offline MCP verification");
            } else if (prompt.contains("[tool]")) {
                alias = PersonalAssistantProfile.PRODUCT_TOOL_ALIAS;
                arguments = Map.of("items", List.of("review the plan", "confirm completion"));
            } else {
                return response(current, "Personal Assistant is ready.", List.of(), ModelFinishReason.STOP);
            }
            return response(
                    current,
                    "",
                    List.of(new ModelToolCall(
                            new ProviderToolCallCorrelationId("personal-call-" + current), alias, arguments)),
                    ModelFinishReason.TOOL_CALLS);
        }

        private AgentChatResponse tool(long current, String alias, Map<String, Object> arguments) {
            return response(
                    current,
                    "",
                    List.of(new ModelToolCall(
                            new ProviderToolCallCorrelationId("personal-call-" + current), alias, arguments)),
                    ModelFinishReason.TOOL_CALLS);
        }

        private AgentChatResponse response(
                long id, String content, List<ModelToolCall> calls, ModelFinishReason reason) {
            return new AgentChatResponse(
                    "personal-response-" + id,
                    modelId,
                    content,
                    calls,
                    reason,
                    ModelUsage.unpriced(12, Math.max(1, content.length() / 4)),
                    "",
                    Map.of("deterministic", true));
        }

        private String cpuObservationScript() {
            return switch (operatingSystem) {
                case "WINDOWS" ->
                    """
                        $sample = Get-CimInstance Win32_Processor |
                          Measure-Object -Property LoadPercentage -Average
                        [pscustomobject]@{
                          CpuUsagePercent = [math]::Round($sample.Average, 1)
                          LogicalProcessors = [Environment]::ProcessorCount
                        } | ConvertTo-Json -Compress
                        """;
                case "MACOS" ->
                    """
                        cpu_usage=$(top -l 2 -n 0 | awk '/CPU usage/ { idle=$7 } END {
                          gsub("%", "", idle)
                          printf "%.1f", 100 - idle
                        }')
                        logical_processors=$(sysctl -n hw.logicalcpu)
                        printf '{"cpuUsagePercent":%s,"logicalProcessors":%s}\n' \
                          "$cpu_usage" "$logical_processors"
                        """;
                default ->
                    """
                        read -r _ user nice system idle iowait irq softirq steal _ < /proc/stat
                        total_before=$((user + nice + system + idle + iowait + irq + softirq + steal))
                        idle_before=$((idle + iowait))
                        sleep 1
                        read -r _ user nice system idle iowait irq softirq steal _ < /proc/stat
                        total_after=$((user + nice + system + idle + iowait + irq + softirq + steal))
                        idle_after=$((idle + iowait))
                        total_delta=$((total_after - total_before))
                        idle_delta=$((idle_after - idle_before))
                        cpu_usage=$((100 * (total_delta - idle_delta) / total_delta))
                        logical_processors=$(grep -c '^processor' /proc/cpuinfo)
                        printf '{"cpuUsagePercent":%s,"logicalProcessors":%s}\n' \
                          "$cpu_usage" "$logical_processors"
                        """;
            };
        }

        private String argumentEchoScript() {
            return "WINDOWS".equals(operatingSystem) ? "$args -join '|'" : "printf '%s|%s' \"$1\" \"$2\"";
        }

        private String argumentEchoPurpose() {
            return "验证 " + ("WINDOWS".equals(operatingSystem) ? "PowerShell" : "Bash") + " 脚本参数通过 stdin 安全传递";
        }

        private String timeoutScript() {
            return "WINDOWS".equals(operatingSystem)
                    ? "Start-Sleep -Seconds 5; 'unexpected completion'"
                    : "sleep 5; printf 'unexpected completion'";
        }
    }
}
