package io.haifa.agent.sdk.api;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.id.UuidV7IdentifierGenerator;
import io.haifa.agent.common.time.SystemTimeProvider;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.run.AgentRunType;
import io.haifa.agent.runtime.core.RuntimeCoreBuilder;
import io.haifa.agent.runtime.core.bootstrap.ResolvedCapability;
import io.haifa.agent.runtime.core.bootstrap.ResolvedDefinition;
import io.haifa.agent.runtime.core.bootstrap.ResolvedProfile;
import io.haifa.agent.runtime.core.bootstrap.RuntimeCallerContext;
import io.haifa.agent.runtime.core.execution.LocalExecutionScheduler;
import io.haifa.agent.runtime.core.retry.RetryPolicy;
import io.haifa.agent.runtime.core.retry.RuntimeBackoffPolicy;
import io.haifa.agent.runtime.core.tool.PublicToolPolicy;
import io.haifa.agent.sdk.contribution.ApprovalPlatformContribution;
import io.haifa.agent.sdk.contribution.ArtifactPlatformContribution;
import io.haifa.agent.sdk.contribution.ContextSourceContribution;
import io.haifa.agent.sdk.contribution.CredentialPlatformContribution;
import io.haifa.agent.sdk.contribution.ExecutionPlatformContribution;
import io.haifa.agent.sdk.contribution.McpToolCatalogContribution;
import io.haifa.agent.sdk.contribution.MemoryPlatformContribution;
import io.haifa.agent.sdk.contribution.ModelContribution;
import io.haifa.agent.sdk.contribution.PolicyPlatformContribution;
import io.haifa.agent.sdk.contribution.ProductApprovalPromptFormatter;
import io.haifa.agent.sdk.contribution.ShellPlatformContribution;
import io.haifa.agent.sdk.contribution.SkillPlatformContribution;
import io.haifa.agent.sdk.contribution.ToolPlatformContribution;
import io.haifa.agent.sdk.internal.DefaultConversationService;
import io.haifa.agent.sdk.internal.JavaToolAssembly;
import io.haifa.agent.sdk.internal.ProcessLocalPromptDiagnostics;
import io.haifa.agent.sdk.internal.ProductAssemblyResolver;
import io.haifa.agent.sdk.internal.SafeConversationService;
import io.haifa.agent.sdk.memory.AgentMemories;
import io.haifa.agent.sdk.product.ProductAssembly;
import io.haifa.agent.sdk.product.ProductAssemblyDiagnostic;
import io.haifa.agent.sdk.product.ProductAssemblyException;
import io.haifa.agent.sdk.product.ProductCapabilities;
import io.haifa.agent.sdk.product.ProductCapabilityId;
import io.haifa.agent.sdk.product.ProductContribution;
import io.haifa.agent.sdk.product.ProductProfile;
import io.haifa.agent.sdk.product.ProductRunProfile;
import io.haifa.agent.sdk.spi.SdkConversationContribution;
import io.haifa.agent.sdk.spi.SdkPersistenceContribution;
import io.haifa.agent.sdk.tool.JavaTool;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/** Fluent bootstrap builder. Product behavior is selected by Profile, not by hard-coded product branches. */
public final class HaifaAgentBuilder {
    private ProductProfile profile;
    private final List<ProductContribution> contributions = new ArrayList<>();
    private final List<JavaTool<?, ?>> javaTools = new ArrayList<>();
    private SdkCallerProvider callers = SdkCallerProvider.defaultPublicUser();
    private IdentifierGenerator ids = new UuidV7IdentifierGenerator();
    private TimeProvider time = new SystemTimeProvider();
    private ProductApprovalPromptFormatter toolApprovalPrompts = ProductApprovalPromptFormatter.defaultFormatter();
    private java.util.function.UnaryOperator<PublicToolPolicy> publicToolPolicyDecorator =
            java.util.function.UnaryOperator.identity();
    private ModelImageResolver modelImageResolver = ModelImageResolver.unsupported();
    private RetryPolicy toolRetry = RetryPolicy.none();
    private final Map<String, ProductRunProfile> runProfiles = new LinkedHashMap<>();
    private AgentMetadata metadata = AgentMetadata.defaults();
    private boolean starterDefaultInstructionsInUse;

    HaifaAgentBuilder() {}

    public HaifaAgentBuilder product(ProductProfile value) {
        profile = Objects.requireNonNull(value, "value must not be null");
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
     * Decorates the Runtime-selected public Tool policy after compatibility and trusted-skill
     * policies have been assembled. Product overrides must preserve request-bound decisions and
     * delegate every action they do not explicitly own.
     */
    public HaifaAgentBuilder publicToolPolicyDecorator(java.util.function.UnaryOperator<PublicToolPolicy> value) {
        publicToolPolicyDecorator = Objects.requireNonNull(value, "value must not be null");
        return this;
    }

    public HaifaAgentBuilder modelImageResolver(ModelImageResolver value) {
        modelImageResolver = Objects.requireNonNull(value, "value must not be null");
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

    public HaifaAgentBuilder runProfile(ProductRunProfile value) {
        ProductRunProfile profile = Objects.requireNonNull(value, "value must not be null");
        if (runProfiles.putIfAbsent(profile.id(), profile) != null) {
            throw new IllegalArgumentException("run profile IDs must be unique");
        }
        return this;
    }

    public HaifaAgentBuilder contribute(ProductContribution value) {
        contributions.add(Objects.requireNonNull(value, "value must not be null"));
        return this;
    }

    public HaifaAgentBuilder contributeAll(List<? extends ProductContribution> values) {
        Objects.requireNonNull(values, "values must not be null").forEach(this::contribute);
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

    public HaifaAgent build() {
        Objects.requireNonNull(profile, "a Product Profile must be configured");
        JavaToolAssembly.Prepared prepared = JavaToolAssembly.prepare(profile, contributions, javaTools);
        ProductProfile effectiveProfile = prepared.profile();
        ProductAssemblyResolver.Resolution resolution =
                new ProductAssemblyResolver().resolve(effectiveProfile, prepared.contributions());
        ModelContribution model;
        SdkPersistenceContribution persistence;
        SdkConversationContribution conversation;
        model = require(resolution.selected(), ProductCapabilities.MODEL, ModelContribution.class);
        persistence = require(resolution.selected(), ProductCapabilities.PERSISTENCE, SdkPersistenceContribution.class);
        conversation =
                require(resolution.selected(), ProductCapabilities.CONVERSATION, SdkConversationContribution.class);
        ContextSourceContribution context =
                optional(resolution.selected(), ProductCapabilities.CONTEXT, ContextSourceContribution.class);
        MemoryPlatformContribution memory =
                optional(resolution.selected(), ProductCapabilities.MEMORY, MemoryPlatformContribution.class);
        ArtifactPlatformContribution artifact =
                optional(resolution.selected(), ProductCapabilities.ARTIFACT, ArtifactPlatformContribution.class);
        PolicyPlatformContribution policy =
                optional(resolution.selected(), ProductCapabilities.POLICY, PolicyPlatformContribution.class);
        ApprovalPlatformContribution approval =
                optional(resolution.selected(), ProductCapabilities.APPROVAL, ApprovalPlatformContribution.class);
        CredentialPlatformContribution credential =
                optional(resolution.selected(), ProductCapabilities.CREDENTIAL, CredentialPlatformContribution.class);
        optional(resolution.selected(), ProductCapabilities.MCP, McpToolCatalogContribution.class);
        ExecutionPlatformContribution execution =
                optional(resolution.selected(), ProductCapabilities.EXECUTION, ExecutionPlatformContribution.class);
        optional(resolution.selected(), ProductCapabilities.SHELL, ShellPlatformContribution.class);
        if (artifact != null && effectiveProfile.policies().artifact().maxArtifactsPerRun() == 0) {
            throw new ProductAssemblyException(
                    "ARTIFACT_POLICY_DISABLED", "Artifact contribution is forbidden by the Product Profile policy");
        }
        if (execution != null && !effectiveProfile.policies().execution().enabled()) {
            throw new ProductAssemblyException(
                    "EXECUTION_POLICY_DISABLED", "Execution contribution is forbidden by the Product Profile policy");
        }
        validateDeclaredAliases(effectiveProfile, resolution.selected());

        List<ProductContribution> initialized = initializeSelected(resolution, prepared.lifecycleReplacements());
        LocalExecutionScheduler scheduler;
        try {
            scheduler = new LocalExecutionScheduler();
        } catch (RuntimeException | Error exception) {
            closeAfterFailedBuild(initialized, exception);
            throw exception;
        }
        try {
            var processPromptDiagnostics = new ProcessLocalPromptDiagnostics();
            RuntimeCoreBuilder runtimeBuilder = new RuntimeCoreBuilder()
                    .identifierGenerator(ids)
                    .timeProvider(time)
                    .scheduler(scheduler)
                    .toolApprovalPrompts(toolApprovalPrompts::format)
                    .toolRetry(toolRetry)
                    .publicToolPolicyDecorator(publicToolPolicyDecorator)
                    .modelImageResolver(modelImageResolver::resolve)
                    .promptDiagnostics(processPromptDiagnostics)
                    .persistence(persistence.runtimePersistence())
                    .callers(() -> {
                        SdkCaller caller = Objects.requireNonNull(callers.current(), "caller provider returned null");
                        return new RuntimeCallerContext(caller.tenant(), caller.principal());
                    })
                    .definitions((id, requested) -> new ResolvedDefinition(
                            id,
                            requested.orElse(effectiveProfile.definitionVersion()),
                            effectiveProfile.allowedTools(),
                            effectiveProfile.allowedSkills(),
                            Set.of(),
                            effectiveProfile.instructions(),
                            List.of()))
                    .profiles((id, overrides) -> {
                        ProductRunProfile selected = runProfiles.get(id);
                        if (selected == null) {
                            return new ResolvedProfile(
                                    id,
                                    effectiveProfile.runProfileVersion(),
                                    AgentRunType.CHAT,
                                    effectiveProfile.budget(),
                                    effectiveProfile.limits(),
                                    resolveModelSnapshot(model, effectiveProfile, id),
                                    resolvedCapabilities(effectiveProfile, resolution));
                        }
                        var snapshot = java.util.Optional.ofNullable(
                                        model.snapshots().get(selected.modelId()))
                                .orElseThrow(() -> new IllegalArgumentException(
                                        "MODEL_SELECTION_REQUIRED: Run Profile model is unavailable"));
                        return new ResolvedProfile(
                                selected.id(),
                                selected.version(),
                                selected.runType(),
                                selected.budget(),
                                selected.limits(),
                                snapshot,
                                resolvedCapabilities(effectiveProfile, resolution),
                                selected.modelRequestOptions(),
                                selected.allowedTools());
                    });
            model.adapters()
                    .forEach((coordinate, adapter) ->
                            runtimeBuilder.registerChatModel(coordinate.type(), coordinate.version(), adapter));
            runtimeBuilder.policyProductId(effectiveProfile.productId().value());

            ProductContribution tool = resolution.selected().get(ProductCapabilities.TOOL);
            if (tool instanceof ToolPlatformContribution platform) {
                runtimeBuilder.toolPlatform(platform.catalog(), platform.invoker(), platform.schemaValidator());
            }
            ProductContribution skill = resolution.selected().get(ProductCapabilities.SKILL);
            if (skill instanceof SkillPlatformContribution platform) {
                runtimeBuilder.skillPlatform(platform.catalog(), platform.contentLoader(), platform.trust());
            }
            if (context != null) {
                context.sources().forEach(runtimeBuilder::registerContextSource);
            }
            if (memory != null) {
                runtimeBuilder.memory(memory.service(), memory.retriever(), memory.audit());
            }
            if (policy != null) {
                runtimeBuilder.policyStores(policy.snapshots(), policy.decisions(), policy.authorizationEvidence());
            }
            if (approval != null) {
                runtimeBuilder.approvalVerification(approval.verification());
            }
            if (credential != null) {
                runtimeBuilder.credentialBroker(credential.broker());
            }

            var runtime = runtimeBuilder.build();
            var conversationService = new DefaultConversationService(
                    effectiveProfile, runtime, persistence, conversation.conversationStore(), callers, ids, time);
            AtomicBoolean lifecycleClosed = new AtomicBoolean();
            var safeConversations = new SafeConversationService(conversationService, lifecycleClosed);
            var agentRuns = new AgentRuns(runtime, processPromptDiagnostics);
            var agentMemories = memory == null
                    ? java.util.Optional.<AgentMemories>empty()
                    : java.util.Optional.of(new AgentMemories(
                            memory.service(),
                            effectiveProfile.policies().memory(),
                            callers,
                            safeConversations,
                            agentRuns,
                            lifecycleClosed));
            ProductAssembly resolvedAssembly = resolution.assembly();
            ProductAssembly assembly = !starterDefaultInstructionsInUse
                    ? resolvedAssembly
                    : new ProductAssembly(
                            resolvedAssembly.profile(),
                            resolvedAssembly.assemblyDigest(),
                            resolvedAssembly.contributions(),
                            java.util.stream.Stream.concat(
                                            resolvedAssembly.diagnostics().stream(),
                                            java.util.stream.Stream.of(
                                                    new ProductAssemblyDiagnostic(
                                                            ProductAssemblyDiagnostic.Severity.WARNING,
                                                            "DEFAULT_INSTRUCTIONS_IN_USE",
                                                            new ProductCapabilityId("agent"),
                                                            java.util.Optional.empty(),
                                                            "Starter quickstart instructions are in use; configure trusted product instructions explicitly")))
                                    .toList());
            return new HaifaAgent(
                    assembly,
                    metadata,
                    agentRuns,
                    safeConversations,
                    agentMemories,
                    artifact == null ? java.util.Optional.empty() : java.util.Optional.of(artifact.service()),
                    scheduler,
                    initialized,
                    lifecycleClosed,
                    ids);
        } catch (RuntimeException | Error exception) {
            scheduler.close();
            closeAfterFailedBuild(initialized, exception);
            throw exception;
        }
    }

    private static List<ProductContribution> initializeSelected(
            ProductAssemblyResolver.Resolution resolution,
            Map<ProductContribution, ProductContribution> lifecycleReplacements) {
        List<ProductContribution> selected = resolution.selected().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .map(contribution -> lifecycleReplacements.getOrDefault(contribution, contribution))
                .distinct()
                .toList();
        List<ProductContribution> initialized = new ArrayList<>();
        try {
            for (ProductContribution contribution : selected) {
                contribution.initialize();
                initialized.add(contribution);
            }
            return List.copyOf(initialized);
        } catch (RuntimeException | Error exception) {
            closeAfterFailedBuild(initialized, exception);
            throw exception;
        }
    }

    private static io.haifa.agent.model.api.ResolvedModelSnapshot resolveModelSnapshot(
            ModelContribution model, ProductProfile profile, String profileId) {
        String modelId = profileId.equals(profile.runProfileId())
                ? model.snapshot().modelId().value()
                : profileId;
        return java.util.Optional.ofNullable(model.snapshots().get(modelId))
                .orElseThrow(() ->
                        new IllegalArgumentException("MODEL_SELECTION_REQUIRED: configured model is unavailable"));
    }

    private Map<String, ResolvedCapability> resolvedCapabilities(
            ProductProfile profile, ProductAssemblyResolver.Resolution resolution) {
        Map<String, ResolvedCapability> capabilities = new LinkedHashMap<>();
        resolution
                .assembly()
                .contributions()
                .forEach((id, contribution) -> capabilities.put(
                        id.value(),
                        new ResolvedCapability(
                                id.value(),
                                contribution.coordinate().version(),
                                contribution.coordinate().externalForm(),
                                contribution.configurationDigest(),
                                true)));
        capabilities.put(
                "product.profile",
                new ResolvedCapability(
                        "product.profile",
                        profile.productVersion().value(),
                        profile.productId().value(),
                        profile.configurationDigest(),
                        true));
        capabilities.put(
                "product.assembly",
                new ResolvedCapability(
                        "product.assembly",
                        "1.0",
                        profile.productId().value(),
                        resolution.assembly().assemblyDigest(),
                        true));
        return Map.copyOf(capabilities);
    }

    private static void validateDeclaredAliases(
            ProductProfile profile, Map<ProductCapabilityId, ProductContribution> selected) {
        ProductContribution tool = selected.get(ProductCapabilities.TOOL);
        Set<String> availableTools = tool instanceof ToolPlatformContribution platform
                ? platform.catalog().snapshot().bindings().stream()
                        .map(binding -> binding.alias().value())
                        .collect(java.util.stream.Collectors.toUnmodifiableSet())
                : Set.of();
        if (!availableTools.containsAll(profile.allowedTools())) {
            throw new ProductAssemblyException(
                    "TOOL_ALIAS_UNAVAILABLE", "Product Profile allows a Tool alias not supplied by its contribution");
        }
        ProductContribution skill = selected.get(ProductCapabilities.SKILL);
        Set<String> availableSkills = skill instanceof SkillPlatformContribution platform
                ? platform.catalog().snapshot().bindings().stream()
                        .map(binding -> binding.alias().value())
                        .collect(java.util.stream.Collectors.toUnmodifiableSet())
                : Set.of();
        if (!availableSkills.containsAll(profile.allowedSkills())) {
            throw new ProductAssemblyException(
                    "SKILL_ALIAS_UNAVAILABLE", "Product Profile allows a Skill alias not supplied by its contribution");
        }
        ProductContribution mcp = selected.get(ProductCapabilities.MCP);
        if (mcp instanceof McpToolCatalogContribution platform) {
            if (!profile.allowedTools().containsAll(platform.toolAliases())
                    || !availableTools.containsAll(platform.toolAliases())) {
                throw new ProductAssemblyException(
                        "MCP_TOOL_BINDING_INVALID",
                        "Every MCP Tool alias must be explicitly allowed and supplied by the unified Tool catalog");
            }
        }
    }

    private static <T> T require(
            Map<ProductCapabilityId, ProductContribution> selected,
            ProductCapabilityId capability,
            Class<T> expectedType) {
        ProductContribution value = selected.get(capability);
        if (!expectedType.isInstance(value)) {
            throw new ProductAssemblyException(
                    "CAPABILITY_IMPLEMENTATION_INVALID",
                    "Capability " + capability.value() + " requires " + expectedType.getSimpleName());
        }
        return expectedType.cast(value);
    }

    private static <T> T optional(
            Map<ProductCapabilityId, ProductContribution> selected,
            ProductCapabilityId capability,
            Class<T> expectedType) {
        ProductContribution value = selected.get(capability);
        if (value == null) return null;
        if (!expectedType.isInstance(value)) {
            throw new ProductAssemblyException(
                    "CAPABILITY_IMPLEMENTATION_INVALID",
                    "Capability " + capability.value() + " requires " + expectedType.getSimpleName());
        }
        return expectedType.cast(value);
    }

    private static void closeAfterFailedBuild(
            java.util.Collection<ProductContribution> contributions, Throwable original) {
        List<ProductContribution> lifecycle = contributions.stream().distinct().toList();
        for (int index = lifecycle.size() - 1; index >= 0; index--) {
            try {
                lifecycle.get(index).close();
            } catch (RuntimeException closeFailure) {
                original.addSuppressed(closeFailure);
            }
        }
    }
}
