package io.haifa.agent.orchestration.langgraph4j;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimePrecision;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.orchestration.api.CompiledWorkflowDefinition;
import io.haifa.agent.orchestration.api.WorkflowCancelRequest;
import io.haifa.agent.orchestration.api.WorkflowCapability;
import io.haifa.agent.orchestration.api.WorkflowCheckpoint;
import io.haifa.agent.orchestration.api.WorkflowCheckpointId;
import io.haifa.agent.orchestration.api.WorkflowDefinitionRef;
import io.haifa.agent.orchestration.api.WorkflowEdge;
import io.haifa.agent.orchestration.api.WorkflowErrorCode;
import io.haifa.agent.orchestration.api.WorkflowEvent;
import io.haifa.agent.orchestration.api.WorkflowEventType;
import io.haifa.agent.orchestration.api.WorkflowException;
import io.haifa.agent.orchestration.api.WorkflowFailure;
import io.haifa.agent.orchestration.api.WorkflowNodeAttempt;
import io.haifa.agent.orchestration.api.WorkflowNodeAttemptStatus;
import io.haifa.agent.orchestration.api.WorkflowNodeDefinition;
import io.haifa.agent.orchestration.api.WorkflowNodeId;
import io.haifa.agent.orchestration.api.WorkflowNodeType;
import io.haifa.agent.orchestration.api.WorkflowParentLink;
import io.haifa.agent.orchestration.api.WorkflowResumeRequest;
import io.haifa.agent.orchestration.api.WorkflowRunId;
import io.haifa.agent.orchestration.api.WorkflowRunSnapshot;
import io.haifa.agent.orchestration.api.WorkflowRuntime;
import io.haifa.agent.orchestration.api.WorkflowStartRequest;
import io.haifa.agent.orchestration.api.WorkflowState;
import io.haifa.agent.orchestration.api.WorkflowStateDelta;
import io.haifa.agent.orchestration.api.WorkflowStatus;
import io.haifa.agent.orchestration.api.WorkflowSubgraphBinding;
import io.haifa.agent.orchestration.api.WorkflowSubgraphLink;
import io.haifa.agent.orchestration.api.WorkflowTimeoutRequest;
import io.haifa.agent.orchestration.api.WorkflowWait;
import io.haifa.agent.orchestration.api.WorkflowWaitId;
import io.haifa.agent.orchestration.core.DefaultWorkflowDefinitionCompiler;
import io.haifa.agent.orchestration.core.DeterministicStateMerger;
import io.haifa.agent.orchestration.core.DeterministicStateMerger.BranchDelta;
import io.haifa.agent.orchestration.core.spi.WorkflowActionGateway;
import io.haifa.agent.orchestration.core.spi.WorkflowAgentGateway;
import io.haifa.agent.orchestration.core.spi.WorkflowConditionEvaluator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.checkpoint.MemorySaver;

/**
 * Process-local M2 runtime backed by LangGraph4j Core 1.8.24.
 *
 * <p>Haifa definitions, state, attempts, events, and gateway contracts remain authoritative. The
 * Provider graph controls routing and physical fixed-branch execution; its State and MemorySaver
 * stay private implementation details and do not provide durable recovery.
 */
public final class LangGraph4jWorkflowRuntime implements WorkflowRuntime {
    private static final String PROVIDER_RUN_ID = "haifa.internal.run-id";
    private final Map<WorkflowDefinitionRef, ProviderDefinition> definitions = new HashMap<>();
    private final WorkflowActionGateway actions;
    private final WorkflowAgentGateway agents;
    private final WorkflowConditionEvaluator conditions;
    private final IdentifierGenerator identifiers;
    private final TimeProvider timeProvider;
    private final Executor parallelExecutor;
    private final DeterministicStateMerger stateMerger = new DeterministicStateMerger();
    private final Map<WorkflowRunId, MutableRun> runs = new LinkedHashMap<>();
    private final Map<String, StartRecord> starts = new HashMap<>();
    private final Map<String, CommandRecord> commandResults = new HashMap<>();
    private final Map<String, RunContext> contexts = new ConcurrentHashMap<>();

    public LangGraph4jWorkflowRuntime(
            List<CompiledWorkflowDefinition> definitions,
            WorkflowActionGateway actions,
            WorkflowAgentGateway agents,
            WorkflowConditionEvaluator conditions,
            IdentifierGenerator identifiers,
            TimeProvider timeProvider) {
        this(definitions, actions, agents, conditions, identifiers, timeProvider, ForkJoinPool.commonPool());
    }

    public LangGraph4jWorkflowRuntime(
            List<CompiledWorkflowDefinition> definitions,
            WorkflowActionGateway actions,
            WorkflowAgentGateway agents,
            WorkflowConditionEvaluator conditions,
            IdentifierGenerator identifiers,
            TimeProvider timeProvider,
            Executor parallelExecutor) {
        Objects.requireNonNull(definitions, "definitions must not be null");
        this.actions = Objects.requireNonNull(actions, "actions must not be null");
        this.agents = Objects.requireNonNull(agents, "agents must not be null");
        this.conditions = Objects.requireNonNull(conditions, "conditions must not be null");
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers must not be null");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider must not be null");
        this.parallelExecutor = Objects.requireNonNull(parallelExecutor, "parallelExecutor must not be null");
        Map<WorkflowDefinitionRef, CompiledWorkflowDefinition> compiledCatalog = new LinkedHashMap<>();
        definitions.forEach(definition -> registerCompiled(compiledCatalog, definition));
        compiledCatalog.values().forEach(definition -> {
            ProviderDefinition providerDefinition = translate(definition);
            if (this.definitions.put(definition.reference(), providerDefinition) != null) {
                throw new IllegalArgumentException("duplicate compiled workflow definition");
            }
        });
    }

    private static void registerCompiled(
            Map<WorkflowDefinitionRef, CompiledWorkflowDefinition> catalog, CompiledWorkflowDefinition definition) {
        CompiledWorkflowDefinition prior = catalog.putIfAbsent(definition.reference(), definition);
        if (prior != null && !prior.definition().equals(definition.definition())) {
            throw new IllegalArgumentException("duplicate compiled workflow definition");
        }
        definition
                .subgraphDefinitions()
                .forEach((reference, child) -> catalog.putIfAbsent(
                        reference,
                        new CompiledWorkflowDefinition(
                                reference, child, child.requiredCapabilities(), definition.subgraphDefinitions())));
    }

    @Override
    public synchronized WorkflowRunSnapshot start(WorkflowStartRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String fingerprint = request.definition() + "|" + request.initialState().values();
        StartRecord prior = starts.get(request.idempotencyKey());
        if (prior != null) {
            if (!prior.fingerprint().equals(fingerprint)) {
                throw error(
                        WorkflowErrorCode.IDEMPOTENCY_CONFLICT,
                        "start",
                        "idempotency key was used for a different start request");
            }
            return snapshot(requireRun(prior.runId()));
        }
        ProviderDefinition provider = definitions.get(request.definition());
        if (provider == null) {
            throw error(
                    WorkflowErrorCode.DEFINITION_NOT_FOUND, "start", "compiled workflow definition was not registered");
        }
        if (!provider.compiled()
                .definition()
                .stateSchema()
                .equals(request.initialState().schema())) {
            throw error(WorkflowErrorCode.INVALID_STATE, "start", "initial state schema does not match the definition");
        }
        Instant now = now();
        MutableRun run = new MutableRun(
                new WorkflowRunId(identifiers.nextValue()),
                provider,
                request.initialState(),
                provider.compiled().definition().entryNode(),
                now);
        RunContext context = new RunContext(run);
        runs.put(run.id, run);
        contexts.put(run.id.value(), context);
        starts.put(request.idempotencyKey(), new StartRecord(fingerprint, run.id));
        event(run, WorkflowEventType.RUN_STARTED, Optional.empty(), Map.of());
        invoke(run, context, GraphInput.args(Map.of(PROVIDER_RUN_ID, run.id.value())));
        return snapshot(run);
    }

    @Override
    public synchronized Optional<WorkflowRunSnapshot> find(WorkflowRunId runId) {
        MutableRun run = runs.get(Objects.requireNonNull(runId, "runId must not be null"));
        return run == null ? Optional.empty() : Optional.of(snapshot(run));
    }

    @Override
    public synchronized WorkflowRunSnapshot resume(WorkflowResumeRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String commandKey = "resume|" + request.runId().value() + '|' + request.idempotencyKey();
        String fingerprint = request.waitId().value()
                + '|'
                + request.expectedRevision()
                + '|'
                + request.signalId().value()
                + '|'
                + request.delta().values();
        CommandRecord prior = commandResults.get(commandKey);
        if (prior != null) {
            if (!prior.fingerprint().equals(fingerprint)) {
                throw error(
                        WorkflowErrorCode.IDEMPOTENCY_CONFLICT,
                        "resume",
                        "idempotency key was used for a different resume request");
            }
            return prior.result();
        }
        MutableRun run = requireRun(request.runId());
        WorkflowWait wait = run.wait.orElseThrow(
                () -> error(WorkflowErrorCode.INVALID_RESUME, "resume", "workflow run is not waiting"));
        if (!wait.id().equals(request.waitId())
                || run.revision != request.expectedRevision()
                || run.consumedSignals.containsKey(request.signalId().value())) {
            throw error(
                    WorkflowErrorCode.INVALID_RESUME,
                    "resume",
                    "wait, revision, or signal does not match the active interruption");
        }
        if (run.activeSubgraph.isPresent()) {
            resumeSubgraph(run, request);
            WorkflowRunSnapshot result = snapshot(run);
            commandResults.put(commandKey, new CommandRecord(fingerprint, result));
            return result;
        }
        run.consumedSignals.put(request.signalId().value(), request.idempotencyKey());
        RunContext context = requireContext(run.id);
        context.applyResume(request.delta());
        run.status = WorkflowStatus.RUNNING;
        run.wait = Optional.empty();
        run.checkpoint = Optional.empty();
        run.revision++;
        event(run, WorkflowEventType.RESUMED, Optional.of(wait.nodeId()), Map.of());
        invoke(run, context, GraphInput.resume());
        WorkflowRunSnapshot result = snapshot(run);
        commandResults.put(commandKey, new CommandRecord(fingerprint, result));
        return result;
    }

    @Override
    public synchronized WorkflowRunSnapshot cancel(WorkflowCancelRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String commandKey = "cancel|" + request.runId().value() + '|' + request.idempotencyKey();
        CommandRecord prior = commandResults.get(commandKey);
        if (prior != null) {
            return prior.result();
        }
        MutableRun run = requireRun(request.runId());
        if (!run.status.terminal()) {
            run.activeSubgraph.map(link -> runs.get(link.runId())).ifPresent(this::cancelTree);
            run.activeSubgraph = Optional.empty();
            terminateActiveAttempt(run, WorkflowErrorCode.TERMINAL_RUN);
            run.status = WorkflowStatus.CANCELLED;
            run.wait = Optional.empty();
            run.checkpoint = Optional.empty();
            run.revision++;
            event(run, WorkflowEventType.CANCELLED, Optional.of(run.currentNode), Map.of());
            releaseProviderState(run);
        }
        WorkflowRunSnapshot result = snapshot(run);
        commandResults.put(commandKey, new CommandRecord(request.runId().value(), result));
        return result;
    }

    @Override
    public synchronized WorkflowRunSnapshot timeout(WorkflowTimeoutRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String commandKey = "timeout|" + request.runId().value() + '|' + request.idempotencyKey();
        CommandRecord prior = commandResults.get(commandKey);
        if (prior != null) return prior.result();
        MutableRun run = requireRun(request.runId());
        timeoutTree(run);
        WorkflowRunSnapshot result = snapshot(run);
        commandResults.put(commandKey, new CommandRecord(request.runId().value(), result));
        return result;
    }

    @Override
    public synchronized List<WorkflowEvent> events(WorkflowRunId runId, long afterSequence, int limit) {
        if (afterSequence < 0 || limit < 1) {
            throw new IllegalArgumentException("event cursor must be non-negative and limit positive");
        }
        return requireRun(runId).events.stream()
                .filter(event -> event.sequence() > afterSequence)
                .limit(limit)
                .toList();
    }

    private ProviderDefinition translate(CompiledWorkflowDefinition compiled) {
        Objects.requireNonNull(compiled, "compiled definition must not be null");
        Set<WorkflowCapability> unsupported = new HashSet<>(compiled.capabilities());
        unsupported.removeAll(DefaultWorkflowDefinitionCompiler.SUPPORTED_CAPABILITIES);
        if (!unsupported.isEmpty()) {
            throw error(
                    WorkflowErrorCode.UNSUPPORTED_CAPABILITY,
                    "compile",
                    "unsupported workflow capabilities: " + unsupported);
        }
        BranchTopology topology = BranchTopology.from(compiled);
        topology.forks().stream()
                .flatMap(fork -> fork.branches().stream())
                .flatMap(branch -> branch.nodes().stream())
                .filter(node -> node.type() == WorkflowNodeType.SUBGRAPH)
                .forEach(node -> {
                    WorkflowSubgraphBinding binding = node.subgraphBinding().orElseThrow();
                    var child = compiled.subgraphDefinitions().get(binding.definition());
                    if (child == null
                            || child.nodes().stream()
                                    .anyMatch(candidate -> candidate.type() == WorkflowNodeType.WAIT)) {
                        throw error(
                                WorkflowErrorCode.UNSUPPORTED_CAPABILITY,
                                "compile",
                                "interrupting subgraph inside fixed parallel branch is not supported");
                    }
                });
        MemorySaver saver = new MemorySaver();
        try {
            StateGraph<ProviderWorkflowState> graph = new StateGraph<>(ProviderWorkflowState::new);
            Set<WorkflowNodeId> branchNodes = topology.branchNodeIds();
            for (WorkflowNodeDefinition node : compiled.definition().nodes()) {
                if (branchNodes.contains(node.id())) {
                    continue;
                }
                graph.addNode(node.id().value(), (state, config) -> completedFuture(executeProviderNode(node, config)));
            }
            for (ForkPlan fork : topology.forks()) {
                for (BranchPlan branch : fork.branches()) {
                    graph.addNode(
                            branch.providerNodeId(),
                            (state, config) ->
                                    completedFuture(requireContext(config).executeBranch(branch)));
                }
            }
            graph.addEdge(START, compiled.definition().entryNode().value());
            for (WorkflowNodeDefinition node : compiled.definition().nodes()) {
                if (branchNodes.contains(node.id())) {
                    continue;
                }
                if (node.type() == WorkflowNodeType.TERMINAL) {
                    graph.addEdge(node.id().value(), END);
                    continue;
                }
                if (node.type() == WorkflowNodeType.FORK_ALL) {
                    ForkPlan fork = topology.fork(node.id());
                    for (BranchPlan branch : fork.branches()) {
                        graph.addEdge(node.id().value(), branch.providerNodeId());
                        graph.addEdge(branch.providerNodeId(), fork.joinNode().value());
                    }
                    continue;
                }
                List<WorkflowEdge> outgoing = outgoing(compiled, node.id());
                if (outgoing.size() == 1 && outgoing.getFirst().conditionId().isEmpty()) {
                    graph.addEdge(
                            node.id().value(), outgoing.getFirst().target().value());
                } else {
                    Map<String, String> mappings = new LinkedHashMap<>();
                    outgoing.stream()
                            .sorted(Comparator.comparingInt(WorkflowEdge::branchOrdinal)
                                    .thenComparing(WorkflowEdge::target))
                            .forEach(edge -> {
                                String condition = edge.conditionId().orElseThrow();
                                if (mappings.put(condition, edge.target().value()) != null) {
                                    throw error(
                                            WorkflowErrorCode.INVALID_DEFINITION,
                                            "compile",
                                            "conditional edge identifiers must be unique per source node");
                                }
                            });
                    AsyncEdgeAction<ProviderWorkflowState> route =
                            state -> completedFuture(requireContext(state).selectCondition(node.id(), outgoing));
                    graph.addConditionalEdges(node.id().value(), route, mappings);
                }
            }
            int recursionLimit = recursionLimit(compiled);
            Set<String> waits = compiled.definition().nodes().stream()
                    .filter(node -> node.type() == WorkflowNodeType.WAIT)
                    .map(node -> node.id().value())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            Set<String> subgraphs = compiled.definition().nodes().stream()
                    .filter(node -> node.type() == WorkflowNodeType.SUBGRAPH)
                    .filter(node -> !topology.branchNodeIds().contains(node.id()))
                    .map(node -> node.id().value())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            CompiledGraph<ProviderWorkflowState> graphInstance = graph.compile(CompileConfig.builder()
                    .checkpointSaver(saver)
                    .interruptsBefore(waits)
                    .interruptsAfter(subgraphs)
                    .recursionLimit(recursionLimit)
                    .releaseThread(false)
                    .build());
            return new ProviderDefinition(compiled, graphInstance, saver, topology);
        } catch (WorkflowException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new WorkflowException(
                    WorkflowErrorCode.INVALID_DEFINITION,
                    "compile",
                    "LangGraph4j rejected the Haifa workflow definition",
                    exception);
        }
    }

    private Map<String, Object> executeProviderNode(WorkflowNodeDefinition node, RunnableConfig config) {
        RunContext context = requireContext(config);
        switch (node.type()) {
            case ACTION, AGENT_RUN -> context.executeNode(node, Optional.empty());
            case SUBGRAPH -> context.executeSubgraph(node);
            case FORK_ALL -> context.beginFork(node.id());
            case JOIN_ALL -> context.completeJoin(node.id());
            case WAIT, TERMINAL -> context.touch(node.id());
        }
        return Map.of("haifa.node." + node.id().value(), context.marker());
    }

    private void invoke(MutableRun run, RunContext context, GraphInput input) {
        RunnableConfig config = runnableConfig(run);
        try {
            NodeOutput<ProviderWorkflowState> output = run.provider
                    .graph()
                    .invokeFinal(input, config)
                    .orElseThrow(
                            () -> new IllegalStateException("LangGraph4j produced no terminal or interruption output"));
            context.flushBranches();
            run.state = context.state();
            if (output.isEND()) {
                run.status = WorkflowStatus.COMPLETED;
                run.revision++;
                event(run, WorkflowEventType.COMPLETED, Optional.of(run.currentNode), Map.of());
                releaseProviderState(run);
            } else {
                WorkflowNodeDefinition current = node(run.provider.compiled(), run.currentNode);
                if (current.type() == WorkflowNodeType.SUBGRAPH) {
                    MutableRun child = run.activeSubgraph
                            .map(link -> runs.get(link.runId()))
                            .orElse(null);
                    if (child == null || child.status == WorkflowStatus.COMPLETED) {
                        invoke(run, context, GraphInput.resume());
                        return;
                    }
                    if (child.status == WorkflowStatus.WAITING) {
                        interruptSubgraph(run, current, child);
                        return;
                    }
                    throw error(
                            WorkflowErrorCode.NODE_EXECUTION_FAILED,
                            "subgraph",
                            "child workflow did not reach a resumable boundary");
                }
                String nextNode = run.provider
                        .saver()
                        .get(config)
                        .map(checkpoint -> checkpoint.getNextNodeId())
                        .orElseThrow(() -> new IllegalStateException("provider interruption has no checkpoint"));
                WorkflowNodeDefinition waitNode = node(run.provider.compiled(), new WorkflowNodeId(nextNode));
                if (waitNode.type() != WorkflowNodeType.WAIT) {
                    throw new IllegalStateException("provider interrupted at a non-WAIT node");
                }
                interrupt(run, waitNode);
            }
        } catch (RuntimeException exception) {
            context.flushBranches();
            run.state = context.state();
            WorkflowException normalized = normalize(exception);
            fail(run, normalized.code(), normalized.operation(), context.failedNode());
            releaseProviderState(run);
        }
    }

    private void interruptSubgraph(MutableRun parent, WorkflowNodeDefinition node, MutableRun child) {
        WorkflowWait childWait = child.wait.orElseThrow();
        Instant at = now();
        parent.status = WorkflowStatus.WAITING;
        parent.revision++;
        parent.wait = Optional.of(
                new WorkflowWait(new WorkflowWaitId(identifiers.nextValue()), node.id(), parent.revision, at));
        parent.checkpoint = Optional.of(new WorkflowCheckpoint(
                new WorkflowCheckpointId(identifiers.nextValue()),
                parent.id,
                parent.revision,
                node.id(),
                parent.state,
                at));
        event(
                parent,
                WorkflowEventType.WAITING,
                Optional.of(node.id()),
                Map.of(
                        "childRunId",
                        child.id.value(),
                        "childNodeId",
                        childWait.nodeId().value()));
    }

    private RunnableConfig runnableConfig(MutableRun run) {
        RunnableConfig.Builder builder = RunnableConfig.builder().threadId(run.id.value());
        for (ForkPlan fork : run.provider.topology().forks()) {
            builder.addParallelNodeExecutor(fork.forkNode().value(), parallelExecutor);
        }
        return builder.build();
    }

    private void resumeSubgraph(MutableRun parent, WorkflowResumeRequest request) {
        WorkflowSubgraphLink link = parent.activeSubgraph.orElseThrow();
        MutableRun child = requireRun(link.runId());
        WorkflowWait childWait = child.wait.orElseThrow(
                () -> error(WorkflowErrorCode.INVALID_RESUME, "resume-subgraph", "child workflow is not waiting"));
        child.consumedSignals.put(request.signalId().value(), request.idempotencyKey());
        RunContext childContext = requireContext(child.id);
        childContext.applyResume(request.delta());
        child.status = WorkflowStatus.RUNNING;
        child.wait = Optional.empty();
        child.checkpoint = Optional.empty();
        child.revision++;
        event(child, WorkflowEventType.RESUMED, Optional.of(childWait.nodeId()), Map.of());
        invoke(child, childContext, GraphInput.resume());

        parent.consumedSignals.put(request.signalId().value(), request.idempotencyKey());
        parent.status = WorkflowStatus.RUNNING;
        parent.wait = Optional.empty();
        parent.checkpoint = Optional.empty();
        parent.revision++;
        event(
                parent,
                WorkflowEventType.RESUMED,
                Optional.of(link.parentNodeId()),
                Map.of("childRunId", child.id.value()));
        if (child.status == WorkflowStatus.WAITING) {
            interruptSubgraph(parent, node(parent.provider.compiled(), link.parentNodeId()), child);
            return;
        }
        if (child.status != WorkflowStatus.COMPLETED) {
            WorkflowErrorCode code =
                    child.failure.map(WorkflowFailure::code).orElse(WorkflowErrorCode.NODE_EXECUTION_FAILED);
            parent.activeSubgraph = Optional.empty();
            fail(parent, code, "subgraph", Optional.of(link.parentNodeId()));
            return;
        }
        RunContext parentContext = requireContext(parent.id);
        parentContext.completeSubgraph(node(parent.provider.compiled(), link.parentNodeId()), child);
        invoke(parent, parentContext, GraphInput.resume());
    }

    private void cancelTree(MutableRun run) {
        run.activeSubgraph.map(link -> runs.get(link.runId())).ifPresent(this::cancelTree);
        run.activeSubgraph = Optional.empty();
        if (!run.status.terminal()) {
            terminateActiveAttempt(run, WorkflowErrorCode.TERMINAL_RUN);
            run.status = WorkflowStatus.CANCELLED;
            run.wait = Optional.empty();
            run.checkpoint = Optional.empty();
            run.revision++;
            event(run, WorkflowEventType.CANCELLED, Optional.of(run.currentNode), Map.of());
            releaseProviderState(run);
        }
    }

    private void timeoutTree(MutableRun run) {
        run.activeSubgraph.map(link -> runs.get(link.runId())).ifPresent(this::timeoutTree);
        run.activeSubgraph = Optional.empty();
        if (!run.status.terminal()) {
            terminateActiveAttempt(run, WorkflowErrorCode.TIMED_OUT);
            run.status = WorkflowStatus.TIMED_OUT;
            run.wait = Optional.empty();
            run.checkpoint = Optional.empty();
            run.revision++;
            event(run, WorkflowEventType.TIMED_OUT, Optional.of(run.currentNode), Map.of());
            releaseProviderState(run);
        }
    }

    private void terminateActiveAttempt(MutableRun run, WorkflowErrorCode code) {
        if (run.attempts.isEmpty()) return;
        WorkflowNodeAttempt active = run.attempts.getLast();
        if (active.status() != WorkflowNodeAttemptStatus.RUNNING) return;
        run.attempts.set(
                run.attempts.size() - 1,
                new WorkflowNodeAttempt(
                        active.nodeId(),
                        active.attempt(),
                        WorkflowNodeAttemptStatus.FAILED,
                        active.agentRunId(),
                        Optional.of(code),
                        active.startedAt(),
                        Optional.of(now())));
    }

    private static WorkflowState mapInputs(
            WorkflowState parent, ProviderDefinition child, WorkflowSubgraphBinding binding) {
        Map<String, Object> values = new LinkedHashMap<>();
        binding.stateMapping().inputs().forEach((parentKey, childKey) -> {
            if (parent.values().containsKey(parentKey))
                values.put(childKey, parent.values().get(parentKey));
        });
        return new WorkflowState(child.compiled().definition().stateSchema(), values);
    }

    private static WorkflowStateDelta mapOutputs(WorkflowState child, WorkflowSubgraphBinding binding) {
        Map<String, Object> values = new LinkedHashMap<>();
        binding.stateMapping().outputs().forEach((childKey, parentKey) -> {
            if (child.values().containsKey(childKey))
                values.put(parentKey, child.values().get(childKey));
        });
        return new WorkflowStateDelta(values);
    }

    private void interrupt(MutableRun run, WorkflowNodeDefinition node) {
        Instant now = now();
        run.currentNode = node.id();
        run.status = WorkflowStatus.WAITING;
        run.revision++;
        WorkflowWait wait = new WorkflowWait(new WorkflowWaitId(identifiers.nextValue()), node.id(), run.revision, now);
        run.wait = Optional.of(wait);
        run.checkpoint = Optional.of(new WorkflowCheckpoint(
                new WorkflowCheckpointId(identifiers.nextValue()), run.id, run.revision, node.id(), run.state, now));
        event(run, WorkflowEventType.WAITING, Optional.of(node.id()), Map.of());
    }

    private void fail(MutableRun run, WorkflowErrorCode code, String operation, Optional<WorkflowNodeId> failedNode) {
        failedNode.ifPresent(node -> run.currentNode = node);
        run.status = WorkflowStatus.FAILED;
        run.failure = Optional.of(new WorkflowFailure(code, operation, Optional.of(run.currentNode)));
        run.revision++;
        event(run, WorkflowEventType.FAILED, Optional.of(run.currentNode), Map.of("code", code.name()));
    }

    private WorkflowException normalize(RuntimeException exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof WorkflowException workflowException) {
                return workflowException;
            }
            current = current.getCause();
        }
        if (containsMessage(exception, "Maximum number of iterations")) {
            return new WorkflowException(
                    WorkflowErrorCode.ITERATION_LIMIT_EXCEEDED,
                    "execute",
                    "provider recursion guard was reached",
                    exception);
        }
        return new WorkflowException(
                WorkflowErrorCode.NODE_EXECUTION_FAILED,
                "provider-execute",
                "LangGraph4j node execution failed",
                exception);
    }

    private static boolean containsMessage(Throwable failure, String value) {
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains(value)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void releaseProviderState(MutableRun run) {
        try {
            run.provider.saver().release(runnableConfig(run));
        } catch (Exception ignored) {
            // Provider checkpoints are non-authoritative process-local continuation details.
        }
    }

    private void event(
            MutableRun run, WorkflowEventType type, Optional<WorkflowNodeId> nodeId, Map<String, String> attributes) {
        Instant occurredAt = now();
        run.updatedAt = occurredAt;
        run.events.add(new WorkflowEvent(run.id, ++run.eventSequence, type, nodeId, attributes, occurredAt));
    }

    private WorkflowRunSnapshot snapshot(MutableRun run) {
        return new WorkflowRunSnapshot(
                run.id,
                run.provider.compiled().reference(),
                run.status,
                run.revision,
                run.state,
                Optional.of(run.currentNode),
                run.wait,
                run.checkpoint,
                run.failure,
                run.attempts,
                run.parent,
                run.activeSubgraph,
                run.createdAt,
                run.updatedAt);
    }

    private MutableRun requireRun(WorkflowRunId runId) {
        MutableRun run = runs.get(Objects.requireNonNull(runId, "runId must not be null"));
        if (run == null) {
            throw error(WorkflowErrorCode.RUN_NOT_FOUND, "find", "workflow run does not exist");
        }
        return run;
    }

    private RunContext requireContext(RunnableConfig config) {
        String threadId =
                config.threadId().orElseThrow(() -> new IllegalStateException("provider thread id is required"));
        RunContext context = contexts.get(threadId);
        if (context == null) {
            throw new IllegalStateException("provider run context does not exist");
        }
        return context;
    }

    private RunContext requireContext(ProviderWorkflowState state) {
        String runId = state.<String>value(PROVIDER_RUN_ID)
                .orElseThrow(() -> new IllegalStateException("provider state has no Haifa run identity"));
        RunContext context = contexts.get(runId);
        if (context == null) {
            throw new IllegalStateException("provider run context does not exist");
        }
        return context;
    }

    private RunContext requireContext(WorkflowRunId runId) {
        RunContext context = contexts.get(runId.value());
        if (context == null) {
            throw error(WorkflowErrorCode.RUN_NOT_FOUND, "find", "workflow run context does not exist");
        }
        return context;
    }

    private Instant now() {
        return TimePrecision.toMilliseconds(timeProvider.now());
    }

    private static int recursionLimit(CompiledWorkflowDefinition compiled) {
        long value = (long) compiled.definition().limits().maximumIterationsPerNode()
                        * compiled.definition().limits().maximumNodes()
                + compiled.definition().limits().maximumNodes()
                + 2L;
        return (int) Math.min(Integer.MAX_VALUE, value);
    }

    private static List<WorkflowEdge> outgoing(CompiledWorkflowDefinition compiled, WorkflowNodeId nodeId) {
        return compiled.definition().edges().stream()
                .filter(edge -> edge.source().equals(nodeId))
                .toList();
    }

    private static WorkflowNodeDefinition node(CompiledWorkflowDefinition compiled, WorkflowNodeId nodeId) {
        return compiled.definition().nodes().stream()
                .filter(node -> node.id().equals(nodeId))
                .findFirst()
                .orElseThrow(
                        () -> error(WorkflowErrorCode.INVALID_DEFINITION, "compile", "workflow node does not exist"));
    }

    private static WorkflowStateDelta difference(WorkflowState base, WorkflowState updated) {
        Map<String, Object> changed = new LinkedHashMap<>();
        updated.values().forEach((key, value) -> {
            if (!Objects.equals(base.values().get(key), value)) {
                changed.put(key, value);
            }
        });
        return new WorkflowStateDelta(changed);
    }

    private static WorkflowException error(WorkflowErrorCode code, String operation, String message) {
        return new WorkflowException(code, operation, message);
    }

    private final class RunContext {
        private final MutableRun run;
        private final Map<WorkflowNodeId, Integer> visits = new HashMap<>();
        private final Map<WorkflowNodeId, ForkExecution> forkExecutions = new HashMap<>();
        private Optional<WorkflowNodeId> failedNode = Optional.empty();
        private long marker;

        private RunContext(MutableRun run) {
            this.run = run;
        }

        private synchronized long marker() {
            return ++marker;
        }

        private synchronized WorkflowState state() {
            return run.state;
        }

        private synchronized Optional<WorkflowNodeId> failedNode() {
            return failedNode;
        }

        private synchronized void touch(WorkflowNodeId nodeId) {
            run.currentNode = nodeId;
        }

        private synchronized void applyResume(WorkflowStateDelta delta) {
            run.state = run.state.apply(delta);
        }

        private void executeNode(WorkflowNodeDefinition node, Optional<BranchExecution> branch) {
            int attempt = visit(node.id());
            WorkflowState input = branch.map(BranchExecution::state).orElseGet(this::state);
            Instant started = now();
            if (branch.isEmpty()) {
                synchronized (this) {
                    run.currentNode = node.id();
                    event(run, WorkflowEventType.NODE_STARTED, Optional.of(node.id()), Map.of());
                }
            }
            AgentRunId agentRunId = null;
            try {
                WorkflowStateDelta delta;
                if (node.type() == WorkflowNodeType.AGENT_RUN) {
                    WorkflowAgentGateway.AgentExecution execution = agents.execute(run.id, node, input);
                    agentRunId = execution.agentRunId();
                    delta = execution.delta();
                } else {
                    delta = actions.execute(run.id, node, input);
                }
                WorkflowState updated = input.apply(delta);
                WorkflowNodeAttempt completed = new WorkflowNodeAttempt(
                        node.id(),
                        attempt,
                        WorkflowNodeAttemptStatus.COMPLETED,
                        Optional.ofNullable(agentRunId),
                        Optional.empty(),
                        started,
                        Optional.of(now()));
                if (branch.isPresent()) {
                    branch.orElseThrow().complete(updated, completed);
                } else {
                    synchronized (this) {
                        run.state = updated;
                        run.attempts.add(completed);
                        run.revision++;
                        event(run, WorkflowEventType.NODE_COMPLETED, Optional.of(node.id()), Map.of());
                    }
                }
            } catch (RuntimeException exception) {
                WorkflowErrorCode code = exception instanceof WorkflowException workflowException
                        ? workflowException.code()
                        : WorkflowErrorCode.NODE_EXECUTION_FAILED;
                WorkflowNodeAttemptStatus status = code == WorkflowErrorCode.OUTCOME_UNKNOWN
                        ? WorkflowNodeAttemptStatus.OUTCOME_UNKNOWN
                        : WorkflowNodeAttemptStatus.FAILED;
                WorkflowNodeAttempt failed = new WorkflowNodeAttempt(
                        node.id(),
                        attempt,
                        status,
                        Optional.ofNullable(agentRunId),
                        Optional.of(code),
                        started,
                        Optional.of(now()));
                if (branch.isPresent()) {
                    branch.orElseThrow().fail(failed);
                } else {
                    synchronized (this) {
                        run.attempts.add(failed);
                        failedNode = Optional.of(node.id());
                    }
                }
                throw exception;
            }
        }

        private void executeSubgraph(WorkflowNodeDefinition node) {
            int attempt = visit(node.id());
            Instant started = now();
            WorkflowSubgraphBinding binding = node.subgraphBinding().orElseThrow();
            ProviderDefinition childProvider = definitions.get(binding.definition());
            if (childProvider == null) {
                throw error(WorkflowErrorCode.DEFINITION_NOT_FOUND, "subgraph", "child definition is unavailable");
            }
            WorkflowRunId childId = new WorkflowRunId(identifiers.nextValue());
            MutableRun child = new MutableRun(
                    childId,
                    childProvider,
                    mapInputs(run.state, childProvider, binding),
                    childProvider.compiled().definition().entryNode(),
                    now());
            child.parent = Optional.of(new WorkflowParentLink(run.id, node.id(), attempt));
            synchronized (this) {
                run.currentNode = node.id();
                run.attempts.add(new WorkflowNodeAttempt(
                        node.id(),
                        attempt,
                        WorkflowNodeAttemptStatus.RUNNING,
                        Optional.empty(),
                        Optional.empty(),
                        started,
                        Optional.empty()));
                run.activeSubgraph =
                        Optional.of(new WorkflowSubgraphLink(childId, binding.definition(), node.id(), attempt));
                event(
                        run,
                        WorkflowEventType.SUBGRAPH_STARTED,
                        Optional.of(node.id()),
                        Map.of(
                                "childRunId",
                                childId.value(),
                                "definitionDigest",
                                binding.definition().digest().value()));
            }
            RunContext childContext = new RunContext(child);
            runs.put(childId, child);
            contexts.put(childId.value(), childContext);
            event(child, WorkflowEventType.RUN_STARTED, Optional.empty(), Map.of("parentRunId", run.id.value()));
            invoke(child, childContext, GraphInput.args(Map.of(PROVIDER_RUN_ID, childId.value())));
            if (child.status == WorkflowStatus.COMPLETED) {
                completeSubgraph(node, child);
            } else if (child.status != WorkflowStatus.WAITING) {
                WorkflowErrorCode code =
                        child.failure.map(WorkflowFailure::code).orElse(WorkflowErrorCode.NODE_EXECUTION_FAILED);
                WorkflowNodeAttempt active = run.attempts.getLast();
                run.attempts.set(
                        run.attempts.size() - 1,
                        new WorkflowNodeAttempt(
                                active.nodeId(),
                                active.attempt(),
                                WorkflowNodeAttemptStatus.FAILED,
                                Optional.empty(),
                                Optional.of(code),
                                active.startedAt(),
                                Optional.of(now())));
                run.activeSubgraph = Optional.empty();
                failedNode = Optional.of(node.id());
                throw error(code, "subgraph", "child workflow failed");
            }
        }

        private synchronized void completeSubgraph(WorkflowNodeDefinition node, MutableRun child) {
            WorkflowNodeAttempt active = run.attempts.getLast();
            if (active.status() != WorkflowNodeAttemptStatus.RUNNING
                    || !active.nodeId().equals(node.id())) {
                throw error(WorkflowErrorCode.INVALID_STATE, "subgraph", "parent subgraph attempt is not active");
            }
            run.state = run.state.apply(
                    mapOutputs(child.state, node.subgraphBinding().orElseThrow()));
            run.attempts.set(
                    run.attempts.size() - 1,
                    new WorkflowNodeAttempt(
                            active.nodeId(),
                            active.attempt(),
                            WorkflowNodeAttemptStatus.COMPLETED,
                            Optional.empty(),
                            Optional.empty(),
                            active.startedAt(),
                            Optional.of(now())));
            run.activeSubgraph = Optional.empty();
            run.revision++;
            event(
                    run,
                    WorkflowEventType.SUBGRAPH_COMPLETED,
                    Optional.of(node.id()),
                    Map.of("childRunId", child.id.value()));
        }

        private int visit(WorkflowNodeId nodeId) {
            synchronized (this) {
                int visit = visits.merge(nodeId, 1, Integer::sum);
                if (visit > run.provider.compiled().definition().limits().maximumIterationsPerNode()) {
                    failedNode = Optional.of(nodeId);
                    throw error(
                            WorkflowErrorCode.ITERATION_LIMIT_EXCEEDED,
                            "execute",
                            "maximum node iteration count exceeded");
                }
                return visit;
            }
        }

        private synchronized void beginFork(WorkflowNodeId forkNode) {
            run.currentNode = forkNode;
            ForkPlan plan = run.provider.topology().fork(forkNode);
            forkExecutions.put(forkNode, new ForkExecution(plan, run.state));
        }

        private Map<String, Object> executeBranch(BranchPlan plan) {
            ForkExecution fork;
            synchronized (this) {
                fork = forkExecutions.get(plan.forkNode());
            }
            if (fork == null) {
                throw error(WorkflowErrorCode.INVALID_STATE, "execute", "fixed branch started without its fork");
            }
            BranchExecution branch = fork.branch(plan);
            for (WorkflowNodeDefinition node : plan.nodes()) {
                if (node.type() == WorkflowNodeType.SUBGRAPH) executeBranchSubgraph(node, branch);
                else executeNode(node, Optional.of(branch));
            }
            return Map.of("haifa.branch." + plan.providerNodeId(), marker());
        }

        private void executeBranchSubgraph(WorkflowNodeDefinition node, BranchExecution branch) {
            int attempt = visit(node.id());
            Instant started = now();
            WorkflowSubgraphBinding binding = node.subgraphBinding().orElseThrow();
            ProviderDefinition childProvider = definitions.get(binding.definition());
            if (childProvider == null) {
                throw error(WorkflowErrorCode.DEFINITION_NOT_FOUND, "subgraph", "child definition is unavailable");
            }
            WorkflowRunId childId = new WorkflowRunId(identifiers.nextValue());
            MutableRun child = new MutableRun(
                    childId,
                    childProvider,
                    mapInputs(branch.state(), childProvider, binding),
                    childProvider.compiled().definition().entryNode(),
                    now());
            child.parent = Optional.of(new WorkflowParentLink(run.id, node.id(), attempt));
            synchronized (this) {
                event(
                        run,
                        WorkflowEventType.SUBGRAPH_STARTED,
                        Optional.of(node.id()),
                        Map.of(
                                "childRunId",
                                childId.value(),
                                "definitionDigest",
                                binding.definition().digest().value()));
            }
            RunContext childContext = new RunContext(child);
            runs.put(childId, child);
            contexts.put(childId.value(), childContext);
            event(child, WorkflowEventType.RUN_STARTED, Optional.empty(), Map.of("parentRunId", run.id.value()));
            invoke(child, childContext, GraphInput.args(Map.of(PROVIDER_RUN_ID, childId.value())));
            if (child.status != WorkflowStatus.COMPLETED) {
                WorkflowErrorCode code = child.status == WorkflowStatus.WAITING
                        ? WorkflowErrorCode.UNSUPPORTED_CAPABILITY
                        : child.failure.map(WorkflowFailure::code).orElse(WorkflowErrorCode.NODE_EXECUTION_FAILED);
                branch.fail(new WorkflowNodeAttempt(
                        node.id(),
                        attempt,
                        WorkflowNodeAttemptStatus.FAILED,
                        Optional.empty(),
                        Optional.of(code),
                        started,
                        Optional.of(now())));
                throw error(code, "subgraph", "parallel subgraph must complete without interruption");
            }
            WorkflowState updated = branch.state().apply(mapOutputs(child.state, binding));
            synchronized (this) {
                event(
                        run,
                        WorkflowEventType.SUBGRAPH_COMPLETED,
                        Optional.of(node.id()),
                        Map.of("childRunId", childId.value()));
            }
            branch.complete(
                    updated,
                    new WorkflowNodeAttempt(
                            node.id(),
                            attempt,
                            WorkflowNodeAttemptStatus.COMPLETED,
                            Optional.empty(),
                            Optional.empty(),
                            started,
                            Optional.of(now())));
        }

        private synchronized void completeJoin(WorkflowNodeId joinNode) {
            ForkExecution execution = forkExecutions.values().stream()
                    .filter(candidate -> candidate.plan().joinNode().equals(joinNode))
                    .findFirst()
                    .orElseThrow(
                            () -> error(WorkflowErrorCode.INVALID_STATE, "merge", "JOIN_ALL has no active fixed fork"));
            flush(execution);
            run.currentNode = joinNode;
            List<BranchDelta> deltas = execution.plan().branches().stream()
                    .map(plan -> {
                        BranchExecution branch = execution.branch(plan);
                        return new BranchDelta(
                                plan.ordinal(), plan.entryNode(), difference(execution.base(), branch.state()));
                    })
                    .toList();
            try {
                run.state = stateMerger.merge(execution.base(), deltas);
            } catch (WorkflowException exception) {
                failedNode = Optional.of(joinNode);
                throw exception;
            }
            forkExecutions.remove(execution.plan().forkNode());
        }

        private synchronized String selectCondition(WorkflowNodeId source, List<WorkflowEdge> outgoing) {
            List<WorkflowEdge> selected = outgoing.stream()
                    .sorted(Comparator.comparingInt(WorkflowEdge::branchOrdinal).thenComparing(WorkflowEdge::target))
                    .filter(edge -> edge.conditionId()
                            .map(condition -> conditions.evaluate(condition, run.state))
                            .orElse(false))
                    .toList();
            if (selected.size() != 1) {
                failedNode = Optional.of(source);
                throw error(
                        WorkflowErrorCode.NODE_EXECUTION_FAILED,
                        "route",
                        "exactly one workflow condition must select a successor");
            }
            return selected.getFirst().conditionId().orElseThrow();
        }

        private synchronized void flushBranches() {
            forkExecutions.values().stream()
                    .sorted(Comparator.comparing(execution -> execution.plan().forkNode()))
                    .forEach(this::flush);
        }

        private void flush(ForkExecution execution) {
            if (execution.flushed()) {
                return;
            }
            execution.plan().branches().stream()
                    .sorted(Comparator.comparingInt(BranchPlan::ordinal).thenComparing(BranchPlan::entryNode))
                    .map(execution::branch)
                    .forEach(branch -> branch.attempts().forEach(attempt -> {
                        run.currentNode = attempt.nodeId();
                        event(run, WorkflowEventType.NODE_STARTED, Optional.of(attempt.nodeId()), Map.of());
                        run.attempts.add(attempt);
                        if (attempt.status() == WorkflowNodeAttemptStatus.COMPLETED) {
                            run.revision++;
                            event(run, WorkflowEventType.NODE_COMPLETED, Optional.of(attempt.nodeId()), Map.of());
                        } else {
                            failedNode = Optional.of(attempt.nodeId());
                        }
                    }));
            execution.markFlushed();
        }
    }

    private static final class BranchTopology {
        private final List<ForkPlan> forks;
        private final Map<WorkflowNodeId, ForkPlan> byFork;
        private final Set<WorkflowNodeId> branchNodeIds;

        private BranchTopology(List<ForkPlan> forks) {
            this.forks = List.copyOf(forks);
            this.byFork = new HashMap<>();
            Set<WorkflowNodeId> branchNodes = new HashSet<>();
            forks.forEach(fork -> {
                byFork.put(fork.forkNode(), fork);
                fork.branches().forEach(branch -> branch.nodes().forEach(node -> branchNodes.add(node.id())));
            });
            this.branchNodeIds = Set.copyOf(branchNodes);
        }

        private static BranchTopology from(CompiledWorkflowDefinition compiled) {
            List<ForkPlan> plans = compiled.definition().nodes().stream()
                    .filter(node -> node.type() == WorkflowNodeType.FORK_ALL)
                    .map(node -> forkPlan(compiled, node.id()))
                    .sorted(Comparator.comparing(ForkPlan::forkNode))
                    .toList();
            return new BranchTopology(plans);
        }

        private static ForkPlan forkPlan(CompiledWorkflowDefinition compiled, WorkflowNodeId forkNode) {
            List<BranchPlan> branches = new ArrayList<>();
            WorkflowNodeId join = null;
            for (WorkflowEdge edge : outgoing(compiled, forkNode).stream()
                    .sorted(Comparator.comparingInt(WorkflowEdge::branchOrdinal).thenComparing(WorkflowEdge::target))
                    .toList()) {
                List<WorkflowNodeDefinition> nodes = new ArrayList<>();
                WorkflowNodeId cursor = edge.target();
                while (node(compiled, cursor).type() != WorkflowNodeType.JOIN_ALL) {
                    WorkflowNodeDefinition branchNode = node(compiled, cursor);
                    nodes.add(branchNode);
                    cursor = outgoing(compiled, cursor).getFirst().target();
                }
                if (join != null && !join.equals(cursor)) {
                    throw error(
                            WorkflowErrorCode.INVALID_DEFINITION,
                            "compile",
                            "fixed branches must converge on one JOIN_ALL node");
                }
                join = cursor;
                branches.add(new BranchPlan(
                        forkNode,
                        edge.branchOrdinal(),
                        edge.target(),
                        "haifa_branch_" + forkNode.value() + "_" + edge.branchOrdinal(),
                        nodes));
            }
            return new ForkPlan(forkNode, Objects.requireNonNull(join, "join must not be null"), branches);
        }

        private List<ForkPlan> forks() {
            return forks;
        }

        private ForkPlan fork(WorkflowNodeId forkNode) {
            ForkPlan plan = byFork.get(forkNode);
            if (plan == null) {
                throw error(WorkflowErrorCode.INVALID_DEFINITION, "compile", "fixed fork plan does not exist");
            }
            return plan;
        }

        private Set<WorkflowNodeId> branchNodeIds() {
            return branchNodeIds;
        }
    }

    private record ForkPlan(WorkflowNodeId forkNode, WorkflowNodeId joinNode, List<BranchPlan> branches) {
        private ForkPlan {
            branches = List.copyOf(branches);
        }
    }

    private record BranchPlan(
            WorkflowNodeId forkNode,
            int ordinal,
            WorkflowNodeId entryNode,
            String providerNodeId,
            List<WorkflowNodeDefinition> nodes) {
        private BranchPlan {
            nodes = List.copyOf(nodes);
        }
    }

    private static final class ForkExecution {
        private final ForkPlan plan;
        private final WorkflowState base;
        private final Map<Integer, BranchExecution> branches = new HashMap<>();
        private boolean flushed;

        private ForkExecution(ForkPlan plan, WorkflowState base) {
            this.plan = plan;
            this.base = base;
            plan.branches().forEach(branch -> branches.put(branch.ordinal(), new BranchExecution(base)));
        }

        private ForkPlan plan() {
            return plan;
        }

        private WorkflowState base() {
            return base;
        }

        private BranchExecution branch(BranchPlan plan) {
            return branches.get(plan.ordinal());
        }

        private boolean flushed() {
            return flushed;
        }

        private void markFlushed() {
            flushed = true;
        }
    }

    private static final class BranchExecution {
        private final List<WorkflowNodeAttempt> attempts = new ArrayList<>();
        private WorkflowState state;

        private BranchExecution(WorkflowState base) {
            this.state = base;
        }

        private synchronized WorkflowState state() {
            return state;
        }

        private synchronized void complete(WorkflowState updated, WorkflowNodeAttempt attempt) {
            state = updated;
            attempts.add(attempt);
        }

        private synchronized void fail(WorkflowNodeAttempt attempt) {
            attempts.add(attempt);
        }

        private synchronized List<WorkflowNodeAttempt> attempts() {
            return List.copyOf(attempts);
        }
    }

    private record ProviderDefinition(
            CompiledWorkflowDefinition compiled,
            CompiledGraph<ProviderWorkflowState> graph,
            MemorySaver saver,
            BranchTopology topology) {}

    private record StartRecord(String fingerprint, WorkflowRunId runId) {}

    private record CommandRecord(String fingerprint, WorkflowRunSnapshot result) {}

    private static final class MutableRun {
        private final WorkflowRunId id;
        private final ProviderDefinition provider;
        private final Instant createdAt;
        private final List<WorkflowNodeAttempt> attempts = new ArrayList<>();
        private final List<WorkflowEvent> events = new ArrayList<>();
        private final Map<String, String> consumedSignals = new HashMap<>();
        private WorkflowState state;
        private WorkflowNodeId currentNode;
        private WorkflowStatus status = WorkflowStatus.RUNNING;
        private long revision = 1;
        private long eventSequence;
        private Optional<WorkflowWait> wait = Optional.empty();
        private Optional<WorkflowCheckpoint> checkpoint = Optional.empty();
        private Optional<WorkflowFailure> failure = Optional.empty();
        private Optional<WorkflowParentLink> parent = Optional.empty();
        private Optional<WorkflowSubgraphLink> activeSubgraph = Optional.empty();
        private Instant updatedAt;

        private MutableRun(
                WorkflowRunId id,
                ProviderDefinition provider,
                WorkflowState state,
                WorkflowNodeId currentNode,
                Instant createdAt) {
            this.id = id;
            this.provider = provider;
            this.state = state;
            this.currentNode = currentNode;
            this.createdAt = createdAt;
            this.updatedAt = createdAt;
        }
    }
}
