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
import io.haifa.agent.runtime.core.execution.AttemptExecutor;
import io.haifa.agent.runtime.core.execution.ExecutionOwnershipPort;
import io.haifa.agent.runtime.core.execution.ExecutionScheduler;
import io.haifa.agent.runtime.core.execution.LocalExecutionScheduler;
import io.haifa.agent.runtime.core.guard.BudgetGuard;
import io.haifa.agent.runtime.core.guard.ChildRunGuard;
import io.haifa.agent.runtime.core.guard.DuplicateToolCallGuard;
import io.haifa.agent.runtime.core.guard.IterationGuard;
import io.haifa.agent.runtime.core.guard.LoopDetectionGuard;
import io.haifa.agent.runtime.core.interaction.InMemoryInteractionPort;
import io.haifa.agent.runtime.core.interaction.InteractionPort;
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
import io.haifa.agent.runtime.core.retry.ModelRetryPolicy;
import io.haifa.agent.runtime.core.retry.PersistenceRetryPolicy;
import io.haifa.agent.runtime.core.retry.RepairRetryPolicy;
import io.haifa.agent.runtime.core.retry.RetryExecutor;
import io.haifa.agent.runtime.core.retry.RetryPolicy;
import io.haifa.agent.runtime.core.retry.Sleeper;
import io.haifa.agent.runtime.core.retry.ToolRetryPolicy;
import io.haifa.agent.runtime.core.storage.InMemoryRuntimeStore;
import io.haifa.agent.runtime.core.tool.BoundedToolResultNormalizer;
import io.haifa.agent.runtime.core.tool.CapabilityAuthorizer;
import io.haifa.agent.runtime.core.tool.DefaultToolPolicy;
import io.haifa.agent.runtime.core.tool.InMemoryToolExecutionJournal;
import io.haifa.agent.runtime.core.tool.LargeToolResultPolicy;
import io.haifa.agent.runtime.core.tool.ToolExecutionEnvironment;
import io.haifa.agent.runtime.core.tool.ToolPipeline;
import io.haifa.agent.runtime.core.tool.ToolPolicy;
import io.haifa.agent.runtime.core.tool.ToolResultNormalizer;
import io.haifa.agent.runtime.core.trace.TracePort;
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
    private InMemoryRuntimeStore store = new InMemoryRuntimeStore();
    private CallerContextProvider callers =
            () -> new RuntimeCallerContext(new TenantRef("local"), new PrincipalRef("local-user", "user"));
    private RunAccessValidator access = RunAccessValidator.allowLocalReferences();
    private DefinitionResolver definitions;
    private ProfileResolver profiles;
    private ConfigurationSnapshotFactory snapshots;
    private InteractionPort interactions = new InMemoryInteractionPort();
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
    private ToolInvoker toolInvoker = request -> {
        throw new IllegalStateException("no tool invoker configured for "
                + request.binding().coordinate().externalForm());
    };
    private ToolSchemaValidator toolSchemaValidator = (schema, instance) -> new ToolSchemaValidationResult(List.of());
    private boolean toolPlatformConfigured;
    private ToolPolicy toolPolicy = new DefaultToolPolicy();
    private CredentialBroker credentialBroker;
    private ModelRetryPolicy modelRetry = ModelRetryPolicy.none();
    private ToolRetryPolicy toolRetry = ToolRetryPolicy.none();
    private RepairRetryPolicy repairRetry = new RepairRetryPolicy(3);
    private TracePort trace = TracePort.noop();
    private ToolResultNormalizer toolResultNormalizer = new BoundedToolResultNormalizer(4_000, 100);
    private OutputContractValidator outputContract =
            (run, decision) -> !decision.outputSchemaId().isBlank()
                    && !decision.outputSchemaVersion().isBlank();
    private RequiredArtifactChecker requiredArtifacts = (run, decision) -> true;
    private CompletionPolicy completionPolicy = (run, decision) -> true;
    private final List<AgentRuntimeMiddleware> additionalMiddleware = new ArrayList<>();
    private final List<ContextSource> additionalContextSources = new ArrayList<>();
    private final List<CapabilityCheckpointParticipant> capabilityCheckpointParticipants = new ArrayList<>();
    private String workerId = "local-runtime";
    private ExecutionOwnershipPort ownership = ExecutionOwnershipPort.local();
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

    public RuntimeCoreBuilder store(InMemoryRuntimeStore value) {
        store = value;
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

    public RuntimeCoreBuilder interactions(InteractionPort value) {
        interactions = value;
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

    public RuntimeCoreBuilder toolPolicy(ToolPolicy value) {
        toolPolicy = value;
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
        FrozenModelInvoker models = new FrozenModelInvoker(store, chatModels, ids);
        InMemoryMemoryStore defaultMemoryStore = new InMemoryMemoryStore();
        var defaultMemoryPolicy = new DefaultMemoryPolicy();
        MemoryRetriever configuredMemoryRetriever = memoryRetriever != null
                ? memoryRetriever
                : new DefaultMemoryRetriever(defaultMemoryStore, defaultMemoryPolicy);
        MemoryAuditSink configuredMemoryAudit = memoryAudit != null ? memoryAudit : defaultMemoryStore;
        if (memoryService != null) {
            store.addMessageRedactionListener(message -> message.runId()
                    .flatMap(store::find)
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
                store,
                store,
                store,
                store,
                ids,
                time,
                awaiter,
                store,
                new RetryExecutor(Sleeper.threadSleep()),
                PersistenceRetryPolicy.none());
        RunControlService controlService = new DefaultRunControlService(controls, transitions);
        CapabilityAuthorizer authorizer =
                (run, binding) -> toolNames.contains(binding.alias().value());
        ToolPipeline pipeline = new ToolPipeline(
                toolInvoker,
                toolSchemaValidator,
                authorizer,
                toolPolicy,
                credentialBroker,
                new InMemoryToolExecutionJournal(),
                store,
                ids,
                time,
                store,
                controls,
                ToolExecutionEnvironment.local(),
                toolResultNormalizer,
                new RetryExecutor(Sleeper.threadSleep()),
                toolRetry,
                trace,
                transitions,
                store,
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
                new TodoReconciliationService(store, new TodoConvergenceChecker());
        DefaultCompletionGuard completion = new DefaultCompletionGuard(
                store,
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
                store,
                CheckpointPolicy.everyIteration(),
                new CheckpointSnapshotBuilder(ids, time, store, store, interactions, capabilityCheckpointRegistry),
                checkpointSelections,
                store,
                store,
                new MemoryCheckpointValidator(configuredMemoryRetriever, configuredMemoryAudit, time),
                capabilityCheckpointRegistry,
                time);
        DecisionExecutor decisionExecutor = new DecisionExecutor(
                pipeline,
                completion,
                new DefaultRunFinalizer(),
                interactions,
                delegations,
                store,
                transitions,
                ids,
                time,
                checkpoints,
                controls,
                repairRetry);
        ResumeCoordinator resumeCoordinator = new ResumeCoordinator(
                interactions, store, checkpointSelections, transitions, store, access, checkpoints, toolInvoker);
        var compressor = new DeterministicContextCompressor();
        var compressionPolicy = CompressionPolicy.defaults();
        var sessionMessageSource = new SessionMessageSource(store, store, compressor, compressionPolicy, ids, time);
        var memoryContextSource = new MemoryContextSource(configuredMemoryRetriever, store, time);
        AgentLoop loop = new DefaultAgentLoop(
                controls,
                List.of(new BudgetGuard(), new IterationGuard(), new LoopDetectionGuard(3)),
                new DefaultRuntimeContextBuilder(
                        store,
                        middleware,
                        new DefaultAgentContextBuilder(
                                new HeuristicTokenEstimator(), new ContextSelectionPolicy(), additionalContextSources),
                        sessionMessageSource,
                        memoryContextSource),
                models,
                new DefaultDecisionValidator(new DuplicateToolCallGuard(store), new ChildRunGuard(store)),
                decisionExecutor,
                checkpoints,
                transitions,
                store,
                store,
                new RetryExecutor(Sleeper.threadSleep()),
                modelRetry,
                ids,
                time,
                trace,
                new RuntimeStateReconciler(store, store, interactions, pipeline, time, ownership),
                middleware);
        AttemptExecutor attemptExecutor = new AttemptExecutor(store, loop, transitions, time, workerId);
        ConfigurationSnapshotFactory configuredSnapshots =
                snapshots != null ? snapshots : new ContentAddressedSnapshotFactory(toolCatalog.snapshot());
        RunBootstrapper bootstrapper =
                new RunBootstrapper(definitionResolver, profileResolver, access, configuredSnapshots, ids, time);
        return new DefaultAgentRuntime(
                callers,
                bootstrapper,
                store,
                store,
                store,
                store,
                store,
                store,
                store,
                transitions,
                controlService,
                interactions,
                delegations,
                attemptExecutor,
                scheduler,
                ids,
                time,
                awaiter,
                resumeCoordinator);
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
