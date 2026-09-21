package io.haifa.agent.personalassistant.application;

import io.haifa.agent.artifact.ArtifactService;
import io.haifa.agent.artifact.InMemoryArtifactPayloadStore;
import io.haifa.agent.artifact.InMemoryArtifactStore;
import io.haifa.agent.common.id.UuidV7IdentifierGenerator;
import io.haifa.agent.context.compression.CompressionPolicy;
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
import io.haifa.agent.personalassistant.application.research.RuntimeFetchEvidenceReader;
import io.haifa.agent.personalassistant.application.runtime.SdkMissionRuntimeAccess;
import io.haifa.agent.personalassistant.application.skill.PersonalSkillPlatform;
import io.haifa.agent.personalassistant.application.tool.PersonalToolPlatform;
import io.haifa.agent.personalassistant.application.trust.PersonalTrustedScriptManifest;
import io.haifa.agent.personalassistant.application.web.PersonalWebPlatform;
import io.haifa.agent.runtime.core.bootstrap.RuntimeControlOptions;
import io.haifa.agent.sdk.api.HaifaAgents;
import io.haifa.agent.sdk.api.ModelAudioResolver;
import io.haifa.agent.sdk.api.ModelImageResolver;
import io.haifa.agent.sdk.api.SdkCallerProvider;
import io.haifa.agent.sdk.api.SdkConfigurationDigest;
import io.haifa.agent.sdk.contribution.ArtifactPlatformContribution;
import io.haifa.agent.sdk.contribution.MemoryPlatformContribution;
import io.haifa.agent.sdk.contribution.ModelContribution;
import io.haifa.agent.sdk.contribution.PolicyPlatformContribution;
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
    public static final int PERSONAL_ASSISTANT_ACTIVE_HISTORY_BUDGET_PERCENT = 25;
    public static final long PERSONAL_ASSISTANT_MIN_ACTIVE_HISTORY_BUDGET_TOKENS = 48_000L;
    public static final long PERSONAL_ASSISTANT_MAX_ACTIVE_HISTORY_BUDGET_TOKENS = 96_000L;
    public static final int PERSONAL_ASSISTANT_TARGET_TAIL_TOKEN_PERCENT = 40;
    public static final int PERSONAL_ASSISTANT_MIN_TAIL_TOKENS = 24_000;
    public static final int PERSONAL_ASSISTANT_MAX_TAIL_TOKENS = 32_000;

    public static CompressionPolicy defaultCompressionPolicy() {
        return CompressionPolicy.defaults()
                .withSemanticCompactionEnabled(true)
                .withDynamicActiveBudget(
                        PERSONAL_ASSISTANT_ACTIVE_HISTORY_BUDGET_PERCENT,
                        PERSONAL_ASSISTANT_MIN_ACTIVE_HISTORY_BUDGET_TOKENS,
                        PERSONAL_ASSISTANT_MAX_ACTIVE_HISTORY_BUDGET_TOKENS)
                .withTailTokenBounds(PERSONAL_ASSISTANT_MIN_TAIL_TOKENS, PERSONAL_ASSISTANT_MAX_TAIL_TOKENS)
                .withTargetTailTokenPercent(PERSONAL_ASSISTANT_TARGET_TAIL_TOKEN_PERCENT);
    }

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
            var profile = PersonalAssistantProfile.create(
                    skills.aliases(), mcp.aliases(), dependencies.web().aliases(), tools.trustedScriptToolAliases());
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
                            tools.tool().catalog(), dependencies.web(), dependencies.policy()))
                    .modelImageResolver(dependencies.imageResolver())
                    .modelAudioResolver(dependencies.audioResolver())
                    .compressionPolicy(defaultCompressionPolicy())
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
                            new AgentRunLimits(16, 0, 1, 180_000, 120_000, 10, 16, 0),
                            Map.of("response_format", Map.of("type", "json_object")),
                            java.util.Optional.of(Set.copyOf(plannerTools))))
                    .runProfile(new ProductRunProfile(
                            SdkMissionRuntimeAccess.PLANNER_REPAIR_RUN_PROFILE,
                            "1.0.0",
                            dependencies.modelCatalog().defaultModelId(),
                            AgentRunType.CHAT,
                            new AgentRunBudget(96_000, 16_000, 96_000, 0, 2, 0, "USD", 0),
                            new AgentRunLimits(4, 0, 1, 120_000, 120_000, 2, 0, 0),
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
                            new AgentRunLimits(
                                    48, 0, 1, 600_000, 240_000, 24, SdkMissionRuntimeAccess.TASK_MAX_TOOL_CALLS, 0),
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
                            new AgentRunLimits(
                                    40,
                                    0,
                                    1,
                                    600_000,
                                    240_000,
                                    20,
                                    MissionTaskRunInput.DEPENDENCY_AWARE_TOOL_CALL_HARD_LIMIT,
                                    0),
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
                            new AgentRunLimits(4, 0, 1, 120_000, 120_000, 4, 0, 0),
                            Map.of("response_format", Map.of("type", "json_object")),
                            java.util.Optional.of(Set.of())))
                    .runProfile(new ProductRunProfile(
                            SdkMissionRuntimeAccess.SYNTHESIS_RUN_PROFILE,
                            "1.0.0",
                            dependencies.modelCatalog().defaultModelId(),
                            AgentRunType.CHAT,
                            new AgentRunBudget(256_000, 32_000, 256_000, 0, 2, 0, "USD", 0),
                            new AgentRunLimits(4, 0, 1, 180_000, 180_000, 2, 0, 0),
                            Map.of("response_format", Map.of("type", "json_object")),
                            java.util.Optional.of(Set.of())))
                    .runProfile(new ProductRunProfile(
                            SdkMissionRuntimeAccess.RESEARCH_SYNTHESIS_RUN_PROFILE,
                            "1.0.0",
                            dependencies.modelCatalog().defaultModelId(),
                            AgentRunType.CHAT,
                            new AgentRunBudget(256_000, 32_000, 256_000, 0, 2, 0, "USD", 0),
                            new AgentRunLimits(4, 0, 1, 180_000, 180_000, 2, 0, 0),
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
                    .model(dependencies.model())
                    .persistence(dependencies.persistence())
                    .conversation(dependencies.conversation())
                    .memory(dependencies.memory())
                    .policy(dependencies.policy())
                    .artifacts(dependencies.artifact())
                    .toolPlatform(tools.tool())
                    .skillPlatform(tools.skill())
                    .credentials(dependencies.web().credential())
                    .approval(dependencies.execution().approval())
                    .build();
            return new PersonalAssistantApplication(
                    agent,
                    mcp,
                    dependencies.clock(),
                    PersonalCapabilityRegistry.create(tools, mcp),
                    dependencies.modelCatalog(),
                    dependencies.modelPreferences(),
                    new PersonalQuestionRecommender(dependencies.model()),
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
                    skills.bindingReferences(),
                    productDigest(profile, dependencies, tools),
                    new RuntimeFetchEvidenceReader(dependencies.persistence().runtimePersistence()),
                    dependencies.execution().previewPublisher());
        } catch (RuntimeException | Error exception) {
            try {
                mcp.close();
            } catch (RuntimeException closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            throw exception;
        }
    }

    private static String productDigest(
            io.haifa.agent.sdk.product.ProductProfile profile, Dependencies dependencies, PersonalToolPlatform tools) {
        List<String> fields = new java.util.ArrayList<>();
        fields.add("personal-assistant-product-v2");
        appendProfileFields(fields, profile);
        io.haifa.agent.model.api.ResolvedModelSnapshot model =
                dependencies.model().snapshot();
        fields.add("model.id");
        fields.add(model.modelId().value());
        fields.add("model.digest");
        fields.add(model.configurationDigest());
        dependencies.model().snapshots().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    fields.add("model.snapshot.id");
                    fields.add(entry.getKey());
                    fields.add("model.snapshot.digest");
                    fields.add(entry.getValue().configurationDigest());
                });
        fields.add("tool.catalog.digest");
        fields.add(tools.tool().catalog().snapshot().digest());
        fields.add("skill.catalog.digest");
        fields.add(tools.skill().catalog().snapshot().digest().value());
        fields.add("policy.rules.digest");
        fields.add(dependencies.policy().rules().contentDigest());
        io.haifa.agent.sdk.product.ProductMemoryPolicy memoryPolicy =
                dependencies.memory().policy();
        fields.add("memory.policy.manualReviewRequired");
        fields.add(Boolean.toString(memoryPolicy.manualReviewRequired()));
        fields.add("memory.policy.maxCandidateContentChars");
        fields.add(Integer.toString(memoryPolicy.maxCandidateContentChars()));
        fields.add("memory.policy.maxQueryLimit");
        fields.add(Integer.toString(memoryPolicy.maxQueryLimit()));
        io.haifa.agent.sdk.product.ProductArtifactPolicy artifactPolicy =
                dependencies.artifact().policy();
        fields.add("artifact.policy.maxArtifactBytes");
        fields.add(Long.toString(artifactPolicy.maxArtifactBytes()));
        fields.add("artifact.policy.maxArtifactsPerRun");
        fields.add(Integer.toString(artifactPolicy.maxArtifactsPerRun()));
        fields.add("artifact.policy.maxArtifactBytesPerRun");
        fields.add(Long.toString(artifactPolicy.maxArtifactBytesPerRun()));
        fields.add("artifact.policy.allowedMediaTypes");
        artifactPolicy.allowedMediaTypes().stream().sorted().forEach(fields::add);
        fields.add("artifact.policy.rangeSupported");
        fields.add(Boolean.toString(artifactPolicy.rangeSupported()));
        fields.add("artifact.policy.localSoftLimitBytes");
        fields.add(Long.toString(artifactPolicy.localSoftLimitBytes()));
        fields.add("artifact.policy.localHardLimitBytes");
        fields.add(Long.toString(artifactPolicy.localHardLimitBytes()));
        fields.add("artifact.policy.requiredCompletionGate");
        fields.add(Boolean.toString(artifactPolicy.requiredCompletionGate()));
        fields.add("web.contribution.binding");
        dependencies.web().contributions().stream()
                .sorted(java.util.Comparator.comparing(item -> item.alias().value()))
                .forEach(item -> {
                    fields.add(item.alias().value());
                    fields.add(item.providerBindingReference());
                });
        fields.add("execution.shellRuntime.os");
        fields.add(dependencies.execution().shellRuntime().operatingSystem());
        fields.add("execution.shellRuntime.languages");
        dependencies.execution().shellRuntime().scriptLanguages().stream()
                .sorted()
                .forEach(fields::add);
        fields.add("persistence.class");
        fields.add(dependencies.persistence().getClass().getName());
        fields.add("conversation.class");
        fields.add(dependencies.conversation().getClass().getName());
        fields.add("artifact.class");
        fields.add(dependencies.artifact().service().getClass().getName());
        fields.add("approval.class");
        fields.add(dependencies.execution().approval().verification().getClass().getName());
        fields.add("credential.class");
        fields.add(dependencies.web().credential().broker().getClass().getName());
        fields.add("memory.class");
        fields.add(dependencies.memory().service().getClass().getName());
        return SdkConfigurationDigest.sha256(fields.toArray(String[]::new));
    }

    private static void appendProfileFields(List<String> fields, io.haifa.agent.sdk.product.ProductProfile profile) {
        fields.add("product.id");
        fields.add(profile.productId().value());
        fields.add("product.version");
        fields.add(profile.productVersion().value());
        fields.add("definition.id");
        fields.add(profile.definitionId().value());
        fields.add("definition.major");
        fields.add(String.valueOf(profile.definitionVersion().major()));
        fields.add("definition.minor");
        fields.add(String.valueOf(profile.definitionVersion().minor()));
        fields.add("definition.patch");
        fields.add(String.valueOf(profile.definitionVersion().patch()));
        fields.add("instructions");
        fields.add(profile.instructions());
        fields.add("defaultRunProfile.id");
        fields.add(profile.defaultRunProfile().id());
        fields.add("defaultRunProfile.version");
        fields.add(profile.defaultRunProfile().version());
        fields.add("budget.quotaMode");
        fields.add(profile.budget().quotaMode().name());
        fields.add("budget.maxInputTokens");
        fields.add(String.valueOf(profile.budget().maxInputTokens()));
        fields.add("budget.maxOutputTokens");
        fields.add(String.valueOf(profile.budget().maxOutputTokens()));
        fields.add("budget.maxCachedInputTokens");
        fields.add(String.valueOf(profile.budget().maxCachedInputTokens()));
        fields.add("budget.maxCostCurrency");
        fields.add(profile.budget().maxCostCurrency());
        fields.add("budget.maxCostMinorUnits");
        fields.add(String.valueOf(profile.budget().maxCostMinorUnits()));
        fields.add("limits.maxIterations");
        fields.add(String.valueOf(profile.limits().maxIterations()));
        fields.add("limits.maxDepth");
        fields.add(String.valueOf(profile.limits().maxDepth()));
        fields.add("limits.maxParallelChildren");
        fields.add(String.valueOf(profile.limits().maxParallelChildren()));
        fields.add("limits.maxWallTimeMillis");
        fields.add(String.valueOf(profile.limits().maxWallTimeMillis()));
        fields.add("limits.maxIdleTimeMillis");
        fields.add(String.valueOf(profile.limits().maxIdleTimeMillis()));
        fields.add("limits.maxModelCalls");
        fields.add(String.valueOf(profile.limits().maxModelCalls()));
        fields.add("limits.maxToolCalls");
        fields.add(String.valueOf(profile.limits().maxToolCalls()));
        fields.add("limits.maxChildRuns");
        fields.add(String.valueOf(profile.limits().maxChildRuns()));
        profile.allowedTools().stream().sorted().forEach(alias -> {
            fields.add("allowedTool");
            fields.add(alias);
        });
        profile.allowedSkills().stream().sorted().forEach(alias -> {
            fields.add("allowedSkill");
            fields.add(alias);
        });
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
                        new AgentRunLimits(16, 0, 1, 180_000, 120_000, 10, 16, 0),
                        structuredOptions,
                        Optional.of(Set.copyOf(plannerTools))),
                new ProductRunProfile(
                        SdkMissionRuntimeAccess.profileId(
                                SdkMissionRuntimeAccess.PLANNER_REPAIR_RUN_PROFILE, model.id(), defaultModelId),
                        "1.0.0",
                        model.id(),
                        AgentRunType.CHAT,
                        new AgentRunBudget(96_000, 16_000, 96_000, 0, 2, 0, "USD", 0),
                        new AgentRunLimits(4, 0, 1, 120_000, 120_000, 2, 0, 0),
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
                        new AgentRunLimits(
                                48, 0, 1, 600_000, 240_000, 24, SdkMissionRuntimeAccess.TASK_MAX_TOOL_CALLS, 0),
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
                        new AgentRunLimits(
                                40,
                                0,
                                1,
                                600_000,
                                240_000,
                                20,
                                MissionTaskRunInput.DEPENDENCY_AWARE_TOOL_CALL_HARD_LIMIT,
                                0),
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
                        new AgentRunLimits(4, 0, 1, 120_000, 120_000, 4, 0, 0),
                        structuredOptions,
                        Optional.of(Set.of())),
                new ProductRunProfile(
                        SdkMissionRuntimeAccess.profileId(
                                SdkMissionRuntimeAccess.SYNTHESIS_RUN_PROFILE, model.id(), defaultModelId),
                        "1.0.0",
                        model.id(),
                        AgentRunType.CHAT,
                        new AgentRunBudget(256_000, 32_000, 256_000, 0, 2, 0, "USD", 0),
                        new AgentRunLimits(4, 0, 1, 180_000, 180_000, 2, 0, 0),
                        structuredOptions,
                        Optional.of(Set.of())),
                new ProductRunProfile(
                        SdkMissionRuntimeAccess.profileId(
                                SdkMissionRuntimeAccess.RESEARCH_SYNTHESIS_RUN_PROFILE, model.id(), defaultModelId),
                        "1.0.0",
                        model.id(),
                        AgentRunType.CHAT,
                        new AgentRunBudget(256_000, 32_000, 256_000, 0, 2, 0, "USD", 0),
                        new AgentRunLimits(4, 0, 1, 180_000, 180_000, 2, 0, 0),
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
            /** Configured MCP server, or {@code null} when MCP is disabled. */
            PersonalMcpConfiguration mcp,
            Optional<Path> localSkillRoot,
            Optional<Path> trustedScriptManifest,
            List<Path> protectedPaths,
            Clock clock,
            ModelImageResolver imageResolver,
            ModelAudioResolver audioResolver) {
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
                    ModelImageResolver.unsupported(),
                    ModelAudioResolver.unsupported());
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
                    ModelImageResolver.unsupported(),
                    ModelAudioResolver.unsupported());
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
            localSkillRoot = Objects.requireNonNull(localSkillRoot);
            trustedScriptManifest = Objects.requireNonNull(trustedScriptManifest);
            protectedPaths = List.copyOf(protectedPaths);
            Objects.requireNonNull(clock);
            Objects.requireNonNull(imageResolver);
            Objects.requireNonNull(audioResolver);
        }

        private static PersonalModelCatalog defaultCatalog(ModelContribution model) {
            var snapshot = model.snapshot();
            boolean reasoning = snapshot.capabilities().contains(io.haifa.agent.model.api.ModelCapability.REASONING);
            var profile = io.haifa.agent.model.api.ModelBindingProfile.create(
                    snapshot.modelId(),
                    snapshot.apiStyle(),
                    "2.0",
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
                    new io.haifa.agent.model.api.ModelExecutionLimits(
                            snapshot.contextWindow(), 1, snapshot.maxOutputTokens()),
                    false,
                    new io.haifa.agent.model.api.ModelStreamingProfile(
                            snapshot.nativeStreaming(),
                            false,
                            false,
                            io.haifa.agent.model.api.ModelPartialOutputFailureBehavior.NON_RETRYABLE),
                    io.haifa.agent.model.api.ModelProfileStatus.VERIFIED,
                    java.time.LocalDate.of(2026, 8, 13));
            var defaults = new PersonalModelProductDefaults();
            var option = new PersonalModelOption(
                    snapshot.modelId().value(),
                    snapshot.providerId().value() + ":" + snapshot.providerModelId(),
                    snapshot.modelId().value(),
                    snapshot.modelId().value(),
                    snapshot.providerId().value(),
                    snapshot.providerId().value(),
                    snapshot.apiStyle().value(),
                    snapshot.apiStyle().value(),
                    "AVAILABLE",
                    "",
                    profile.capabilities().stream().map(Enum::name).collect(java.util.stream.Collectors.toSet()),
                    profile.contextWindowTokens(),
                    profile.executionLimits().maximumOutputTokens(),
                    PersonalModelProductDefaults.PREFERENCE_SCHEMA_VERSION,
                    profile.version(),
                    profile.digest(),
                    profile.status(),
                    profile.lastVerifiedOn(),
                    defaults.controls(
                            profile,
                            List.of(snapshot.modelId().value()),
                            snapshot.modelId().value()),
                    PersonalModelPreferences.recommended(),
                    profile.imageInput());
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
                    new ArtifactService(
                            new InMemoryArtifactStore(),
                            new InMemoryArtifactPayloadStore(),
                            new UuidV7IdentifierGenerator(),
                            clock::instant),
                    io.haifa.agent.personalassistant.application.product.PersonalAssistantProfile.ARTIFACT_POLICY);
        }
    }
}
