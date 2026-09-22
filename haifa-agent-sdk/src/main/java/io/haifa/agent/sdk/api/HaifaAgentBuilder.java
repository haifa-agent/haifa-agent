package io.haifa.agent.sdk.api;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.id.UuidV7IdentifierGenerator;
import io.haifa.agent.common.time.SystemTimeProvider;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.context.compression.CompressionPolicy;
import io.haifa.agent.core.run.AgentRunType;
import io.haifa.agent.runtime.core.RuntimeCoreBuilder;
import io.haifa.agent.runtime.core.bootstrap.ResolvedDefinition;
import io.haifa.agent.runtime.core.bootstrap.ResolvedProfile;
import io.haifa.agent.runtime.core.bootstrap.RuntimeCallerContext;
import io.haifa.agent.runtime.core.execution.LocalExecutionScheduler;
import io.haifa.agent.runtime.core.retry.ModelRetryPolicy;
import io.haifa.agent.runtime.core.retry.RetryPolicy;
import io.haifa.agent.runtime.core.retry.RuntimeBackoffPolicy;
import io.haifa.agent.runtime.core.tool.PublicToolPolicy;
import io.haifa.agent.sdk.contribution.ApprovalPlatformContribution;
import io.haifa.agent.sdk.contribution.ArtifactPlatformContribution;
import io.haifa.agent.sdk.contribution.CredentialPlatformContribution;
import io.haifa.agent.sdk.contribution.MemoryPlatformContribution;
import io.haifa.agent.sdk.contribution.ModelContribution;
import io.haifa.agent.sdk.contribution.PolicyPlatformContribution;
import io.haifa.agent.sdk.contribution.ProductApprovalPromptFormatter;
import io.haifa.agent.sdk.contribution.SkillPlatformContribution;
import io.haifa.agent.sdk.contribution.ToolPlatformContribution;
import io.haifa.agent.sdk.contribution.ToolRegistration;
import io.haifa.agent.sdk.internal.DefaultConversationService;
import io.haifa.agent.sdk.internal.ProcessLocalPromptDiagnostics;
import io.haifa.agent.sdk.internal.SafeConversationService;
import io.haifa.agent.sdk.internal.ToolAssembly;
import io.haifa.agent.sdk.memory.AgentMemories;
import io.haifa.agent.sdk.product.ProductProfile;
import io.haifa.agent.sdk.product.ProductRunProfile;
import io.haifa.agent.sdk.spi.SdkConversationContribution;
import io.haifa.agent.sdk.spi.SdkPersistenceContribution;
import io.haifa.agent.sdk.tool.JavaTool;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/** Fluent bootstrap builder with explicit typed component assembly. */
public final class HaifaAgentBuilder {
    private ProductProfile profile;
    private ModelContribution model;
    private SdkPersistenceContribution persistence;
    private SdkConversationContribution conversation;
    private ToolPlatformContribution toolPlatform;
    private SkillPlatformContribution skillPlatform;
    private MemoryPlatformContribution memory;
    private ArtifactPlatformContribution artifacts;
    private PolicyPlatformContribution policy;
    private ApprovalPlatformContribution approval;
    private CredentialPlatformContribution credentials;
    private final List<JavaTool<?, ?>> javaTools = new ArrayList<>();
    private final List<ToolRegistration> toolRegistrations = new ArrayList<>();
    private final List<AutoCloseable> managedResources = new ArrayList<>();
    private final List<AgentDiagnostic> assemblyDiagnostics = new ArrayList<>();
    private SdkCallerProvider callers = SdkCallerProvider.defaultPublicUser();
    private IdentifierGenerator ids = new UuidV7IdentifierGenerator();
    private TimeProvider time = new SystemTimeProvider();
    private ProductApprovalPromptFormatter toolApprovalPrompts = ProductApprovalPromptFormatter.defaultFormatter();
    private java.util.function.UnaryOperator<PublicToolPolicy> publicToolPolicyDecorator =
            java.util.function.UnaryOperator.identity();
    private ModelImageResolver modelImageResolver = ModelImageResolver.unsupported();
    private ModelAudioResolver modelAudioResolver = ModelAudioResolver.unsupported();
    private RetryPolicy toolRetry = RetryPolicy.none();
    private ModelRetryPolicy modelRetry = ModelRetryPolicy.defaults();
    private final Map<String, ProductRunProfile> runProfiles = new LinkedHashMap<>();
    private AgentMetadata metadata = AgentMetadata.defaults();
    private boolean starterDefaultInstructionsInUse;
    private CompressionPolicy compressionPolicy;

    HaifaAgentBuilder() {}

    public HaifaAgentBuilder compressionPolicy(CompressionPolicy value) {
        this.compressionPolicy = Objects.requireNonNull(value, "compressionPolicy must not be null");
        return this;
    }

    public CompressionPolicy compressionPolicy() {
        return compressionPolicy;
    }

    public HaifaAgentBuilder product(ProductProfile value) {
        profile = Objects.requireNonNull(value, "value must not be null");
        return this;
    }

    public HaifaAgentBuilder model(ModelContribution value) {
        model = Objects.requireNonNull(value, "value must not be null");
        return this;
    }

    public HaifaAgentBuilder persistence(SdkPersistenceContribution value) {
        persistence = Objects.requireNonNull(value, "value must not be null");
        return this;
    }

    public HaifaAgentBuilder conversation(SdkConversationContribution value) {
        conversation = Objects.requireNonNull(value, "value must not be null");
        return this;
    }

    public HaifaAgentBuilder toolPlatform(ToolPlatformContribution value) {
        toolPlatform = Objects.requireNonNull(value, "value must not be null");
        return this;
    }

    public HaifaAgentBuilder skillPlatform(SkillPlatformContribution value) {
        skillPlatform = Objects.requireNonNull(value, "value must not be null");
        return this;
    }

    public HaifaAgentBuilder memory(MemoryPlatformContribution value) {
        memory = Objects.requireNonNull(value, "value must not be null");
        return this;
    }

    public HaifaAgentBuilder artifacts(ArtifactPlatformContribution value) {
        artifacts = Objects.requireNonNull(value, "value must not be null");
        return this;
    }

    public HaifaAgentBuilder policy(PolicyPlatformContribution value) {
        policy = Objects.requireNonNull(value, "value must not be null");
        return this;
    }

    public HaifaAgentBuilder approval(ApprovalPlatformContribution value) {
        approval = Objects.requireNonNull(value, "value must not be null");
        return this;
    }

    public HaifaAgentBuilder credentials(CredentialPlatformContribution value) {
        credentials = Objects.requireNonNull(value, "value must not be null");
        return this;
    }

    public HaifaAgentBuilder callerProvider(SdkCallerProvider value) {
        callers = Objects.requireNonNull(value, "value must not be null");
        return this;
    }

    public HaifaAgentBuilder identifierGenerator(IdentifierGenerator value) {
        ids = Objects.requireNonNull(value, "value must not be null");
        return this;
    }

    public HaifaAgentBuilder timeProvider(TimeProvider value) {
        time = Objects.requireNonNull(value, "value must not be null");
        return this;
    }

    /** Sets immutable display/diagnostic metadata; it does not enter Prompt or Run selection. */
    public HaifaAgentBuilder metadata(AgentMetadata value) {
        metadata = Objects.requireNonNull(value, "value must not be null");
        return this;
    }

    /** Records that a higher-level quickstart builder retained its bounded fallback instructions. */
    public HaifaAgentBuilder starterDefaultInstructionsInUse() {
        starterDefaultInstructionsInUse = true;
        return this;
    }

    public HaifaAgentBuilder toolApprovalPrompts(ProductApprovalPromptFormatter value) {
        toolApprovalPrompts = Objects.requireNonNull(value, "value must not be null");
        return this;
    }

    /**
     * Decorates the Runtime-selected public Tool policy. Product overrides must preserve
     * request-bound decisions and delegate every action they do not explicitly own.
     */
    public HaifaAgentBuilder publicToolPolicyDecorator(java.util.function.UnaryOperator<PublicToolPolicy> value) {
        publicToolPolicyDecorator = Objects.requireNonNull(value, "value must not be null");
        return this;
    }

    public HaifaAgentBuilder modelImageResolver(ModelImageResolver value) {
        modelImageResolver = Objects.requireNonNull(value, "value must not be null");
        return this;
    }

    public HaifaAgentBuilder modelAudioResolver(ModelAudioResolver value) {
        modelAudioResolver = Objects.requireNonNull(value, "value must not be null");
        return this;
    }

    /** Configures bounded Tool retries; Runtime still disables them for non-idempotent Tools. */
    public HaifaAgentBuilder toolRetry(
            int maxAttempts,
            Predicate<RuntimeException> retryable,
            Duration initialDelay,
            Duration maxDelay,
            double backoffMultiplier) {
        toolRetry = new RetryPolicy(
                maxAttempts,
                Objects.requireNonNull(retryable, "retryable must not be null"),
                new RuntimeBackoffPolicy(initialDelay, maxDelay, backoffMultiplier));
        return this;
    }

    /** Configures bounded model I/O retries; Runtime still rejects unsafe failure categories. */
    public HaifaAgentBuilder modelRetry(
            int maxAttempts, Duration initialDelay, Duration maxDelay, double backoffMultiplier, double jitterRatio) {
        modelRetry = new ModelRetryPolicy(
                new RetryPolicy(
                        maxAttempts,
                        ignored -> false,
                        new RuntimeBackoffPolicy(initialDelay, maxDelay, backoffMultiplier, jitterRatio)),
                maxDelay);
        return this;
    }

    public HaifaAgentBuilder runProfile(ProductRunProfile value) {
        ProductRunProfile runProfile = Objects.requireNonNull(value, "value must not be null");
        if (runProfiles.putIfAbsent(runProfile.id(), runProfile) != null) {
            throw new IllegalArgumentException("run profile IDs must be unique");
        }
        return this;
    }

    /** Registers one typed Java Tool without requiring a catalog or platform contribution. */
    public HaifaAgentBuilder tool(JavaTool<?, ?> value) {
        javaTools.add(Objects.requireNonNull(value, "value must not be null"));
        return this;
    }

    /** Registers typed Java Tools in declaration order. */
    public HaifaAgentBuilder tools(List<? extends JavaTool<?, ?>> values) {
        Objects.requireNonNull(values, "values must not be null").forEach(this::tool);
        return this;
    }

    /**
     * Registers already-reviewed Integration Tools, such as imported MCP Tools, in declaration order.
     *
     * <p>They join the same single Tool catalog freeze as Java Tools, so an alias contributed twice
     * fails the build instead of silently overwriting the earlier Tool.
     */
    public HaifaAgentBuilder toolRegistrations(List<ToolRegistration> values) {
        Objects.requireNonNull(values, "values must not be null")
                .forEach(value -> toolRegistrations.add(Objects.requireNonNull(value, "value must not be null")));
        return this;
    }

    /**
     * Registers a resource the assembled Agent owns and closes, such as a native MCP client
     * connection pool. It is also closed when the build itself fails.
     */
    public HaifaAgentBuilder managedResource(AutoCloseable value) {
        managedResources.add(Objects.requireNonNull(value, "value must not be null"));
        return this;
    }

    /** Adds one non-secret assembly diagnostic to the built Agent. */
    public HaifaAgentBuilder diagnostic(AgentDiagnostic value) {
        assemblyDiagnostics.add(Objects.requireNonNull(value, "value must not be null"));
        return this;
    }

    public HaifaAgent build() {
        ProductProfile effectiveProfile = Objects.requireNonNull(profile, "a Product Profile must be configured");
        ModelContribution model = requireComponent(this.model, "MODEL_REQUIRED", "a Model must be configured");
        SdkPersistenceContribution persistence = requireComponent(
                this.persistence, "PERSISTENCE_REQUIRED", "a Persistence component must be configured");
        SdkConversationContribution conversation = requireComponent(
                this.conversation, "CONVERSATION_REQUIRED", "a Conversation component must be configured");
        ArtifactPlatformContribution artifact = this.artifacts;
        if (artifact != null && artifact.policy().maxArtifactsPerRun() == 0) {
            throw new HaifaAgentException(
                    "ARTIFACT_POLICY_DISABLED",
                    "product.assemble",
                    "assembly",
                    "Artifact component is forbidden by the Product Profile policy");
        }
        ToolAssembly.Prepared prepared = ToolAssembly.prepare(this.toolPlatform, javaTools, toolRegistrations);
        ToolPlatformContribution tool = prepared.platform();
        Set<String> allowedTools = new LinkedHashSet<>(effectiveProfile.allowedTools());
        allowedTools.addAll(prepared.contributedAliases());
        Set<String> effectiveAllowedTools = Set.copyOf(allowedTools);
        validateDeclaredAliases(effectiveAllowedTools, tool, effectiveProfile.allowedSkills(), skillPlatform);

        List<AutoCloseable> lifecycle = collectLifecycle();
        LocalExecutionScheduler scheduler;
        try {
            scheduler = new LocalExecutionScheduler();
        } catch (RuntimeException | Error exception) {
            closeAfterFailedBuild(lifecycle, exception);
            throw exception;
        }
        try {
            var processPromptDiagnostics = new ProcessLocalPromptDiagnostics();
            RuntimeCoreBuilder runtimeBuilder = new RuntimeCoreBuilder()
                    .identifierGenerator(ids)
                    .timeProvider(time)
                    .scheduler(scheduler)
                    .toolApprovalPrompts(toolApprovalPrompts::format)
                    .structuredOutputSchemaValidator(new io.haifa.agent.tool.core.JsonSchema202012Validator())
                    .modelRetry(modelRetry)
                    .toolRetry(toolRetry)
                    .modelImageResolver(modelImageResolver::resolve)
                    .modelAudioResolver(modelAudioResolver::resolve)
                    .promptDiagnostics(processPromptDiagnostics)
                    .persistence(persistence.runtimePersistence())
                    .callers(() -> {
                        SdkCaller caller = Objects.requireNonNull(callers.current(), "caller provider returned null");
                        return new RuntimeCallerContext(caller.tenant(), caller.principal());
                    })
                    .definitions((id, requested) -> new ResolvedDefinition(
                            id,
                            requested.orElse(effectiveProfile.definitionVersion()),
                            effectiveAllowedTools,
                            effectiveProfile.allowedSkills(),
                            Set.of(),
                            effectiveProfile.instructions(),
                            List.of()))
                    .profiles((id, overrides) -> {
                        ProductRunProfile selected = runProfiles.get(id);
                        if (selected == null) {
                            return new ResolvedProfile(
                                    id,
                                    effectiveProfile.defaultRunProfile().version(),
                                    AgentRunType.CHAT,
                                    effectiveProfile.budget(),
                                    effectiveProfile.limits(),
                                    resolveModelSnapshot(model, effectiveProfile, id),
                                    Map.of());
                        }
                        var baseSnapshot = Optional.ofNullable(model.snapshots().get(selected.modelId()))
                                .orElseThrow(() -> new IllegalArgumentException(
                                        "MODEL_SELECTION_REQUIRED: Run Profile model is unavailable"));
                        var snapshot = selected.effectiveModelParameters()
                                .map(baseSnapshot::withEffectiveParameters)
                                .orElse(baseSnapshot);
                        return new ResolvedProfile(
                                selected.id(),
                                selected.version(),
                                selected.runType(),
                                selected.budget(),
                                selected.limits(),
                                snapshot,
                                Map.of(),
                                selected.modelRequestOptions(),
                                selected.allowedTools());
                    });
            model.adapters()
                    .forEach((coordinate, adapter) ->
                            runtimeBuilder.registerChatModel(coordinate.type(), coordinate.version(), adapter));
            runtimeBuilder.policyProductId(effectiveProfile.productId().value());

            if (tool != null) {
                runtimeBuilder.toolPlatform(tool.catalog(), tool.invoker(), tool.schemaValidator());
            }
            if (skillPlatform != null) {
                runtimeBuilder.skillPlatform(
                        skillPlatform.catalog(), skillPlatform.contentLoader(), skillPlatform.trust());
            }
            runtimeBuilder.publicToolPolicyDecorator(publicToolPolicyDecorator);
            if (memory != null) {
                runtimeBuilder.memory(memory.service(), memory.retriever());
            }
            // No implicit policy: a tool platform without an explicit product policy fails closed in
            // RuntimeCoreBuilder instead of inheriting rules from the SDK assembly layer.
            if (policy != null) {
                runtimeBuilder.policy(policy.rules(), policy.evaluator());
            }
            if (approval != null) {
                runtimeBuilder.approvalVerification(approval.verification());
            }
            if (credentials != null) {
                runtimeBuilder.credentialBroker(credentials.broker());
            }
            if (compressionPolicy != null) {
                runtimeBuilder.compressionPolicy(compressionPolicy);
            }

            var runtime = runtimeBuilder.build();
            var conversationService = new DefaultConversationService(
                    effectiveProfile, runtime, persistence, conversation.conversationStore(), callers, ids, time);
            AtomicBoolean lifecycleClosed = new AtomicBoolean();
            var safeConversations = new SafeConversationService(conversationService, lifecycleClosed);
            var agentRuns = new AgentRuns(runtime, processPromptDiagnostics);
            var agentMemories = memory == null
                    ? Optional.<AgentMemories>empty()
                    : Optional.of(new AgentMemories(
                            memory.service(), memory.policy(), callers, safeConversations, agentRuns, lifecycleClosed));
            return new HaifaAgent(
                    effectiveProfile,
                    metadata,
                    diagnostics(),
                    agentRuns,
                    safeConversations,
                    agentMemories,
                    artifact == null ? Optional.empty() : Optional.of(artifact.service()),
                    scheduler,
                    lifecycle,
                    lifecycleClosed,
                    ids);
        } catch (RuntimeException | Error exception) {
            scheduler.close();
            closeAfterFailedBuild(lifecycle, exception);
            throw exception;
        }
    }

    private List<AgentDiagnostic> diagnostics() {
        List<AgentDiagnostic> diagnostics = new ArrayList<>();
        if (starterDefaultInstructionsInUse) {
            diagnostics.add(new AgentDiagnostic(
                    AgentDiagnostic.Severity.WARNING,
                    "DEFAULT_INSTRUCTIONS_IN_USE",
                    "Starter quickstart instructions are in use; configure trusted product instructions explicitly"));
        }
        diagnostics.addAll(assemblyDiagnostics);
        return List.copyOf(diagnostics);
    }

    private List<AutoCloseable> collectLifecycle() {
        Set<AutoCloseable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        List<AutoCloseable> lifecycle = new ArrayList<>();
        addLifecycle(lifecycle, seen, persistence);
        addLifecycle(lifecycle, seen, conversation);
        addLifecycle(lifecycle, seen, toolPlatform);
        addLifecycle(lifecycle, seen, skillPlatform);
        addLifecycle(lifecycle, seen, memory);
        addLifecycle(lifecycle, seen, artifacts);
        addLifecycle(lifecycle, seen, policy);
        addLifecycle(lifecycle, seen, approval);
        addLifecycle(lifecycle, seen, credentials);
        managedResources.forEach(resource -> addLifecycle(lifecycle, seen, resource));
        return List.copyOf(lifecycle);
    }

    private static void addLifecycle(List<AutoCloseable> lifecycle, Set<AutoCloseable> seen, Object component) {
        if (component instanceof AutoCloseable closeable && seen.add(closeable)) lifecycle.add(closeable);
    }

    private static <T> T requireComponent(T component, String code, String message) {
        if (component == null) {
            throw new HaifaAgentException(code, "product.assemble", "assembly", message);
        }
        return component;
    }

    private static io.haifa.agent.model.api.ResolvedModelSnapshot resolveModelSnapshot(
            ModelContribution model, ProductProfile profile, String profileId) {
        String modelId = profileId.equals(profile.defaultRunProfile().id())
                ? model.snapshot().modelId().value()
                : profileId;
        return Optional.ofNullable(model.snapshots().get(modelId))
                .orElseThrow(() ->
                        new IllegalArgumentException("MODEL_SELECTION_REQUIRED: configured model is unavailable"));
    }

    private static void validateDeclaredAliases(
            Set<String> allowedTools,
            ToolPlatformContribution tool,
            Set<String> allowedSkills,
            SkillPlatformContribution skill) {
        Set<String> availableTools = tool == null
                ? Set.of()
                : tool.catalog().snapshot().bindings().stream()
                        .map(binding -> binding.alias().value())
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!availableTools.containsAll(allowedTools)) {
            throw new HaifaAgentException(
                    "TOOL_ALIAS_UNAVAILABLE",
                    "product.assemble",
                    "assembly",
                    "Product Profile allows a Tool alias not supplied by its Tool platform");
        }
        Set<String> availableSkills = skill == null
                ? Set.of()
                : skill.catalog().snapshot().bindings().stream()
                        .map(binding -> binding.alias().value())
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!availableSkills.containsAll(allowedSkills)) {
            throw new HaifaAgentException(
                    "SKILL_ALIAS_UNAVAILABLE",
                    "product.assemble",
                    "assembly",
                    "Product Profile allows a Skill alias not supplied by its Skill platform");
        }
    }

    private static void closeAfterFailedBuild(List<AutoCloseable> lifecycle, Throwable original) {
        for (int index = lifecycle.size() - 1; index >= 0; index--) {
            try {
                lifecycle.get(index).close();
            } catch (Exception closeFailure) {
                original.addSuppressed(closeFailure);
            }
        }
    }
}
