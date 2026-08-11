package io.haifa.agent.starter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunLimits;
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
import io.haifa.agent.sdk.api.HaifaAgent;
import io.haifa.agent.sdk.api.HaifaAgents;
import io.haifa.agent.sdk.api.SdkCallerProvider;
import io.haifa.agent.sdk.api.SdkConfigurationDigest;
import io.haifa.agent.sdk.contribution.InMemoryConversationContribution;
import io.haifa.agent.sdk.contribution.ModelContribution;
import io.haifa.agent.sdk.contribution.SdkContributionMetadata;
import io.haifa.agent.sdk.contribution.SdkContributions;
import io.haifa.agent.sdk.product.ProductCapabilities;
import io.haifa.agent.sdk.product.ProductCapabilityRequirement;
import io.haifa.agent.sdk.product.ProductContributionCoordinate;
import io.haifa.agent.sdk.product.ProductId;
import io.haifa.agent.sdk.product.ProductProfile;
import io.haifa.agent.sdk.product.ProductProviderSuitability;
import io.haifa.agent.sdk.product.ProductVersion;
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
    static final URI ENDPOINT = URI.create("https://api.deepseek.com");

    private static final String VERSION = "1.0.0";
    private static final String ADAPTER_TYPE = "openai-compatible";
    private static final ProductContributionCoordinate MODEL_COORDINATE =
            new ProductContributionCoordinate("starter.model.catalog", VERSION);
    private static final ProductContributionCoordinate PERSISTENCE_COORDINATE =
            new ProductContributionCoordinate("starter.persistence.memory", VERSION);
    private static final ProductContributionCoordinate CONVERSATION_COORDINATE =
            new ProductContributionCoordinate("starter.conversation.memory", VERSION);

    private String instructions = "You are a helpful assistant. Answer clearly and concisely.";
    private String credentialEnvironmentVariable = API_KEY_ENVIRONMENT_VARIABLE;
    private SdkCallerProvider callers = SdkCallerProvider.defaultPublicUser();
    private Function<String, String> environment = System::getenv;
    private Duration connectTimeout = Duration.ofSeconds(10);
    private final List<JavaTool<?, ?>> tools = new ArrayList<>();
    private final Map<String, ModelRegistration> models = new LinkedHashMap<>();
    private String defaultModelId;

    HaifaAgentStarterBuilder() {}

    /**
     * Sets the trusted system instructions frozen into every Run created by this Starter.
     *
     * @param value trusted instructions
     * @return this builder
     */
    public HaifaAgentStarterBuilder instructions(String value) {
        instructions = requireText(value, "instructions");
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
        if (models.putIfAbsent(modelId, new ModelRegistration(model, snapshot)) != null) {
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
        var builder = HaifaAgents.builder(profile)
                .callerProvider(callers)
                .contribute(model.contribution())
                .contribute(persistenceContribution())
                .contribute(conversationContribution())
                .tools(tools);
        model.snapshots().values().stream()
                .filter(snapshot -> !snapshot.modelId().equals(model.snapshot().modelId()))
                .map(this::runProfile)
                .forEach(builder::runProfile);
        return builder.build();
    }

    HaifaAgentStarterBuilder environment(Function<String, String> value) {
        environment = Objects.requireNonNull(value, "value must not be null");
        return this;
    }

    private ModelBundle deepSeekModel() {
        String apiKey = environment.apply(credentialEnvironmentVariable);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(credentialEnvironmentVariable + " is not configured");
        }
        ResolvedModelSnapshot snapshot = ResolvedModelSnapshot.create(
                new ModelProviderId("deepseek"),
                "2026-04-24",
                new ModelDefinitionId(MODEL_ID),
                "2026-04-24",
                MODEL_ID,
                ADAPTER_TYPE,
                VERSION,
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                OpenAiCompatibleDialects.DEEPSEEK,
                ENDPOINT,
                new CredentialRef("env://" + credentialEnvironmentVariable),
                false,
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING),
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
                metadata(
                        MODEL_COORDINATE,
                        ProductCapabilities.MODEL,
                        snapshot.configurationDigest(),
                        ProductProviderSuitability.PRODUCTION,
                        "DeepSeek V4 Flash with Thinking disabled"),
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
        ModelContribution contribution = new ModelContribution(
                metadata(
                        MODEL_COORDINATE,
                        ProductCapabilities.MODEL,
                        selected.snapshot().configurationDigest(),
                        ProductProviderSuitability.DEVELOPMENT,
                        "Explicit Starter model catalog"),
                adapters,
                selected.snapshot(),
                snapshots);
        return new ModelBundle(contribution, selected.snapshot(), Map.copyOf(snapshots));
    }

    private ProductProfile profile(ResolvedModelSnapshot snapshot) {
        Map<io.haifa.agent.sdk.product.ProductCapabilityId, ProductCapabilityRequirement> requirements = Map.of(
                ProductCapabilities.MODEL,
                ProductCapabilityRequirement.required(
                        ProductCapabilities.MODEL, Set.of(MODEL_COORDINATE), ProductProviderSuitability.DEVELOPMENT),
                ProductCapabilities.PERSISTENCE,
                ProductCapabilityRequirement.required(
                        ProductCapabilities.PERSISTENCE,
                        Set.of(PERSISTENCE_COORDINATE),
                        ProductProviderSuitability.DEVELOPMENT),
                ProductCapabilities.CONVERSATION,
                ProductCapabilityRequirement.required(
                        ProductCapabilities.CONVERSATION,
                        Set.of(CONVERSATION_COORDINATE),
                        ProductProviderSuitability.DEVELOPMENT));
        return ProductProfile.create(
                new ProductId("haifa-sdk-starter"),
                new ProductVersion(VERSION),
                new AgentDefinitionId("haifa-sdk-starter-agent"),
                new AgentDefinitionVersion(1, 0, 0),
                snapshot.modelId().value(),
                VERSION,
                instructions,
                new AgentRunBudget(65_536, 8_192, 65_536, 16, 16, 0, "USD", 100),
                new AgentRunLimits(16, 0, 1, 120_000, 60_000),
                requirements,
                Set.of(),
                Set.of(),
                Set.of());
    }

    private io.haifa.agent.sdk.product.ProductRunProfile runProfile(ResolvedModelSnapshot snapshot) {
        return new io.haifa.agent.sdk.product.ProductRunProfile(
                snapshot.modelId().value(),
                VERSION,
                snapshot.modelId().value(),
                io.haifa.agent.core.run.AgentRunType.CHAT,
                new AgentRunBudget(65_536, 8_192, 65_536, 16, 16, 0, "USD", 100),
                new AgentRunLimits(16, 0, 1, 120_000, 60_000),
                Map.of());
    }

    private static io.haifa.agent.sdk.product.ProductContribution persistenceContribution() {
        return SdkContributions.inMemoryPersistence(metadata(
                PERSISTENCE_COORDINATE,
                ProductCapabilities.PERSISTENCE,
                SdkConfigurationDigest.sha256("starter-persistence-memory-v1"),
                ProductProviderSuitability.DEVELOPMENT,
                "Process-local Runtime persistence"));
    }

    private static InMemoryConversationContribution conversationContribution() {
        return new InMemoryConversationContribution(metadata(
                CONVERSATION_COORDINATE,
                ProductCapabilities.CONVERSATION,
                SdkConfigurationDigest.sha256("starter-conversation-memory-v1"),
                ProductProviderSuitability.DEVELOPMENT,
                "Process-local Conversation storage"));
    }

    private static SdkContributionMetadata metadata(
            ProductContributionCoordinate coordinate,
            io.haifa.agent.sdk.product.ProductCapabilityId capability,
            String digest,
            ProductProviderSuitability suitability,
            String summary) {
        return new SdkContributionMetadata(coordinate, capability, digest, suitability, summary);
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }

    private record ModelRegistration(AgentChatModel model, ResolvedModelSnapshot snapshot) {}

    private record ModelBundle(
            ModelContribution contribution,
            ResolvedModelSnapshot snapshot,
            Map<String, ResolvedModelSnapshot> snapshots) {}
}
