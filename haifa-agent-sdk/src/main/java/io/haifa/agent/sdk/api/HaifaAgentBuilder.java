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
import io.haifa.agent.sdk.contribution.ApprovalPlatformContribution;
import io.haifa.agent.sdk.contribution.ArtifactPlatformContribution;
import io.haifa.agent.sdk.contribution.ContextSourceContribution;
import io.haifa.agent.sdk.contribution.CredentialPlatformContribution;
import io.haifa.agent.sdk.contribution.ExecutionPlatformContribution;
import io.haifa.agent.sdk.contribution.McpToolCatalogContribution;
import io.haifa.agent.sdk.contribution.MemoryPlatformContribution;
import io.haifa.agent.sdk.contribution.ModelContribution;
import io.haifa.agent.sdk.contribution.PolicyPlatformContribution;
import io.haifa.agent.sdk.contribution.SkillPlatformContribution;
import io.haifa.agent.sdk.contribution.ToolPlatformContribution;
import io.haifa.agent.sdk.internal.DefaultConversationService;
import io.haifa.agent.sdk.internal.ProductAssemblyResolver;
import io.haifa.agent.sdk.internal.SafeConversationService;
import io.haifa.agent.sdk.product.ProductAssemblyException;
import io.haifa.agent.sdk.product.ProductCapabilities;
import io.haifa.agent.sdk.product.ProductCapabilityId;
import io.haifa.agent.sdk.product.ProductContribution;
import io.haifa.agent.sdk.product.ProductProfile;
import io.haifa.agent.sdk.spi.SdkConversationContribution;
import io.haifa.agent.sdk.spi.SdkPersistenceContribution;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Fluent bootstrap builder. Product behavior is selected by Profile, not by hard-coded product branches. */
public final class HaifaAgentBuilder {
    private ProductProfile profile;
    private final List<ProductContribution> contributions = new ArrayList<>();
    private SdkCallerProvider callers = SdkCallerProvider.defaultPublicUser();
    private IdentifierGenerator ids = new UuidV7IdentifierGenerator();
    private TimeProvider time = new SystemTimeProvider();

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

    public HaifaAgentBuilder contribute(ProductContribution value) {
        contributions.add(Objects.requireNonNull(value, "value must not be null"));
        return this;
    }

    public HaifaAgentBuilder contributeAll(List<? extends ProductContribution> values) {
        Objects.requireNonNull(values, "values must not be null").forEach(this::contribute);
        return this;
    }

    public HaifaAgent build() {
        Objects.requireNonNull(profile, "a Product Profile must be configured");
        ProductAssemblyResolver.Resolution resolution = new ProductAssemblyResolver().resolve(profile, contributions);
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
        if (artifact != null && profile.policies().artifact().maxArtifactsPerRun() == 0) {
            throw new ProductAssemblyException(
                    "ARTIFACT_POLICY_DISABLED", "Artifact contribution is forbidden by the Product Profile policy");
        }
        if (execution != null && !profile.policies().execution().enabled()) {
            throw new ProductAssemblyException(
                    "EXECUTION_POLICY_DISABLED", "Execution contribution is forbidden by the Product Profile policy");
        }
        validateDeclaredAliases(resolution.selected());

        List<ProductContribution> initialized = initializeSelected(resolution);
        LocalExecutionScheduler scheduler;
        try {
            scheduler = new LocalExecutionScheduler();
        } catch (RuntimeException | Error exception) {
            closeAfterFailedBuild(initialized, exception);
            throw exception;
        }
        try {
            RuntimeCoreBuilder runtimeBuilder = new RuntimeCoreBuilder()
                    .identifierGenerator(ids)
                    .timeProvider(time)
                    .scheduler(scheduler)
                    .persistence(persistence.runtimePersistence())
                    .callers(() -> {
                        SdkCaller caller = Objects.requireNonNull(callers.current(), "caller provider returned null");
                        return new RuntimeCallerContext(caller.tenant(), caller.principal());
                    })
                    .definitions((id, requested) -> new ResolvedDefinition(
                            id,
                            requested.orElse(profile.definitionVersion()),
                            profile.allowedTools(),
                            profile.allowedSkills(),
                            Set.of(),
                            profile.instructions(),
                            List.of()))
                    .profiles((id, overrides) -> new ResolvedProfile(
                            id,
                            profile.runProfileVersion(),
                            AgentRunType.CHAT,
                            profile.budget(),
                            profile.limits(),
                            model.snapshot(),
                            resolvedCapabilities(resolution)))
                    .registerChatModel(
                            model.snapshot().adapterType(), model.snapshot().adapterVersion(), model.model());

            ProductContribution tool = resolution.selected().get(ProductCapabilities.TOOL);
            if (tool instanceof ToolPlatformContribution platform) {
                runtimeBuilder.toolPlatform(platform.catalog(), platform.invoker(), platform.schemaValidator());
            }
            ProductContribution skill = resolution.selected().get(ProductCapabilities.SKILL);
            if (skill instanceof SkillPlatformContribution platform) {
                runtimeBuilder.skillPlatform(platform.catalog(), platform.contentLoader());
            }
            if (context != null) {
                context.sources().forEach(runtimeBuilder::registerContextSource);
            }
            if (memory != null) {
                runtimeBuilder.memory(memory.service(), memory.retriever(), memory.audit());
            }
            if (policy != null) {
                runtimeBuilder.policyStores(policy.decisions(), policy.authorizationEvidence());
            }
            if (approval != null) {
                runtimeBuilder.approvalVerification(approval.verification());
            }
            if (credential != null) {
                runtimeBuilder.credentialBroker(credential.broker());
            }

            var runtime = runtimeBuilder.build();
            var conversationService = new DefaultConversationService(
                    profile, runtime, persistence, conversation.conversationStore(), callers, ids, time);
            AtomicBoolean lifecycleClosed = new AtomicBoolean();
            return new HaifaAgent(
                    resolution.assembly(),
                    new AgentRuns(runtime),
                    new SafeConversationService(conversationService, lifecycleClosed),
                    memory == null ? java.util.Optional.empty() : java.util.Optional.of(memory.service()),
                    artifact == null ? java.util.Optional.empty() : java.util.Optional.of(artifact.service()),
                    scheduler,
                    initialized,
                    lifecycleClosed);
        } catch (RuntimeException | Error exception) {
            scheduler.close();
            closeAfterFailedBuild(initialized, exception);
            throw exception;
        }
    }

    private static List<ProductContribution> initializeSelected(ProductAssemblyResolver.Resolution resolution) {
        List<ProductContribution> selected = resolution.selected().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
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

    private Map<String, ResolvedCapability> resolvedCapabilities(ProductAssemblyResolver.Resolution resolution) {
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

    private void validateDeclaredAliases(Map<ProductCapabilityId, ProductContribution> selected) {
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
