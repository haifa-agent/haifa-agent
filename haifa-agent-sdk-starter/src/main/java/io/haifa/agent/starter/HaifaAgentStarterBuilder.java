package io.haifa.agent.starter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.mcp.client.McpClientFactory;
import io.haifa.agent.mcp.client.SdkMcpClientFactory;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelAdapterCoordinate;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.model.openai.EnvironmentCredentialResolver;
import io.haifa.agent.model.openai.OpenAiCompatibleChatModel;
import io.haifa.agent.model.openai.OpenAiCompatibleDialects;
import io.haifa.agent.model.openai.OpenAiCompatibleModelConfiguration;
import io.haifa.agent.policy.api.PolicyPresets;
import io.haifa.agent.policy.core.DefaultPolicyDecisionService;
import io.haifa.agent.sdk.api.AgentMetadata;
import io.haifa.agent.sdk.api.HaifaAgent;
import io.haifa.agent.sdk.api.HaifaAgents;
import io.haifa.agent.sdk.api.ModelImageResolver;
import io.haifa.agent.sdk.api.SdkCallerProvider;
import io.haifa.agent.sdk.contribution.CredentialPlatformContribution;
import io.haifa.agent.sdk.contribution.InMemoryConversationContribution;
import io.haifa.agent.sdk.contribution.ModelContribution;
import io.haifa.agent.sdk.contribution.PolicyPlatformContribution;
import io.haifa.agent.sdk.contribution.SdkContributions;
import io.haifa.agent.sdk.product.ProductId;
import io.haifa.agent.sdk.product.ProductProfile;
import io.haifa.agent.sdk.product.ProductVersion;
import io.haifa.agent.sdk.spi.SdkPersistenceContribution;
import io.haifa.agent.sdk.tool.JavaTool;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Safe-default builder for a process-local Haifa Agent. */
public final class HaifaAgentStarterBuilder {
    static final String API_KEY_ENVIRONMENT_VARIABLE = "DEEPSEEK_API_KEY";
    static final String MODEL_ID = "deepseek-v4-flash";
    static final String VISION_MODEL_ID = "deepseek-v4-flash-vision-exp";
    static final Set<String> SUPPORTED_DEEPSEEK_MODELS = Set.of(
            MODEL_ID,
            "deepseek-chat-flash",
            "deepseek-chat-pro",
            VISION_MODEL_ID,
            "deepseek-responses-v4-flash-vision-exp");
    static final Set<String> DEEPSEEK_VISION_MODELS = Set.of(VISION_MODEL_ID, "deepseek-responses-v4-flash-vision-exp");
    static final URI ENDPOINT = URI.create("https://api.deepseek.com");

    private static final String VERSION = "1.0.0";
    private static final String ADAPTER_TYPE = "openai-compatible";

    private String instructions = "You are a helpful assistant. Answer clearly and concisely.";
    private String name = AgentMetadata.DEFAULT_NAME;
    private boolean defaultInstructions = true;
    private String credentialEnvironmentVariable = API_KEY_ENVIRONMENT_VARIABLE;
    private SdkCallerProvider callers = SdkCallerProvider.defaultPublicUser();
    private Function<String, String> environment = System::getenv;
    private Duration connectTimeout = Duration.ofSeconds(10);
    private final List<JavaTool<?, ?>> tools = new ArrayList<>();
    private final List<McpServerSpec> mcpServers = new ArrayList<>();
    private McpClientFactory mcpClientFactory = new SdkMcpClientFactory();
    private final Map<String, ModelRegistration> models = new LinkedHashMap<>();
    private String defaultModelId;
    private ModelImageResolver modelImageResolver = ModelImageResolver.unsupported();

    HaifaAgentStarterBuilder() {}

    /** Sets bounded immutable display metadata; it is never added to the Prompt. */
    public HaifaAgentStarterBuilder name(String value) {
        name = requireText(value, "name", 128);
        return this;
    }

    /**
     * Sets the trusted system instructions frozen into every Run created by this Starter.
     *
     * @param value trusted instructions
     * @return this builder
     */
    public HaifaAgentStarterBuilder instructions(String value) {
        instructions = requireText(value, "instructions");
        defaultInstructions = false;
        return this;
    }

    /**
     * Selects the environment variable containing the default provider API key.
     *
     * @param value environment variable name
     * @return this builder
     */
    public HaifaAgentStarterBuilder credentialEnvironmentVariable(String value) {
        String variable = requireText(value, "credentialEnvironmentVariable");
        if (!variable.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("credentialEnvironmentVariable must be a valid environment name");
        }
        credentialEnvironmentVariable = variable;
        return this;
    }

    /**
     * Sets the host-authenticated caller provider.
     *
     * @param value trusted caller provider
     * @return this builder
     */
    public HaifaAgentStarterBuilder callerProvider(SdkCallerProvider value) {
        callers = Objects.requireNonNull(value, "value must not be null");
        return this;
    }

    /**
     * Sets the bounded HTTP connection timeout used by the model adapter.
     *
     * @param value positive connection timeout
     * @return this builder
     */
    public HaifaAgentStarterBuilder connectTimeout(Duration value) {
        connectTimeout = Objects.requireNonNull(value, "value must not be null");
        if (connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalArgumentException("connectTimeout must be positive");
        }
        return this;
    }

    /**
     * Sets the resolver used to map stored image references into model-accessible image bytes.
     *
     * @param value image resolver
     * @return this builder
     */
    public HaifaAgentStarterBuilder modelImageResolver(ModelImageResolver value) {
        modelImageResolver = Objects.requireNonNull(value, "value must not be null");
        return this;
    }

    /**
     * Registers one typed Java Tool.
     *
     * @param value Tool to register
     * @return this builder
     */
    public HaifaAgentStarterBuilder tool(JavaTool<?, ?> value) {
        tools.add(Objects.requireNonNull(value, "value must not be null"));
        return this;
    }

    /**
     * Registers typed Java Tools in declaration order.
     *
     * @param values Tools to register
     * @return this builder
     */
    public HaifaAgentStarterBuilder tools(List<? extends JavaTool<?, ?>> values) {
        Objects.requireNonNull(values, "values must not be null").forEach(this::tool);
        return this;
    }

    /**
     * Declares one remote MCP server this Agent consumes as an MCP Client.
     *
     * <p>The Agent owns the connection: its allowlisted Tools join the same frozen Tool catalog as
     * Java Tools, and {@link io.haifa.agent.sdk.api.HaifaAgent#close()} releases the MCP connections
     * and HTTP resources it opened.
     *
     * @param value MCP server declaration
     * @return this builder
     */
    public HaifaAgentStarterBuilder mcpServer(McpServerSpec value) {
        mcpServers.add(Objects.requireNonNull(value, "value must not be null"));
        return this;
    }

    /**
     * Declares remote MCP servers in declaration order.
     *
     * @param values MCP server declarations
     * @return this builder
     */
    public HaifaAgentStarterBuilder mcpServers(List<McpServerSpec> values) {
        Objects.requireNonNull(values, "values must not be null").forEach(this::mcpServer);
        return this;
    }

    /**
     * Registers one trusted model adapter and its frozen snapshot. Registering any custom model
     * replaces the built-in DeepSeek catalog. Callers select non-default models by passing the
     * model ID as the trusted conversation {@code runProfileId}.
     *
     * @param model model adapter
     * @param snapshot frozen model snapshot
     * @return this builder
     */
    public HaifaAgentStarterBuilder model(AgentChatModel model, ResolvedModelSnapshot snapshot) {
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        String modelId = snapshot.modelId().value();
        if (models.putIfAbsent(modelId, new ModelRegistration(model, snapshot, Duration.ofSeconds(60))) != null) {
            throw new IllegalArgumentException("model IDs must be unique");
        }
        return this;
    }

    /**
     * Registers a typed OpenAI-compatible model configuration. The adapter coordinate, frozen
     * snapshot, invocation options, and request timeout remain part of the ordinary Runtime path.
     *
     * @param configuration typed Integration configuration
     * @return this builder
     */
    public HaifaAgentStarterBuilder model(OpenAiCompatibleModelConfiguration configuration) {
        OpenAiCompatibleModelConfiguration value =
                Objects.requireNonNull(configuration, "configuration must not be null");
        String modelId = value.snapshot().modelId().value();
        if (models.putIfAbsent(modelId, new ModelRegistration(value.model(), value.snapshot(), value.requestTimeout()))
                != null) {
            throw new IllegalArgumentException("model IDs must be unique");
        }
        return this;
    }

    /**
     * Selects the default from the explicitly registered model catalog.
     *
     * @param modelId internal model ID
     * @return this builder
     */
    public HaifaAgentStarterBuilder defaultModel(String modelId) {
        defaultModelId = requireText(modelId, "modelId");
        return this;
    }

    /**
     * Builds a process-local Agent. The configured API key is checked but never retained.
     *
     * @return the assembled Agent
     */
    public HaifaAgent build() {
        ModelBundle model = models.isEmpty() ? deepSeekModel() : configuredModels();
        ProductProfile profile = profile(model.snapshot());
        NativeMcpToolPlatform mcp = connectMcpServers();
        try {
            var builder = HaifaAgents.builder(profile)
                    .metadata(new AgentMetadata(name))
                    .callerProvider(callers)
                    .model(model.contribution())
                    .persistence(persistenceContribution())
                    .conversation(conversationContribution())
                    .modelImageResolver(modelImageResolver)
                    .tools(tools)
                    .policy(new PolicyPlatformContribution(
                            PolicyPresets.standardApproval(), new DefaultPolicyDecisionService()));
            if (mcp != null) {
                builder.toolRegistrations(mcp.registrations())
                        .managedResource(mcp)
                        .credentials(new CredentialPlatformContribution(mcp.credentials()));
                mcp.diagnostics().forEach(builder::diagnostic);
            }
            if (defaultInstructions) {
                builder.starterDefaultInstructionsInUse();
            }
            models.values().stream().map(this::runProfile).forEach(builder::runProfile);
            return builder.build();
        } catch (RuntimeException | Error exception) {
            closeQuietly(mcp, exception);
            throw exception;
        }
    }

    private NativeMcpToolPlatform connectMcpServers() {
        if (mcpServers.isEmpty()) return null;
        var caller = Objects.requireNonNull(callers.current(), "caller provider returned null");
        return NativeMcpToolPlatform.connect(
                List.copyOf(mcpServers), caller.tenant(), caller.principal(), environment, mcpClientFactory);
    }

    private static void closeQuietly(NativeMcpToolPlatform mcp, Throwable original) {
        if (mcp == null) return;
        try {
            mcp.close();
        } catch (RuntimeException closeFailure) {
            original.addSuppressed(closeFailure);
        }
    }

    HaifaAgentStarterBuilder environment(Function<String, String> value) {
        environment = Objects.requireNonNull(value, "value must not be null");
        return this;
    }

    HaifaAgentStarterBuilder mcpClientFactory(McpClientFactory value) {
        mcpClientFactory = Objects.requireNonNull(value, "value must not be null");
        return this;
    }

    private ModelBundle deepSeekModel() {
        String apiKey = environment.apply(credentialEnvironmentVariable);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(credentialEnvironmentVariable + " is not configured");
        }
        String modelId = defaultModelId != null ? defaultModelId : MODEL_ID;
        if (!SUPPORTED_DEEPSEEK_MODELS.contains(modelId)) {
            throw new IllegalArgumentException("unsupported default DeepSeek model: " + modelId
                    + "; supported models are: " + SUPPORTED_DEEPSEEK_MODELS);
        }
        boolean isVision = DEEPSEEK_VISION_MODELS.contains(modelId);
        Set<ModelCapability> capabilities = isVision
                ? Set.of(
                        ModelCapability.TEXT_CHAT,
                        ModelCapability.IMAGE_UPLOAD_INPUT,
                        ModelCapability.IMAGE_URL_INPUT,
                        ModelCapability.TOOL_CALLING,
                        ModelCapability.STRUCTURED_OUTPUT)
                : Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING, ModelCapability.STRUCTURED_OUTPUT);
        ResolvedModelSnapshot snapshot = ResolvedModelSnapshot.create(
                new ModelProviderId("deepseek"),
                "2026-04-24",
                new ModelDefinitionId(modelId),
                "2026-04-24",
                modelId,
                ADAPTER_TYPE,
                VERSION,
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                OpenAiCompatibleDialects.DEEPSEEK,
                ENDPOINT,
                new CredentialRef("env://" + credentialEnvironmentVariable),
                false,
                capabilities,
                1_048_576,
                8_192,
                Map.of(),
                Map.of("thinking", "disabled"));
        AgentChatModel model = new OpenAiCompatibleChatModel(
                ADAPTER_TYPE,
                VERSION,
                HttpClient.newBuilder()
                        .connectTimeout(connectTimeout)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                new ObjectMapper(),
                new EnvironmentCredentialResolver(environment),
                false,
                4 * 1024 * 1024);
        ModelContribution contribution = new ModelContribution(
                Map.of(ModelAdapterCoordinate.from(snapshot), model),
                snapshot,
                Map.of(snapshot.modelId().value(), snapshot));
        return new ModelBundle(contribution, snapshot, Map.of(snapshot.modelId().value(), snapshot));
    }

    private ModelBundle configuredModels() {
        String selectedModelId =
                defaultModelId == null ? models.keySet().iterator().next() : defaultModelId;
        ModelRegistration selected = models.get(selectedModelId);
        if (selected == null) {
            throw new IllegalArgumentException("default model must be present in the registered model catalog");
        }
        Map<ModelAdapterCoordinate, AgentChatModel> adapters = new LinkedHashMap<>();
        Map<String, ResolvedModelSnapshot> snapshots = new LinkedHashMap<>();
        models.forEach((modelId, registration) -> {
            ModelAdapterCoordinate coordinate = ModelAdapterCoordinate.from(registration.snapshot());
            AgentChatModel existing = adapters.putIfAbsent(coordinate, registration.model());
            if (existing != null && existing != registration.model()) {
                throw new IllegalArgumentException(
                        "one model adapter coordinate cannot resolve to multiple adapter instances");
            }
            snapshots.put(modelId, registration.snapshot());
        });
        ModelContribution contribution = new ModelContribution(adapters, selected.snapshot(), snapshots);
        return new ModelBundle(contribution, selected.snapshot(), Map.copyOf(snapshots));
    }

    private ProductProfile profile(ResolvedModelSnapshot snapshot) {
        return ProductProfile.create(
                new ProductId("haifa-sdk-starter"),
                new ProductVersion(VERSION),
                new AgentDefinitionId("haifa-sdk-starter-agent"),
                new AgentDefinitionVersion(1, 0, 0),
                instructions,
                new io.haifa.agent.sdk.product.ProductRunProfileRef(
                        snapshot.modelId().value(), VERSION),
                new AgentRunBudget(65_536, 8_192, 65_536, 16, 16, 0, "USD", 100),
                new AgentRunLimits(16, 0, 1, 120_000, 60_000, 16, 16, 0),
                Set.of(),
                Set.of());
    }

    private io.haifa.agent.sdk.product.ProductRunProfile runProfile(ModelRegistration registration) {
        ResolvedModelSnapshot snapshot = registration.snapshot();
        long requestTimeoutMillis = registration.requestTimeout().toMillis();
        return new io.haifa.agent.sdk.product.ProductRunProfile(
                snapshot.modelId().value(),
                VERSION,
                snapshot.modelId().value(),
                io.haifa.agent.core.run.AgentRunType.CHAT,
                new AgentRunBudget(65_536, 8_192, 65_536, 16, 16, 0, "USD", 100),
                new AgentRunLimits(16, 0, 1, Math.max(120_000, requestTimeoutMillis), requestTimeoutMillis, 16, 16, 0),
                Map.of());
    }

    private static SdkPersistenceContribution persistenceContribution() {
        return SdkContributions.inMemoryPersistence();
    }

    private static InMemoryConversationContribution conversationContribution() {
        return new InMemoryConversationContribution();
    }

    private static String requireText(String value, String field) {
        return requireText(value, field, 32_000);
    }

    private static String requireText(String value, String field, int maximumLength) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        if (normalized.length() > maximumLength) throw new IllegalArgumentException(field + " is too long");
        return normalized;
    }

    private record ModelRegistration(AgentChatModel model, ResolvedModelSnapshot snapshot, Duration requestTimeout) {
        private ModelRegistration {
            Objects.requireNonNull(model, "model must not be null");
            Objects.requireNonNull(snapshot, "snapshot must not be null");
            Objects.requireNonNull(requestTimeout, "requestTimeout must not be null");
        }
    }

    private record ModelBundle(
            ModelContribution contribution,
            ResolvedModelSnapshot snapshot,
            Map<String, ResolvedModelSnapshot> snapshots) {}
}
