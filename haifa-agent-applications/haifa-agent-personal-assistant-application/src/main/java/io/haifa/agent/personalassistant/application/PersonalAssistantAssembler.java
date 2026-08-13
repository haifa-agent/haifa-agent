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
import io.haifa.agent.personalassistant.application.mission.MissionModelBinding;
import io.haifa.agent.personalassistant.application.mission.MissionTaskRunInput;
import io.haifa.agent.personalassistant.application.policy.PersonalWebAllowPolicy;
import io.haifa.agent.personalassistant.application.product.PersonalAssistantProfile;
import io.haifa.agent.personalassistant.application.recommendation.PersonalQuestionRecommender;
import io.haifa.agent.personalassistant.application.runtime.SdkMissionRuntimeAccess;
import io.haifa.agent.personalassistant.application.skill.PersonalSkillPlatform;
import io.haifa.agent.personalassistant.application.tool.PersonalToolPlatform;
import io.haifa.agent.personalassistant.application.trust.PersonalTrustedScriptManifest;
import io.haifa.agent.personalassistant.application.web.PersonalWebPlatform;
import io.haifa.agent.runtime.core.bootstrap.RuntimeControlOptions;
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
import io.haifa.agent.tool.api.ToolInvocationException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
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
            var agentBuilder = HaifaAgents.builder(profile)
                    .callerProvider(dependencies.callers())
                    .timeProvider(dependencies.clock()::instant)
                    .toolApprovalPrompts(dependencies.execution()::approvalPrompt)
                    .publicToolPolicyDecorator(PersonalWebAllowPolicy.decorator(
                            tools.tool().catalog(), dependencies.web(), dependencies.policy(), dependencies.clock()))
                    .modelImageResolver(dependencies.imageResolver())
                    .toolRetry(
                            2,
                            PersonalAssistantAssembler::isTransientToolFailure,
                            Duration.ofMillis(250),
                            Duration.ofSeconds(1),
                            2.0d)
                    .runProfile(new ProductRunProfile(
                            SdkMissionRuntimeAccess.PLANNER_RUN_PROFILE,
                            "1.0.0",
                            dependencies.modelCatalog().defaultModelId(),
                            AgentRunType.CHAT,
                            new AgentRunBudget(128_000, 16_000, 128_000, 16, 10, 0, "USD", 0),
                            new AgentRunLimits(16, 0, 1, 180_000, 120_000),
                            Map.of("response_format", Map.of("type", "json_object")),
                            java.util.Optional.of(Set.copyOf(plannerTools))))
                    .runProfile(new ProductRunProfile(
                            SdkMissionRuntimeAccess.PLANNER_REPAIR_RUN_PROFILE,
                            "1.0.0",
                            dependencies.modelCatalog().defaultModelId(),
                            AgentRunType.CHAT,
                            new AgentRunBudget(96_000, 16_000, 96_000, 0, 2, 0, "USD", 0),
                            new AgentRunLimits(4, 0, 1, 120_000, 120_000),
                            Map.of("response_format", Map.of("type", "json_object")),
                            java.util.Optional.of(Set.of())))
                    .runProfile(new ProductRunProfile(
                            SdkMissionRuntimeAccess.TASK_RUN_PROFILE,
                            "1.0.0",
                            dependencies.modelCatalog().defaultModelId(),
                            AgentRunType.CHAT,
                            new AgentRunBudget(
                                    384_000,
                                    64_000,
                                    384_000,
                                    SdkMissionRuntimeAccess.TASK_MAX_TOOL_CALLS,
                                    24,
                                    0,
                                    "USD",
                                    0),
                            new AgentRunLimits(48, 0, 1, 600_000, 240_000),
                            Map.of(
                                    RuntimeControlOptions.FINALIZE_AFTER_TOOL_CALLS,
                                    MissionTaskRunInput.PRIMARY_RESEARCH_TOOL_CALL_STOP_TARGET),
                            java.util.Optional.of(Set.copyOf(researchTaskTools))))
                    .runProfile(new ProductRunProfile(
                            SdkMissionRuntimeAccess.DEPENDENT_TASK_RUN_PROFILE,
                            "1.0.0",
                            dependencies.modelCatalog().defaultModelId(),
                            AgentRunType.CHAT,
                            new AgentRunBudget(
                                    384_000,
                                    64_000,
                                    384_000,
                                    MissionTaskRunInput.DEPENDENCY_AWARE_TOOL_CALL_HARD_LIMIT,
                                    20,
                                    0,
                                    "USD",
                                    0),
                            new AgentRunLimits(40, 0, 1, 600_000, 240_000),
                            Map.of(
                                    RuntimeControlOptions.FINALIZE_AFTER_TOOL_CALLS,
                                    MissionTaskRunInput.DEPENDENCY_AWARE_TOOL_CALL_STOP_TARGET),
                            java.util.Optional.of(Set.copyOf(researchTaskTools))))
                    .runProfile(new ProductRunProfile(
                            SdkMissionRuntimeAccess.TASK_NORMALIZER_RUN_PROFILE,
                            "1.0.0",
                            dependencies.modelCatalog().defaultModelId(),
                            AgentRunType.CHAT,
                            new AgentRunBudget(128_000, 16_384, 128_000, 0, 4, 0, "USD", 0),
                            new AgentRunLimits(4, 0, 1, 120_000, 120_000),
                            Map.of("response_format", Map.of("type", "json_object")),
                            java.util.Optional.of(Set.of())))
                    .runProfile(new ProductRunProfile(
                            SdkMissionRuntimeAccess.SYNTHESIS_RUN_PROFILE,
                            "1.0.0",
                            dependencies.modelCatalog().defaultModelId(),
                            AgentRunType.CHAT,
                            new AgentRunBudget(128_000, 32_000, 128_000, 0, 2, 0, "USD", 0),
                            new AgentRunLimits(4, 0, 1, 120_000, 120_000),
                            Map.of("response_format", Map.of("type", "json_object")),
                            java.util.Optional.of(Set.of())))
                    .runProfile(new ProductRunProfile(
                            SdkMissionRuntimeAccess.RESEARCH_SYNTHESIS_RUN_PROFILE,
                            "1.0.0",
                            dependencies.modelCatalog().defaultModelId(),
                            AgentRunType.CHAT,
                            new AgentRunBudget(128_000, 32_000, 128_000, 0, 2, 0, "USD", 0),
                            new AgentRunLimits(4, 0, 1, 120_000, 120_000),
                            Map.of(),
                            java.util.Optional.of(Set.of())));
            dependencies
                    .modelCatalog()
                    .runProfiles()
                    .forEach(selection -> agentBuilder.runProfile(new ProductRunProfile(
                            selection.runProfileId(),
                            "1.0.0",
                            selection.option().id(),
                            AgentRunType.CHAT,
                            profile.budget(),
                            profile.limits(),
                            Map.of(),
                            Optional.of(selection.effectiveParameters()),
                            Optional.empty())));
            dependencies.modelCatalog().available().stream()
                    .filter(model ->
                            !model.id().equals(dependencies.modelCatalog().defaultModelId()))
                    .flatMap(
                            model -> missionRunProfiles(
                                    model,
                                    dependencies.modelCatalog().defaultModelId(),
                                    plannerTools,
                                    researchTaskTools)
                                    .stream())
                    .forEach(agentBuilder::runProfile);
            var agent = agentBuilder
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

    private static List<ProductRunProfile> missionRunProfiles(
            PersonalModelOption model, String defaultModelId, Set<String> plannerTools, Set<String> researchTaskTools) {
        Map<String, Object> structuredOptions = model.capabilities().contains("STRUCTURED_OUTPUT")
                ? Map.of("response_format", Map.of("type", "json_object"))
                : Map.of();
        return List.of(
                new ProductRunProfile(
                        SdkMissionRuntimeAccess.profileId(
                                SdkMissionRuntimeAccess.PLANNER_RUN_PROFILE, model.id(), defaultModelId),
                        "1.0.0",
                        model.id(),
                        AgentRunType.CHAT,
                        new AgentRunBudget(128_000, 16_000, 128_000, 16, 10, 0, "USD", 0),
                        new AgentRunLimits(16, 0, 1, 180_000, 120_000),
                        structuredOptions,
                        Optional.of(Set.copyOf(plannerTools))),
                new ProductRunProfile(
                        SdkMissionRuntimeAccess.profileId(
                                SdkMissionRuntimeAccess.PLANNER_REPAIR_RUN_PROFILE, model.id(), defaultModelId),
                        "1.0.0",
                        model.id(),
                        AgentRunType.CHAT,
                        new AgentRunBudget(96_000, 16_000, 96_000, 0, 2, 0, "USD", 0),
                        new AgentRunLimits(4, 0, 1, 120_000, 120_000),
                        structuredOptions,
                        Optional.of(Set.of())),
                new ProductRunProfile(
                        SdkMissionRuntimeAccess.profileId(
                                SdkMissionRuntimeAccess.TASK_RUN_PROFILE, model.id(), defaultModelId),
                        "1.0.0",
                        model.id(),
                        AgentRunType.CHAT,
                        new AgentRunBudget(
                                384_000, 64_000, 384_000, SdkMissionRuntimeAccess.TASK_MAX_TOOL_CALLS, 24, 0, "USD", 0),
                        new AgentRunLimits(48, 0, 1, 600_000, 240_000),
                        Map.of(
                                RuntimeControlOptions.FINALIZE_AFTER_TOOL_CALLS,
                                MissionTaskRunInput.PRIMARY_RESEARCH_TOOL_CALL_STOP_TARGET),
                        Optional.of(Set.copyOf(researchTaskTools))),
                new ProductRunProfile(
                        SdkMissionRuntimeAccess.profileId(
                                SdkMissionRuntimeAccess.DEPENDENT_TASK_RUN_PROFILE, model.id(), defaultModelId),
                        "1.0.0",
                        model.id(),
                        AgentRunType.CHAT,
                        new AgentRunBudget(
                                384_000,
                                64_000,
                                384_000,
                                MissionTaskRunInput.DEPENDENCY_AWARE_TOOL_CALL_HARD_LIMIT,
                                20,
                                0,
                                "USD",
                                0),
                        new AgentRunLimits(40, 0, 1, 600_000, 240_000),
                        Map.of(
                                RuntimeControlOptions.FINALIZE_AFTER_TOOL_CALLS,
                                MissionTaskRunInput.DEPENDENCY_AWARE_TOOL_CALL_STOP_TARGET),
                        Optional.of(Set.copyOf(researchTaskTools))),
                new ProductRunProfile(
                        SdkMissionRuntimeAccess.profileId(
                                SdkMissionRuntimeAccess.TASK_NORMALIZER_RUN_PROFILE, model.id(), defaultModelId),
                        "1.0.0",
                        model.id(),
                        AgentRunType.CHAT,
                        new AgentRunBudget(128_000, 16_384, 128_000, 0, 4, 0, "USD", 0),
                        new AgentRunLimits(4, 0, 1, 120_000, 120_000),
                        structuredOptions,
                        Optional.of(Set.of())),
                new ProductRunProfile(
                        SdkMissionRuntimeAccess.profileId(
                                SdkMissionRuntimeAccess.SYNTHESIS_RUN_PROFILE, model.id(), defaultModelId),
                        "1.0.0",
                        model.id(),
                        AgentRunType.CHAT,
                        new AgentRunBudget(128_000, 32_000, 128_000, 0, 2, 0, "USD", 0),
                        new AgentRunLimits(4, 0, 1, 120_000, 120_000),
                        structuredOptions,
                        Optional.of(Set.of())),
                new ProductRunProfile(
                        SdkMissionRuntimeAccess.profileId(
                                SdkMissionRuntimeAccess.RESEARCH_SYNTHESIS_RUN_PROFILE, model.id(), defaultModelId),
                        "1.0.0",
                        model.id(),
                        AgentRunType.CHAT,
                        new AgentRunBudget(128_000, 32_000, 128_000, 0, 2, 0, "USD", 0),
                        new AgentRunLimits(4, 0, 1, 120_000, 120_000),
                        Map.of(),
                        Optional.of(Set.of())));
    }

    static boolean isTransientToolFailure(RuntimeException failure) {
        if (!(failure instanceof ToolInvocationException invocation)) return false;
        return Set.of("MCP_CALL_DEADLINE_EXCEEDED", "MCP_CALL_OUTCOME_UNKNOWN", "MCP_SESSION_INVALID", "MCP_NOT_READY")
                .contains(invocation.failureCode());
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
            boolean reasoning = snapshot.capabilities().contains(io.haifa.agent.model.api.ModelCapability.REASONING);
            var profile = io.haifa.agent.model.api.ModelBindingProfile.create(
                    snapshot.modelId(),
                    snapshot.apiStyle(),
                    "1.0",
                    snapshot.capabilities(),
                    reasoning
                            ? io.haifa.agent.model.api.ModelReasoningBehavior.OPTIONAL
                            : io.haifa.agent.model.api.ModelReasoningBehavior.NONE,
                    reasoning
                            ? java.util.EnumSet.of(
                                    io.haifa.agent.model.api.ModelReasoningMode.DISABLED,
                                    io.haifa.agent.model.api.ModelReasoningMode.ENABLED)
                            : Set.of(io.haifa.agent.model.api.ModelReasoningMode.DISABLED),
                    reasoning ? Set.of(io.haifa.agent.model.api.ModelReasoningEffort.HIGH) : Set.of(),
                    java.util.OptionalLong.empty(),
                    1,
                    snapshot.maxOutputTokens(),
                    false,
                    io.haifa.agent.model.api.ModelProfileStatus.VERIFIED,
                    java.time.LocalDate.of(2026, 8, 13));
            var defaults = new PersonalModelProductDefaults();
            var option = new PersonalModelOption(
                    snapshot.modelId().value(),
                    snapshot.providerId().value() + ":" + snapshot.providerModelId(),
                    snapshot.modelId().value(),
                    snapshot.providerId().value(),
                    snapshot.providerId().value(),
                    snapshot.apiStyle().value(),
                    snapshot.apiStyle().value(),
                    "AVAILABLE",
                    "",
                    snapshot.capabilities().stream().map(Enum::name).collect(java.util.stream.Collectors.toSet()),
                    snapshot.contextWindow(),
                    snapshot.maxOutputTokens(),
                    PersonalModelProductDefaults.PREFERENCE_SCHEMA_VERSION,
                    profile.version(),
                    profile.digest(),
                    defaults.controls(
                            profile,
                            snapshot.modelId().value(),
                            List.of(snapshot.modelId().value())),
                    PersonalModelPreferences.recommended());
            return new PersonalModelCatalog() {
                @Override
                public String defaultModelId() {
                    return option.id();
                }

                @Override
                public List<PersonalModelOption> available() {
                    return List.of(option);
                }

                @Override
                public Optional<MissionModelBinding> binding(String modelId) {
                    if (!snapshot.modelId().value().equals(modelId)) return Optional.empty();
                    return Optional.of(new MissionModelBinding(
                            option.id(),
                            option.displayName(),
                            option.providerId(),
                            option.providerDisplayName(),
                            snapshot.configurationDigest()));
                }

                @Override
                public Optional<io.haifa.agent.model.api.ModelBindingProfile> profile(String modelBindingId) {
                    return option.id().equals(modelBindingId) ? Optional.of(profile) : Optional.empty();
                }

                @Override
                public PersonalResolvedModelSelection resolve(PersonalModelSelectionRequest request) {
                    if (!option.id().equals(request.modelBindingId())
                            || !option.preferenceSchemaVersion().equals(request.preferenceSchemaVersion())
                            || !profile.version().equals(request.profileVersion())
                            || !profile.digest().equals(request.profileDigest())) {
                        throw new IllegalArgumentException("MODEL_PROFILE_STALE");
                    }
                    var effective = defaults.resolve(profile, request.preferences());
                    String runProfileId = "pa-conversation-"
                            + request.preferences().responseLength().name().toLowerCase(java.util.Locale.ROOT);
                    return new PersonalResolvedModelSelection(option, request.preferences(), effective, runProfileId);
                }

                @Override
                public List<PersonalResolvedModelSelection> runProfiles() {
                    return java.util.Arrays.stream(PersonalResponseLength.values())
                            .map(length -> resolve(new PersonalModelSelectionRequest(
                                    option.id(),
                                    option.preferenceSchemaVersion(),
                                    option.profileVersion(),
                                    option.profileDigest(),
                                    new PersonalModelPreferences(
                                            PersonalResponseMode.RECOMMENDED, Optional.empty(), length))))
                            .toList();
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
