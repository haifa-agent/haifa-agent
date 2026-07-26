package io.haifa.agent.runtime.core;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.id.UuidV7IdentifierGenerator;
import io.haifa.agent.common.time.SystemTimeProvider;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.context.budget.HeuristicTokenEstimator;
import io.haifa.agent.context.compression.CompressionPolicy;
import io.haifa.agent.context.compression.DeterministicContextCompressor;
import io.haifa.agent.context.core.DefaultAgentContextBuilder;
import io.haifa.agent.context.selection.ContextSelectionPolicy;
import io.haifa.agent.context.source.ContextSource;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.core.run.AgentRunOutcome;
import io.haifa.agent.core.run.AgentRunResult;
import io.haifa.agent.core.run.AgentRunType;
import io.haifa.agent.credential.api.CredentialBroker;
import io.haifa.agent.memory.api.MemoryActor;
import io.haifa.agent.memory.api.MemoryAuditSink;
import io.haifa.agent.memory.api.MemoryRetriever;
import io.haifa.agent.memory.api.MemoryService;
import io.haifa.agent.memory.api.MemorySourceRef;
import io.haifa.agent.memory.api.MemorySourceType;
import io.haifa.agent.memory.core.DefaultMemoryPolicy;
import io.haifa.agent.memory.core.DefaultMemoryRetriever;
import io.haifa.agent.memory.core.InMemoryMemoryStore;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.policy.api.ApprovalMode;
import io.haifa.agent.policy.api.ApprovalVerification;
import io.haifa.agent.policy.api.ApprovalVerificationService;
import io.haifa.agent.policy.api.PolicyAuthorizationEvidenceStore;
import io.haifa.agent.policy.api.PolicyDecisionStore;
import io.haifa.agent.runtime.api.checkpoint.CapabilityCheckpointParticipant;
import io.haifa.agent.runtime.core.bootstrap.CallerContextProvider;
import io.haifa.agent.runtime.core.bootstrap.ConfigurationSnapshotFactory;
import io.haifa.agent.runtime.core.bootstrap.ContentAddressedSnapshotFactory;
import io.haifa.agent.runtime.core.bootstrap.DefinitionResolver;
import io.haifa.agent.runtime.core.bootstrap.ProfileResolver;
import io.haifa.agent.runtime.core.bootstrap.ResolvedDefinition;
import io.haifa.agent.runtime.core.bootstrap.ResolvedProfile;
import io.haifa.agent.runtime.core.bootstrap.RunAccessValidator;
import io.haifa.agent.runtime.core.bootstrap.RunBootstrapper;
import io.haifa.agent.runtime.core.bootstrap.RuntimeCallerContext;
import io.haifa.agent.runtime.core.checkpoint.CapabilityCheckpointRegistry;
import io.haifa.agent.runtime.core.checkpoint.CheckpointManager;
import io.haifa.agent.runtime.core.checkpoint.CheckpointPolicy;
import io.haifa.agent.runtime.core.checkpoint.CheckpointSnapshotBuilder;
import io.haifa.agent.runtime.core.checkpoint.MemoryCheckpointValidator;
import io.haifa.agent.runtime.core.checkpoint.ResumeCheckpointSelector;
import io.haifa.agent.runtime.core.checkpoint.ResumeCoordinator;
import io.haifa.agent.runtime.core.completion.CompletionPolicy;
import io.haifa.agent.runtime.core.completion.DefaultCompletionGuard;
import io.haifa.agent.runtime.core.completion.DefaultRunFinalizer;
import io.haifa.agent.runtime.core.completion.OutputContractValidator;
import io.haifa.agent.runtime.core.completion.RequiredArtifactChecker;
import io.haifa.agent.runtime.core.completion.TodoConvergenceChecker;
import io.haifa.agent.runtime.core.completion.TodoReconciliationService;
import io.haifa.agent.runtime.core.control.DefaultRunControlService;
import io.haifa.agent.runtime.core.control.RunControlRegistry;
import io.haifa.agent.runtime.core.control.RunControlService;
import io.haifa.agent.runtime.core.decision.DecisionExecutor;
import io.haifa.agent.runtime.core.decision.DefaultDecisionValidator;
import io.haifa.agent.runtime.core.delegation.DelegationPort;
import io.haifa.agent.runtime.core.event.NotifyingRuntimeEventAppender;
import io.haifa.agent.runtime.core.event.RuntimeClientEventProjector;
import io.haifa.agent.runtime.core.event.RuntimeEventFeed;
import io.haifa.agent.runtime.core.event.RuntimeEventSubscriptions;
import io.haifa.agent.runtime.core.event.RuntimeEventWakeupRegistry;
import io.haifa.agent.runtime.core.execution.AttemptExecutor;
import io.haifa.agent.runtime.core.execution.ExecutionOwnershipPort;
import io.haifa.agent.runtime.core.execution.ExecutionScheduler;
import io.haifa.agent.runtime.core.execution.LocalExecutionScheduler;
import io.haifa.agent.runtime.core.guard.BudgetGuard;
import io.haifa.agent.runtime.core.guard.ChildRunGuard;
import io.haifa.agent.runtime.core.guard.DuplicateToolCallGuard;
import io.haifa.agent.runtime.core.guard.IterationGuard;
import io.haifa.agent.runtime.core.guard.LoopDetectionGuard;
import io.haifa.agent.runtime.core.input.RunInputApplier;
import io.haifa.agent.runtime.core.input.RunInputPort;
import io.haifa.agent.runtime.core.interaction.InteractionPort;
import io.haifa.agent.runtime.core.interaction.ToolApprovalPromptFormatter;
import io.haifa.agent.runtime.core.lifecycle.RunAwaiter;
import io.haifa.agent.runtime.core.lifecycle.RunTransitionCoordinator;
import io.haifa.agent.runtime.core.loop.AgentLoop;
import io.haifa.agent.runtime.core.loop.DefaultAgentLoop;
import io.haifa.agent.runtime.core.loop.DefaultRuntimeContextBuilder;
import io.haifa.agent.runtime.core.loop.MemoryContextSource;
import io.haifa.agent.runtime.core.loop.RuntimeStateReconciler;
import io.haifa.agent.runtime.core.loop.SessionMessageSource;
import io.haifa.agent.runtime.core.middleware.AgentRuntimeMiddleware;
import io.haifa.agent.runtime.core.middleware.AgentRuntimeMiddlewareChain;
import io.haifa.agent.runtime.core.middleware.RunMetadataMiddleware;
import io.haifa.agent.runtime.core.middleware.SafetyInstructionMiddleware;
import io.haifa.agent.runtime.core.middleware.TodoMiddleware;
import io.haifa.agent.runtime.core.middleware.ToolDisclosureMiddleware;
import io.haifa.agent.runtime.core.middleware.TraceMiddleware;
import io.haifa.agent.runtime.core.model.FrozenModelInvoker;
import io.haifa.agent.runtime.core.model.ModelAdapterKey;
import io.haifa.agent.runtime.core.model.RuntimeModelOutputPublisher;
import io.haifa.agent.runtime.core.policy.RuntimePolicyAuthorizationEvidenceStore;
import io.haifa.agent.runtime.core.policy.RuntimePolicyDecisionStore;
import io.haifa.agent.runtime.core.retry.ModelRetryPolicy;
import io.haifa.agent.runtime.core.retry.PersistenceRetryPolicy;
import io.haifa.agent.runtime.core.retry.RepairRetryPolicy;
import io.haifa.agent.runtime.core.retry.RetryExecutor;
import io.haifa.agent.runtime.core.retry.RetryPolicy;
import io.haifa.agent.runtime.core.retry.Sleeper;
import io.haifa.agent.runtime.core.retry.ToolRetryPolicy;
import io.haifa.agent.runtime.core.storage.RuntimePersistencePorts;
import io.haifa.agent.runtime.core.tool.BoundedToolResultNormalizer;
import io.haifa.agent.runtime.core.tool.CapabilityAuthorizer;
import io.haifa.agent.runtime.core.tool.DefaultToolPolicy;
import io.haifa.agent.runtime.core.tool.DefaultToolPolicyRequestAdapter;
import io.haifa.agent.runtime.core.tool.LargeToolResultPolicy;
import io.haifa.agent.runtime.core.tool.LegacyToolPolicyAdapter;
import io.haifa.agent.runtime.core.tool.PublicToolPolicy;
import io.haifa.agent.runtime.core.tool.ToolExecutionEnvironment;
import io.haifa.agent.runtime.core.tool.ToolPipeline;
import io.haifa.agent.runtime.core.tool.ToolPolicy;
import io.haifa.agent.runtime.core.tool.ToolResultNormalizer;
import io.haifa.agent.runtime.core.trace.TracePort;
import io.haifa.agent.skill.api.SkillCatalog;
import io.haifa.agent.skill.api.SkillContentLoader;
import io.haifa.agent.tool.api.ToolCatalog;
import io.haifa.agent.tool.api.ToolInvoker;
import io.haifa.agent.tool.api.ToolSchemaValidationResult;
import io.haifa.agent.tool.api.ToolSchemaValidator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Convenience assembly for a local, dependency-free Runtime. */
public final class RuntimeCoreBuilder {
    private IdentifierGenerator ids = new UuidV7IdentifierGenerator();
    private TimeProvider time = new SystemTimeProvider();
    private ExecutionScheduler scheduler = new LocalExecutionScheduler();
    private RunControlRegistry controls = new RunControlRegistry();
    private RuntimePersistencePorts persistence = RuntimePersistencePorts.inMemory();
    private CallerContextProvider callers =
            () -> new RuntimeCallerContext(new TenantRef("local"), new PrincipalRef("local-user", "user"));
    private RunAccessValidator access = RunAccessValidator.allowLocalReferences();
    private DefinitionResolver definitions;
    private ProfileResolver profiles;
    private ConfigurationSnapshotFactory snapshots;
    private DelegationPort delegations = (parent, decision) -> new AgentRunResult(
            AgentRunOutcome.INSUFFICIENT_INFORMATION,
            "No delegation adapter configured",
            "delegation-result",
            "1.0",
            Map.of(),
            List.of(),
            List.of("delegation adapter unavailable"));
    private final Map<ModelAdapterKey, AgentChatModel> chatModels = new LinkedHashMap<>();
    private ToolCatalog toolCatalog = ToolCatalog.empty();
    private SkillCatalog skillCatalog = SkillCatalog.empty();
    private SkillContentLoader skillContentLoader = SkillContentLoader.empty();
    private ToolInvoker toolInvoker = request -> {
        throw new IllegalStateException("no tool invoker configured for "
                + request.binding().coordinate().externalForm());
    };
    private ToolSchemaValidator toolSchemaValidator = (schema, instance) -> new ToolSchemaValidationResult(List.of());
    private boolean toolPlatformConfigured;
    private ToolPolicy toolPolicy = new DefaultToolPolicy();
    private PublicToolPolicy publicToolPolicy;
    private PolicyDecisionStore policyDecisions = new RuntimePolicyDecisionStore();
    private PolicyAuthorizationEvidenceStore policyAuthorizationEvidence =
            new RuntimePolicyAuthorizationEvidenceStore();
    private ApprovalVerificationService approvalVerification = (request, responder) -> {
        boolean samePrincipal = request.requester().tenant().equals(responder.tenant())
                && request.requester().principal().equals(responder.principal());
        return new ApprovalVerification(
                samePrincipal, samePrincipal ? "LOCAL_PRINCIPAL_MATCH" : "LOCAL_PRINCIPAL_MISMATCH");
    };
    private ToolApprovalPromptFormatter toolApprovalPrompts = ToolApprovalPromptFormatter.defaultFormatter();
    private CredentialBroker credentialBroker;
    private ModelRetryPolicy modelRetry = ModelRetryPolicy.none();
    private ToolRetryPolicy toolRetry = ToolRetryPolicy.none();
    private PersistenceRetryPolicy persistenceRetry = PersistenceRetryPolicy.none();
    private RepairRetryPolicy repairRetry = new RepairRetryPolicy(3);
    private TracePort trace = TracePort.noop();
    private RunInputPort runInputs;
    private ToolResultNormalizer toolResultNormalizer = new BoundedToolResultNormalizer(4_000, 100);
    private OutputContractValidator outputContract =
            (run, decision) -> !decision.outputSchemaId().isBlank()
                    && !decision.outputSchemaVersion().isBlank();
    private RequiredArtifactChecker requiredArtifacts = (run, decision) -> true;
    private CompletionPolicy completionPolicy = (run, decision) -> true;
    private final List<AgentRuntimeMiddleware> additionalMiddleware = new ArrayList<>();
    private final List<ContextSource> additionalContextSources = new ArrayList<>();
    private final List<CapabilityCheckpointParticipant> capabilityCheckpointParticipants = new ArrayList<>();
    private String workerId = "local-runtime-" + ids.nextValue();
    private ExecutionOwnershipPort ownership;
    private MemoryRetriever memoryRetriever;
    private MemoryAuditSink memoryAudit;
    private MemoryService memoryService;

    public RuntimeCoreBuilder registerChatModel(String adapterType, String adapterVersion, AgentChatModel value) {
        ModelAdapterKey key = new ModelAdapterKey(adapterType, adapterVersion);
        if (chatModels.putIfAbsent(key, Objects.requireNonNull(value, "value must not be null")) != null) {
            throw new IllegalArgumentException(
                    "duplicate model adapter: " + key.adapterType() + "@" + key.adapterVersion());
        }
        return this;
    }

    public RuntimeCoreBuilder registerContextSource(ContextSource source) {
        Objects.requireNonNull(source, "source must not be null");
        if (additionalContextSources.stream().anyMatch(existing -> existing.id().equals(source.id()))) {
            throw new IllegalArgumentException("duplicate context source: " + source.id());
        }
        additionalContextSources.add(source);
        return this;
    }

    public RuntimeCoreBuilder registerCapabilityCheckpointParticipant(CapabilityCheckpointParticipant participant) {
        Objects.requireNonNull(participant, "participant must not be null");
        if (capabilityCheckpointParticipants.stream()
                .anyMatch(existing -> existing.id().equals(participant.id()))) {
            throw new IllegalArgumentException("duplicate capability checkpoint participant: "
                    + participant.id().value());
        }
        capabilityCheckpointParticipants.add(participant);
        return this;
    }

    public RuntimeCoreBuilder identifierGenerator(IdentifierGenerator value) {
        ids = value;
        return this;
    }

    public RuntimeCoreBuilder timeProvider(TimeProvider value) {
        time = value;
        return this;
    }

    public RuntimeCoreBuilder scheduler(ExecutionScheduler value) {
        scheduler = value;
        return this;
    }

    public RuntimeCoreBuilder controlRegistry(RunControlRegistry value) {
        controls = value;
        return this;
    }

    public RuntimeCoreBuilder persistence(RuntimePersistencePorts value) {
        persistence = Objects.requireNonNull(value, "persistence must not be null");
        return this;
    }

    /**
     * Configures the durable Run Input boundary.
     *
     * <p>The default comes from {@link RuntimePersistencePorts}; this method is an explicit
     * application override.
     */
    public RuntimeCoreBuilder runInputs(RunInputPort value) {
        runInputs = Objects.requireNonNull(value, "runInputs must not be null");
        return this;
    }

    public RuntimeCoreBuilder callers(CallerContextProvider value) {
        callers = value;
        return this;
    }

    public RuntimeCoreBuilder accessValidator(RunAccessValidator value) {
        access = value;
        return this;
    }

    public RuntimeCoreBuilder definitions(DefinitionResolver value) {
        definitions = value;
        return this;
    }

    public RuntimeCoreBuilder profiles(ProfileResolver value) {
        profiles = value;
        return this;
    }

    public RuntimeCoreBuilder snapshotFactory(ConfigurationSnapshotFactory value) {
        snapshots = value;
        return this;
    }

    public RuntimeCoreBuilder delegations(DelegationPort value) {
        delegations = value;
        return this;
    }

    public RuntimeCoreBuilder toolPlatform(
            ToolCatalog catalog, ToolInvoker invoker, ToolSchemaValidator schemaValidator) {
        toolCatalog = Objects.requireNonNull(catalog, "catalog");
        toolInvoker = Objects.requireNonNull(invoker, "invoker");
        toolSchemaValidator = Objects.requireNonNull(schemaValidator, "schemaValidator");
        toolPlatformConfigured = true;
        return this;
    }

    public RuntimeCoreBuilder skillPlatform(SkillCatalog catalog, SkillContentLoader contentLoader) {
        skillCatalog = Objects.requireNonNull(catalog, "catalog");
        skillContentLoader = Objects.requireNonNull(contentLoader, "contentLoader");
        return this;
    }

    public RuntimeCoreBuilder toolPolicy(ToolPolicy value) {
        toolPolicy = Objects.requireNonNull(value, "value");
        publicToolPolicy = null;
        return this;
    }

    public RuntimeCoreBuilder publicToolPolicy(PublicToolPolicy value) {
        publicToolPolicy = Objects.requireNonNull(value, "value");
        return this;
    }

    public RuntimeCoreBuilder policyStores(
            PolicyDecisionStore decisions, PolicyAuthorizationEvidenceStore authorizationEvidence) {
        policyDecisions = Objects.requireNonNull(decisions, "decisions");
        policyAuthorizationEvidence = Objects.requireNonNull(authorizationEvidence, "authorizationEvidence");
        return this;
    }

    public RuntimeCoreBuilder approvalVerification(ApprovalVerificationService value) {
        approvalVerification = Objects.requireNonNull(value, "value");
        return this;
    }

    public RuntimeCoreBuilder toolApprovalPrompts(ToolApprovalPromptFormatter value) {
        toolApprovalPrompts = Objects.requireNonNull(value, "value");
        return this;
    }

    public RuntimeCoreBuilder credentialBroker(CredentialBroker value) {
        credentialBroker = Objects.requireNonNull(value, "value");
        return this;
    }

    public RuntimeCoreBuilder modelRetry(RetryPolicy value) {
        modelRetry = new ModelRetryPolicy(value);
        return this;
    }

    public RuntimeCoreBuilder toolRetry(RetryPolicy value) {
        toolRetry = new ToolRetryPolicy(value);
        return this;
    }

    public RuntimeCoreBuilder persistenceRetry(RetryPolicy value) {
        persistenceRetry = new PersistenceRetryPolicy(value);
        return this;
    }

    public RuntimeCoreBuilder repairRetry(RepairRetryPolicy value) {
        repairRetry = Objects.requireNonNull(value);
        return this;
    }

    public RuntimeCoreBuilder trace(TracePort value) {
        trace = value;
        return this;
    }

    public RuntimeCoreBuilder toolResultNormalizer(ToolResultNormalizer value) {
        toolResultNormalizer = value;
        return this;
    }

    public RuntimeCoreBuilder outputContractValidator(OutputContractValidator value) {
        outputContract = Objects.requireNonNull(value);
        return this;
    }

    public RuntimeCoreBuilder requiredArtifactChecker(RequiredArtifactChecker value) {
        requiredArtifacts = Objects.requireNonNull(value);
        return this;
    }

    public RuntimeCoreBuilder completionPolicy(CompletionPolicy value) {
        completionPolicy = Objects.requireNonNull(value);
        return this;
    }

    public RuntimeCoreBuilder middleware(AgentRuntimeMiddleware value) {
        additionalMiddleware.add(Objects.requireNonNull(value));
        return this;
    }

    public RuntimeCoreBuilder workerId(String value) {
        workerId = Objects.requireNonNull(value, "workerId must not be null").trim();
        if (workerId.isEmpty()) throw new IllegalArgumentException("workerId must not be blank");
        return this;
    }

    public RuntimeCoreBuilder executionOwnership(ExecutionOwnershipPort value) {
        ownership = Objects.requireNonNull(value);
        return this;
    }

    public RuntimeCoreBuilder memory(MemoryRetriever retriever, MemoryAuditSink audit) {
        memoryRetriever = Objects.requireNonNull(retriever);
        memoryAudit = Objects.requireNonNull(audit);
        return this;
    }

    public RuntimeCoreBuilder memory(MemoryService service, MemoryRetriever retriever, MemoryAuditSink audit) {
        memoryService = Objects.requireNonNull(service);
        return memory(retriever, audit);
    }

    public DefaultAgentRuntime build() {
        if (chatModels.isEmpty()) throw new NullPointerException("a versioned Model API adapter must be configured");
        if (!toolCatalog.snapshot().bindings().isEmpty() && !toolPlatformConfigured) {
            throw new IllegalStateException("non-empty tool catalog requires an invoker and schema validator");
        }
        var runs = persistence.runs();
        var attempts = persistence.attempts();
        var checkpointsRepository = persistence.checkpoints();
        var state = persistence.state();
        RuntimeEventWakeupRegistry eventWakeups = new RuntimeEventWakeupRegistry();
        var events = new NotifyingRuntimeEventAppender(persistence.events(), persistence.unitOfWork(), eventWakeups);
        var outbox = persistence.outbox();
        var idempotency = persistence.idempotency();
        var unitOfWork = persistence.unitOfWork();
        var toolJournal = persistence.toolJournal();
        InteractionPort interactions = persistence.interactions();
        RunInputPort configuredRunInputs = runInputs != null ? runInputs : persistence.runInputs();
        var summaries = persistence.conversationSummaries();
        var toolResultAssets = persistence.toolResultAssets();
        var messageRedactions = persistence.messageRedactions();
        RuntimeEventFeed eventFeed = new RuntimeEventFeed(events, new RuntimeClientEventProjector(runs));
        RuntimeEventSubscriptions eventSubscriptions = new RuntimeEventSubscriptions(eventFeed, eventWakeups);
        ExecutionOwnershipPort configuredOwnership =
                ownership != null ? ownership : ExecutionOwnershipPort.local(workerId);
        RuntimeModelOutputPublisher modelOutput = new RuntimeModelOutputPublisher(events, time);
        FrozenModelInvoker models = new FrozenModelInvoker(state, chatModels, ids, modelOutput, controls);
        InMemoryMemoryStore defaultMemoryStore = new InMemoryMemoryStore();
        var defaultMemoryPolicy = new DefaultMemoryPolicy();
        MemoryRetriever configuredMemoryRetriever = memoryRetriever != null
                ? memoryRetriever
                : new DefaultMemoryRetriever(defaultMemoryStore, defaultMemoryPolicy);
        MemoryAuditSink configuredMemoryAudit = memoryAudit != null ? memoryAudit : defaultMemoryStore;
        if (memoryService != null) {
            messageRedactions.register(message -> message.runId()
                    .flatMap(runs::find)
                    .ifPresent(run -> memoryService.invalidateSource(
                            new MemorySourceRef(
                                    MemorySourceType.MESSAGE, message.id().value(), java.util.Optional.empty()),
                            "source message redacted",
                            new MemoryActor(run.tenant(), run.principal(), Set.of("memory:review")))));
        }
        Set<String> toolNames = toolCatalog.snapshot().bindings().stream()
                .map(binding -> binding.alias().value())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        DefinitionResolver definitionResolver = definitions != null
                ? definitions
                : (id, requested) -> new ResolvedDefinition(
                        id,
                        requested.orElse(new AgentDefinitionVersion(1, 0, 0)),
                        toolNames,
                        Set.of(),
                        "Complete the objective using disclosed capabilities.");
        ProfileResolver profileResolver = profiles != null ? profiles : RuntimeCoreBuilder::defaultProfile;
        RunAwaiter awaiter = new RunAwaiter();
        RunTransitionCoordinator transitions = new RunTransitionCoordinator(
                runs,
                state,
                events,
                outbox,
                ids,
                time,
                awaiter,
                unitOfWork,
                new RetryExecutor(Sleeper.threadSleep()),
                persistenceRetry);
        RunControlService controlService = new DefaultRunControlService(controls);
        CapabilityAuthorizer authorizer =
                (run, binding) -> toolNames.contains(binding.alias().value());
        PublicToolPolicy configuredToolPolicy = publicToolPolicy != null
                ? publicToolPolicy
                : new LegacyToolPolicyAdapter(
                        toolPolicy,
                        new DefaultToolPolicyRequestAdapter("runtime-compatibility", ApprovalMode.ASK),
                        ids,
                        time,
                        policyDecisions);
        ToolPipeline pipeline = new ToolPipeline(
                toolInvoker,
                toolSchemaValidator,
                authorizer,
                configuredToolPolicy,
                credentialBroker,
                toolJournal,
                state,
                ids,
                time,
                events,
                controls,
                ToolExecutionEnvironment.local(),
                toolResultNormalizer,
                new RetryExecutor(Sleeper.threadSleep()),
                toolRetry,
                trace,
                transitions,
                toolResultAssets,
                LargeToolResultPolicy.defaults());
        List<AgentRuntimeMiddleware> configuredMiddleware = new ArrayList<>(List.of(
                new RunMetadataMiddleware(),
                new SafetyInstructionMiddleware(),
                new TodoMiddleware(),
                new ToolDisclosureMiddleware(toolNames),
                new TraceMiddleware()));
        configuredMiddleware.addAll(additionalMiddleware);
        AgentRuntimeMiddlewareChain middleware = new AgentRuntimeMiddlewareChain(configuredMiddleware);
        TodoReconciliationService todoReconciliation =
                new TodoReconciliationService(state, new TodoConvergenceChecker());
        DefaultCompletionGuard completion = new DefaultCompletionGuard(
                state,
                pipeline,
                interactions,
                delegations,
                todoReconciliation,
                outputContract,
                requiredArtifacts,
                completionPolicy);
        ResumeCheckpointSelector checkpointSelections = new ResumeCheckpointSelector();
        CapabilityCheckpointRegistry capabilityCheckpointRegistry =
                new CapabilityCheckpointRegistry(capabilityCheckpointParticipants);
        CheckpointManager checkpoints = new CheckpointManager(
                checkpointsRepository,
                CheckpointPolicy.everyIteration(),
                new CheckpointSnapshotBuilder(ids, time, state, summaries, interactions, capabilityCheckpointRegistry),
                checkpointSelections,
                state,
                summaries,
                new MemoryCheckpointValidator(configuredMemoryRetriever, configuredMemoryAudit, time),
                capabilityCheckpointRegistry,
                time);
        DecisionExecutor decisionExecutor = new DecisionExecutor(
                pipeline,
                completion,
                new DefaultRunFinalizer(),
                interactions,
                delegations,
                state,
                transitions,
                ids,
                time,
                checkpoints,
                controls,
                repairRetry,
                toolApprovalPrompts,
                policyDecisions,
                unitOfWork,
                events,
                outbox);
        ResumeCoordinator resumeCoordinator = new ResumeCoordinator(
                interactions,
                checkpointsRepository,
                checkpointSelections,
                transitions,
                state,
                access,
                checkpoints,
                toolInvoker,
                skillContentLoader);
        var compressor = new DeterministicContextCompressor();
        var compressionPolicy = CompressionPolicy.defaults();
        var sessionMessageSource = new SessionMessageSource(state, summaries, compressor, compressionPolicy, ids, time);
        var memoryContextSource = new MemoryContextSource(configuredMemoryRetriever, state, time);
        RunInputApplier runInputApplier =
                new RunInputApplier(configuredRunInputs, state, events, outbox, unitOfWork, ids, time);
        AgentLoop loop = new DefaultAgentLoop(
                controls,
                List.of(new BudgetGuard(), new IterationGuard(), new LoopDetectionGuard(3)),
                new DefaultRuntimeContextBuilder(
                        state,
                        middleware,
                        new DefaultAgentContextBuilder(
                                new HeuristicTokenEstimator(), new ContextSelectionPolicy(), additionalContextSources),
                        sessionMessageSource,
                        memoryContextSource,
                        skillContentLoader),
                models,
                new DefaultDecisionValidator(new DuplicateToolCallGuard(state), new ChildRunGuard(state)),
                decisionExecutor,
                checkpoints,
                transitions,
                state,
                events,
                new RetryExecutor(Sleeper.threadSleep()),
                modelRetry,
                ids,
                time,
                trace,
                new RuntimeStateReconciler(state, attempts, interactions, pipeline, time, configuredOwnership),
                middleware,
                runInputApplier);
        AttemptExecutor attemptExecutor = new AttemptExecutor(
                attempts,
                loop,
                transitions,
                time,
                workerId,
                new RetryExecutor(Sleeper.threadSleep()),
                persistenceRetry,
                trace);
        ConfigurationSnapshotFactory configuredSnapshots = snapshots != null
                ? snapshots
                : new ContentAddressedSnapshotFactory(toolCatalog.snapshot(), skillCatalog.snapshot());
        RunBootstrapper bootstrapper =
                new RunBootstrapper(definitionResolver, profileResolver, access, configuredSnapshots, ids, time);
        return new DefaultAgentRuntime(
                callers,
                bootstrapper,
                runs,
                attempts,
                state,
                events,
                outbox,
                idempotency,
                unitOfWork,
                transitions,
                controlService,
                interactions,
                delegations,
                attemptExecutor,
                scheduler,
                ids,
                time,
                awaiter,
                resumeCoordinator,
                modelOutput,
                configuredOwnership,
                new RetryExecutor(Sleeper.threadSleep()),
                persistenceRetry,
                approvalVerification,
                policyAuthorizationEvidence,
                policyDecisions,
                configuredRunInputs,
                eventFeed,
                eventSubscriptions);
    }

    private static ResolvedProfile defaultProfile(String id, io.haifa.agent.runtime.api.RuntimeOverrides overrides) {
        long maxToolCalls = number(overrides, "maxToolCalls", 32);
        long maxModelCalls = number(overrides, "maxModelCalls", 64);
        int maxIterations = Math.toIntExact(number(overrides, "maxIterations", 50));
        long maxWallTime = number(overrides, "maxWallTimeMillis", 300_000);
        return new ResolvedProfile(
                id,
                "1.0.0",
                AgentRunType.CHAT,
                new AgentRunBudget(1_000_000, 1_000_000, 1_000_000, maxToolCalls, maxModelCalls, 8, "USD", 1_000_000),
                new AgentRunLimits(maxIterations, 4, 1, maxWallTime, 60_000));
    }

    private static long number(io.haifa.agent.runtime.api.RuntimeOverrides overrides, String key, long fallback) {
        Object value = overrides.values().get(key);
        if (value == null) return fallback;
        if (!(value instanceof Number number)) throw new IllegalArgumentException(key + " must be numeric");
        long result = number.longValue();
        if (result < 1) throw new IllegalArgumentException(key + " must be positive");
        return result;
    }
}
