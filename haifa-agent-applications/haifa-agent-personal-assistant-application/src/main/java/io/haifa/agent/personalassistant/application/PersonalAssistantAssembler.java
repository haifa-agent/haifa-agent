package io.haifa.agent.personalassistant.application;

import io.haifa.agent.artifact.ArtifactService;
import io.haifa.agent.artifact.InMemoryArtifactPayloadStore;
import io.haifa.agent.artifact.InMemoryArtifactStore;
import io.haifa.agent.common.id.UuidV7IdentifierGenerator;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.core.run.AgentRunType;
import io.haifa.agent.personalassistant.application.execution.PersonalExecutionPlatform;
import io.haifa.agent.personalassistant.application.mcp.PersonalMcpConfiguration;
import io.haifa.agent.personalassistant.application.mcp.PersonalMcpPlatform;
import io.haifa.agent.personalassistant.application.mission.SdkMissionRuntimeAccess;
import io.haifa.agent.personalassistant.application.policy.PersonalWebAllowPolicy;
import io.haifa.agent.personalassistant.application.product.PersonalAssistantProfile;
import io.haifa.agent.personalassistant.application.recommendation.PersonalQuestionRecommender;
import io.haifa.agent.personalassistant.application.skill.PersonalSkillPlatform;
import io.haifa.agent.personalassistant.application.tool.PersonalToolPlatform;
import io.haifa.agent.personalassistant.application.trust.PersonalTrustedScriptManifest;
import io.haifa.agent.personalassistant.application.web.PersonalWebPlatform;
import io.haifa.agent.sdk.api.HaifaAgents;
import io.haifa.agent.sdk.api.ModelImageResolver;
import io.haifa.agent.sdk.api.SdkCallerProvider;
import io.haifa.agent.sdk.api.SdkConfigurationDigest;
import io.haifa.agent.sdk.contribution.ArtifactPlatformContribution;
import io.haifa.agent.sdk.contribution.MemoryPlatformContribution;
import io.haifa.agent.sdk.contribution.ModelContribution;
import io.haifa.agent.sdk.contribution.PolicyPlatformContribution;
import io.haifa.agent.sdk.contribution.SdkContributionMetadata;
import io.haifa.agent.sdk.product.ProductCapabilities;
import io.haifa.agent.sdk.product.ProductContributionCoordinate;
import io.haifa.agent.sdk.product.ProductProviderSuitability;
import io.haifa.agent.sdk.product.ProductRunProfile;
import io.haifa.agent.sdk.spi.SdkConversationContribution;
import io.haifa.agent.sdk.spi.SdkPersistenceContribution;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Explicit Composition helper; no classpath scanning or Bean ordering participates in product assembly. */
public final class PersonalAssistantAssembler {
    private PersonalAssistantAssembler() {}

    public static PersonalAssistantApplication assemble(Dependencies dependencies) {
        Objects.requireNonNull(dependencies);
        PersonalTrustedScriptManifest trustManifest =
                PersonalTrustedScriptManifest.load(dependencies.trustedScriptManifest());
        var skills = PersonalSkillPlatform.create(
                dependencies.tenant(),
                dependencies.principal(),
                dependencies.localSkillRoot(),
                dependencies.protectedPaths(),
                trustManifest,
                dependencies.clock());
        PersonalMcpPlatform mcp = PersonalMcpPlatform.connect(
                dependencies.mcp(), dependencies.tenant(), dependencies.principal(), dependencies.clock());
        try {
            var tools = PersonalToolPlatform.create(
                    dependencies.persistence(),
                    skills,
                    mcp,
                    dependencies.web(),
                    dependencies.execution(),
                    dependencies.clock()::instant);
            var coordinates = new PersonalAssistantProfile.ContributionCoordinates(
                    dependencies.model().coordinate(),
                    dependencies.persistence().coordinate(),
                    dependencies.conversation().coordinate(),
                    dependencies.memory().coordinate(),
                    dependencies.policy().coordinate(),
                    tools.tool().coordinate(),
                    tools.skill().coordinate(),
                    tools.mcp().coordinate(),
                    dependencies.web().credential().coordinate(),
                    dependencies.execution().execution().coordinate(),
                    dependencies.execution().shell().coordinate(),
                    dependencies.execution().approval().coordinate(),
                    dependencies.artifact().coordinate());
            var profile = PersonalAssistantProfile.create(
                    coordinates,
                    skills.aliases(),
                    mcp.aliases(),
                    dependencies.web().aliases(),
                    tools.trustedScriptToolAliases());
            Set<String> plannerTools = new LinkedHashSet<>(dependencies.web().aliases());
            mcp.aliases().stream()
                    .filter(alias ->
                            alias.equals("utility_wikipedia_search") || alias.equals("utility_wikipedia_summary"))
                    .forEach(plannerTools::add);
            Set<String> researchTaskTools = new LinkedHashSet<>(plannerTools);
            var agent = HaifaAgents.builder(profile)
                    .callerProvider(dependencies.callers())
                    .timeProvider(dependencies.clock()::instant)
                    .toolApprovalPrompts(dependencies.execution()::approvalPrompt)
                    .publicToolPolicyDecorator(PersonalWebAllowPolicy.decorator(
                            tools.tool().catalog(), dependencies.web(), dependencies.policy(), dependencies.clock()))
                    .modelImageResolver(dependencies.imageResolver())
                    .runProfile(new ProductRunProfile(
                            SdkMissionRuntimeAccess.PLANNER_RUN_PROFILE,
                            "1.0.0",
                            dependencies.modelCatalog().defaultModelId(),
                            AgentRunType.CHAT,
                            new AgentRunBudget(64_000, 8_000, 64_000, 8, 5, 0, "USD", 0),
                            new AgentRunLimits(8, 0, 1, 180_000, 120_000),
                            Map.of("response_format", Map.of("type", "json_object")),
                            java.util.Optional.of(Set.copyOf(plannerTools))))
                    .runProfile(new ProductRunProfile(
                            SdkMissionRuntimeAccess.TASK_RUN_PROFILE,
                            "1.0.0",
                            dependencies.modelCatalog().defaultModelId(),
                            AgentRunType.CHAT,
                            new AgentRunBudget(
                                    192_000,
                                    32_000,
                                    192_000,
                                    SdkMissionRuntimeAccess.TASK_MAX_TOOL_CALLS,
                                    12,
                                    0,
                                    "USD",
                                    0),
                            new AgentRunLimits(24, 0, 1, 600_000, 240_000),
                            Map.of(),
                            java.util.Optional.of(Set.copyOf(researchTaskTools))))
                    .runProfile(new ProductRunProfile(
                            SdkMissionRuntimeAccess.TASK_NORMALIZER_RUN_PROFILE,
                            "1.0.0",
                            dependencies.modelCatalog().defaultModelId(),
                            AgentRunType.CHAT,
                            new AgentRunBudget(64_000, 8_192, 64_000, 0, 2, 0, "USD", 0),
                            new AgentRunLimits(2, 0, 1, 120_000, 120_000),
                            Map.of("response_format", Map.of("type", "json_object")),
                            java.util.Optional.of(Set.of())))
                    .runProfile(new ProductRunProfile(
                            SdkMissionRuntimeAccess.SYNTHESIS_RUN_PROFILE,
                            "1.0.0",
                            dependencies.modelCatalog().defaultModelId(),
                            AgentRunType.CHAT,
                            new AgentRunBudget(64_000, 16_000, 64_000, 0, 1, 0, "USD", 0),
                            new AgentRunLimits(2, 0, 1, 120_000, 120_000),
                            Map.of("response_format", Map.of("type", "json_object")),
                            java.util.Optional.of(Set.of())))
                    .contribute(dependencies.model())
                    .contribute(dependencies.persistence())
                    .contribute(dependencies.conversation())
                    .contribute(dependencies.memory())
                    .contribute(dependencies.policy())
                    .contribute(dependencies.artifact())
                    .contribute(tools.tool())
                    .contribute(tools.skill())
                    .contribute(tools.mcp())
                    .contribute(dependencies.web().credential())
                    .contribute(dependencies.execution().execution())
                    .contribute(dependencies.execution().shell())
                    .contribute(dependencies.execution().approval())
                    .build();
            return new PersonalAssistantApplication(
                    agent,
                    mcp,
                    dependencies.clock(),
                    PersonalCapabilityRegistry.create(tools, mcp),
                    dependencies.modelCatalog(),
                    dependencies.modelPreferences(),
                    new PersonalQuestionRecommender(dependencies.model()),
                    dependencies.execution(),
                    new SdkMissionRuntimeAccess(
                            agent,
                            dependencies.persistence(),
                            dependencies.tenant(),
                            dependencies.principal(),
                            dependencies.clock()::instant,
                            dependencies.modelCatalog(),
                            dependencies.modelCatalog().defaultModelId(),
                            skills.load(
                                    PersonalAssistantProfile.DEEP_RESEARCH_SKILL_ALIAS,
                                    dependencies.tenant(),
                                    dependencies.principal())),
                    dependencies.artifact().service(),
                    skills.bindingReferences());
        } catch (RuntimeException | Error exception) {
            try {
                dependencies.execution().close();
            } catch (RuntimeException closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            try {
                mcp.close();
            } catch (RuntimeException closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            throw exception;
        }
    }

    public record Dependencies(
            TenantRef tenant,
            PrincipalRef principal,
            SdkCallerProvider callers,
            ModelContribution model,
            PersonalModelCatalog modelCatalog,
            PersonalModelPreferenceStore modelPreferences,
            SdkPersistenceContribution persistence,
            SdkConversationContribution conversation,
            MemoryPlatformContribution memory,
            PolicyPlatformContribution policy,
            ArtifactPlatformContribution artifact,
            PersonalExecutionPlatform execution,
            PersonalWebPlatform web,
            PersonalMcpConfiguration mcp,
            Optional<Path> localSkillRoot,
            Optional<Path> trustedScriptManifest,
            List<Path> protectedPaths,
            Clock clock,
            ModelImageResolver imageResolver) {
        public Dependencies(
                TenantRef tenant,
                PrincipalRef principal,
                SdkCallerProvider callers,
                ModelContribution model,
                SdkPersistenceContribution persistence,
                SdkConversationContribution conversation,
                MemoryPlatformContribution memory,
                PolicyPlatformContribution policy,
                PersonalExecutionPlatform execution,
                PersonalWebPlatform web,
                PersonalMcpConfiguration mcp,
                Optional<Path> localSkillRoot,
                List<Path> protectedPaths,
                Clock clock) {
            this(
                    tenant,
                    principal,
                    callers,
                    model,
                    defaultCatalog(model),
                    new InMemoryPersonalModelPreferenceStore(),
                    persistence,
                    conversation,
                    memory,
                    policy,
                    defaultArtifact(clock),
                    execution,
                    web,
                    mcp,
                    localSkillRoot,
                    Optional.empty(),
                    protectedPaths,
                    clock,
                    ModelImageResolver.unsupported());
        }

        public Dependencies(
                TenantRef tenant,
                PrincipalRef principal,
                SdkCallerProvider callers,
                ModelContribution model,
                SdkPersistenceContribution persistence,
                SdkConversationContribution conversation,
                MemoryPlatformContribution memory,
                PolicyPlatformContribution policy,
                PersonalExecutionPlatform execution,
                PersonalWebPlatform web,
                PersonalMcpConfiguration mcp,
                Optional<Path> localSkillRoot,
                Optional<Path> trustedScriptManifest,
                List<Path> protectedPaths,
                Clock clock) {
            this(
                    tenant,
                    principal,
                    callers,
                    model,
                    defaultCatalog(model),
                    new InMemoryPersonalModelPreferenceStore(),
                    persistence,
                    conversation,
                    memory,
                    policy,
                    defaultArtifact(clock),
                    execution,
                    web,
                    mcp,
                    localSkillRoot,
                    trustedScriptManifest,
                    protectedPaths,
                    clock,
                    ModelImageResolver.unsupported());
        }

        public Dependencies {
            Objects.requireNonNull(tenant);
            Objects.requireNonNull(principal);
            Objects.requireNonNull(callers);
            Objects.requireNonNull(model);
            Objects.requireNonNull(modelCatalog);
            Objects.requireNonNull(modelPreferences);
            Objects.requireNonNull(persistence);
            Objects.requireNonNull(conversation);
            Objects.requireNonNull(memory);
            Objects.requireNonNull(policy);
            Objects.requireNonNull(artifact);
            Objects.requireNonNull(execution);
            Objects.requireNonNull(web);
            Objects.requireNonNull(mcp);
            localSkillRoot = Objects.requireNonNull(localSkillRoot);
            trustedScriptManifest = Objects.requireNonNull(trustedScriptManifest);
            protectedPaths = List.copyOf(protectedPaths);
            Objects.requireNonNull(clock);
            Objects.requireNonNull(imageResolver);
        }

        private static PersonalModelCatalog defaultCatalog(ModelContribution model) {
            var snapshot = model.snapshot();
            var option = new PersonalModelOption(
                    snapshot.modelId().value(),
                    snapshot.modelId().value(),
                    snapshot.providerId().value(),
                    snapshot.providerId().value(),
                    snapshot.capabilities().stream().map(Enum::name).collect(java.util.stream.Collectors.toSet()),
                    snapshot.contextWindow());
            return new PersonalModelCatalog() {
                @Override
                public String defaultModelId() {
                    return option.id();
                }

                @Override
                public List<PersonalModelOption> available() {
                    return List.of(option);
                }
            };
        }

        private static ArtifactPlatformContribution defaultArtifact(Clock clock) {
            return new ArtifactPlatformContribution(
                    new SdkContributionMetadata(
                            new ProductContributionCoordinate("haifa-personal-in-memory-artifact", "1.0.0"),
                            ProductCapabilities.ARTIFACT,
                            SdkConfigurationDigest.sha256("personal-in-memory-artifact-v1"),
                            ProductProviderSuitability.DEVELOPMENT,
                            "Personal Assistant in-memory Artifact storage"),
                    new ArtifactService(
                            new InMemoryArtifactStore(),
                            new InMemoryArtifactPayloadStore(),
                            new UuidV7IdentifierGenerator(),
                            clock::instant));
        }
    }
}
