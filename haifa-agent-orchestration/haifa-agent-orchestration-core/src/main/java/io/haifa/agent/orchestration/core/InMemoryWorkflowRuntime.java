package io.haifa.agent.orchestration.core;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimePrecision;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.orchestration.api.CompiledWorkflowDefinition;
import io.haifa.agent.orchestration.api.WorkflowCancelRequest;
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
import io.haifa.agent.orchestration.core.DeterministicStateMerger.BranchDelta;
import io.haifa.agent.orchestration.core.spi.WorkflowActionGateway;
import io.haifa.agent.orchestration.core.spi.WorkflowAgentGateway;
import io.haifa.agent.orchestration.core.spi.WorkflowConditionEvaluator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Deterministic, process-local M1 reference runtime. It is deliberately not a durable production
 * scheduler; persistence and provider execution belong to later milestones.
 */
public final class InMemoryWorkflowRuntime implements WorkflowRuntime {
    private final Map<WorkflowDefinitionRef, CompiledWorkflowDefinition> definitions;
    private final WorkflowActionGateway actions;
    private final WorkflowAgentGateway agents;
    private final WorkflowConditionEvaluator conditions;
    private final IdentifierGenerator identifiers;
    private final TimeProvider timeProvider;
    private final DeterministicStateMerger stateMerger = new DeterministicStateMerger();
    private final Map<WorkflowRunId, MutableRun> runs = new LinkedHashMap<>();
    private final Map<String, StartRecord> starts = new HashMap<>();
    private final Map<String, CommandRecord> commandResults = new HashMap<>();

    public InMemoryWorkflowRuntime(
            List<CompiledWorkflowDefinition> definitions,
            WorkflowActionGateway actions,
            WorkflowAgentGateway agents,
            WorkflowConditionEvaluator conditions,
            IdentifierGenerator identifiers,
            TimeProvider timeProvider) {
        Objects.requireNonNull(definitions, "definitions must not be null");
        this.definitions = new HashMap<>();
        definitions.forEach(definition -> {
            register(definition);
        });
        this.actions = Objects.requireNonNull(actions, "actions must not be null");
        this.agents = Objects.requireNonNull(agents, "agents must not be null");
        this.conditions = Objects.requireNonNull(conditions, "conditions must not be null");
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers must not be null");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider must not be null");
    }

    private void register(CompiledWorkflowDefinition definition) {
        CompiledWorkflowDefinition prior = definitions.putIfAbsent(definition.reference(), definition);
        if (prior != null && !prior.definition().equals(definition.definition())) {
            throw new IllegalArgumentException("duplicate compiled workflow definition");
        }
        definition
                .subgraphDefinitions()
                .forEach((reference, child) -> definitions.putIfAbsent(
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
        CompiledWorkflowDefinition compiled = definitions.get(request.definition());
        if (compiled == null) {
            throw error(
                    WorkflowErrorCode.DEFINITION_NOT_FOUND, "start", "compiled workflow definition was not registered");
        }
        if (!compiled.definition().stateSchema().equals(request.initialState().schema())) {
            throw error(WorkflowErrorCode.INVALID_STATE, "start", "initial state schema does not match the definition");
        }
        Instant now = now();
        MutableRun run = new MutableRun(
                new WorkflowRunId(identifiers.nextValue()),
                compiled,
                request.initialState(),
                compiled.definition().entryNode(),
                now);
        runs.put(run.id, run);
        starts.put(request.idempotencyKey(), new StartRecord(fingerprint, run.id));
        event(run, WorkflowEventType.RUN_STARTED, Optional.empty(), Map.of());
        executeUntilBoundary(run);
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
        run.state = run.state.apply(request.delta());
        run.status = WorkflowStatus.RUNNING;
        run.wait = Optional.empty();
        run.checkpoint = Optional.empty();
        run.currentNode = onlySuccessor(run, wait.nodeId()).target();
        run.revision++;
        event(run, WorkflowEventType.RESUMED, Optional.of(wait.nodeId()), Map.of());
        executeUntilBoundary(run);
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

    private void executeUntilBoundary(MutableRun run) {
        try {
            while (run.status == WorkflowStatus.RUNNING) {
                WorkflowNodeDefinition node = node(run, run.currentNode);
                int visit = run.visits.merge(node.id(), 1, Integer::sum);
                if (visit > run.compiled.definition().limits().maximumIterationsPerNode()) {
                    throw error(
                            WorkflowErrorCode.ITERATION_LIMIT_EXCEEDED,
                            "execute",
                            "maximum node iteration count exceeded");
                }
                if (node.type() == WorkflowNodeType.TERMINAL) {
                    run.status = WorkflowStatus.COMPLETED;
                    run.revision++;
                    event(run, WorkflowEventType.COMPLETED, Optional.of(node.id()), Map.of());
                    continue;
                }
                if (node.type() == WorkflowNodeType.WAIT) {
                    interrupt(run, node);
                    continue;
                }
                if (node.type() == WorkflowNodeType.FORK_ALL) {
                    executeFork(run, node);
                    continue;
                }
                if (node.type() == WorkflowNodeType.JOIN_ALL) {
                    run.currentNode = onlySuccessor(run, node.id()).target();
                    continue;
                }
                if (node.type() == WorkflowNodeType.SUBGRAPH) {
                    executeSubgraph(run, node);
                    continue;
                }
                executeNode(run, node);
                run.currentNode = selectSuccessor(run, node.id()).target();
            }
        } catch (WorkflowException exception) {
            if (!run.status.terminal()) fail(run, exception.code(), exception.operation());
        } catch (RuntimeException exception) {
            if (!run.status.terminal()) fail(run, WorkflowErrorCode.NODE_EXECUTION_FAILED, "execute");
        }
    }

    private void executeSubgraph(MutableRun parent, WorkflowNodeDefinition node) {
        Instant started = now();
        parent.attempts.add(new WorkflowNodeAttempt(
                node.id(),
                parent.visits.get(node.id()),
                WorkflowNodeAttemptStatus.RUNNING,
                Optional.empty(),
                Optional.empty(),
                started,
                Optional.empty()));
        WorkflowSubgraphBinding binding = node.subgraphBinding().orElseThrow();
        CompiledWorkflowDefinition childDefinition = definitions.get(binding.definition());
        if (childDefinition == null) {
            throw error(WorkflowErrorCode.DEFINITION_NOT_FOUND, "subgraph", "child definition was not registered");
        }
        WorkflowRunId childId = new WorkflowRunId(identifiers.nextValue());
        WorkflowState childState = mapInputs(parent.state, childDefinition, binding);
        MutableRun child = new MutableRun(
                childId,
                childDefinition,
                childState,
                childDefinition.definition().entryNode(),
                started);
        child.parent = Optional.of(new WorkflowParentLink(parent.id, node.id(), parent.visits.get(node.id())));
        WorkflowSubgraphLink link =
                new WorkflowSubgraphLink(childId, binding.definition(), node.id(), parent.visits.get(node.id()));
        parent.activeSubgraph = Optional.of(link);
        runs.put(childId, child);
        event(
                parent,
                WorkflowEventType.SUBGRAPH_STARTED,
                Optional.of(node.id()),
                Map.of(
                        "childRunId",
                        childId.value(),
                        "definitionDigest",
                        binding.definition().digest().value()));
        event(child, WorkflowEventType.RUN_STARTED, Optional.empty(), Map.of("parentRunId", parent.id.value()));
        executeUntilBoundary(child);
        synchronizeSubgraph(parent, child, node, binding);
    }

    private void resumeSubgraph(MutableRun parent, WorkflowResumeRequest request) {
        WorkflowSubgraphLink link = parent.activeSubgraph.orElseThrow();
        MutableRun child = requireRun(link.runId());
        WorkflowWait childWait = child.wait.orElseThrow(
                () -> error(WorkflowErrorCode.INVALID_RESUME, "resume", "child workflow run is not waiting"));
        parent.consumedSignals.put(request.signalId().value(), request.idempotencyKey());
        child.consumedSignals.put(request.signalId().value(), request.idempotencyKey());
        child.state = child.state.apply(request.delta());
        child.status = WorkflowStatus.RUNNING;
        child.wait = Optional.empty();
        child.checkpoint = Optional.empty();
        child.currentNode = onlySuccessor(child, childWait.nodeId()).target();
        child.revision++;
        event(child, WorkflowEventType.RESUMED, Optional.of(childWait.nodeId()), Map.of());
        parent.status = WorkflowStatus.RUNNING;
        parent.wait = Optional.empty();
        parent.checkpoint = Optional.empty();
        parent.revision++;
        event(
                parent,
                WorkflowEventType.RESUMED,
                Optional.of(link.parentNodeId()),
                Map.of("childRunId", child.id.value()));
        executeUntilBoundary(child);
        WorkflowNodeDefinition node = node(parent, link.parentNodeId());
        synchronizeSubgraph(parent, child, node, node.subgraphBinding().orElseThrow());
        if (parent.status == WorkflowStatus.RUNNING) {
            executeUntilBoundary(parent);
        }
    }

    private void synchronizeSubgraph(
            MutableRun parent, MutableRun child, WorkflowNodeDefinition node, WorkflowSubgraphBinding binding) {
        if (child.status == WorkflowStatus.WAITING) {
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
            return;
        }
        WorkflowNodeAttempt active = parent.attempts.getLast();
        if (child.status == WorkflowStatus.COMPLETED) {
            parent.state = parent.state.apply(mapOutputs(child.state, binding));
            parent.attempts.set(
                    parent.attempts.size() - 1,
                    new WorkflowNodeAttempt(
                            active.nodeId(),
                            active.attempt(),
                            WorkflowNodeAttemptStatus.COMPLETED,
                            Optional.empty(),
                            Optional.empty(),
                            active.startedAt(),
                            Optional.of(now())));
            parent.activeSubgraph = Optional.empty();
            parent.currentNode = selectSuccessor(parent, node.id()).target();
            parent.revision++;
            event(
                    parent,
                    WorkflowEventType.SUBGRAPH_COMPLETED,
                    Optional.of(node.id()),
                    Map.of("childRunId", child.id.value()));
            return;
        }
        WorkflowErrorCode code =
                child.failure.map(WorkflowFailure::code).orElse(WorkflowErrorCode.NODE_EXECUTION_FAILED);
        parent.attempts.set(
                parent.attempts.size() - 1,
                new WorkflowNodeAttempt(
                        active.nodeId(),
                        active.attempt(),
                        WorkflowNodeAttemptStatus.FAILED,
                        Optional.empty(),
                        Optional.of(code),
                        active.startedAt(),
                        Optional.of(now())));
        parent.activeSubgraph = Optional.empty();
        fail(parent, code, "subgraph");
    }

    private static WorkflowState mapInputs(
            WorkflowState parent, CompiledWorkflowDefinition child, WorkflowSubgraphBinding binding) {
        Map<String, Object> values = new LinkedHashMap<>();
        binding.stateMapping().inputs().forEach((parentKey, childKey) -> {
            if (parent.values().containsKey(parentKey)) {
                values.put(childKey, parent.values().get(parentKey));
            }
        });
        return new WorkflowState(child.definition().stateSchema(), values);
    }

    private static WorkflowStateDelta mapOutputs(WorkflowState child, WorkflowSubgraphBinding binding) {
        Map<String, Object> values = new LinkedHashMap<>();
        binding.stateMapping().outputs().forEach((childKey, parentKey) -> {
            if (child.values().containsKey(childKey)) {
                values.put(parentKey, child.values().get(childKey));
            }
        });
        return new WorkflowStateDelta(values);
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

    private void executeNode(MutableRun run, WorkflowNodeDefinition node) {
        Instant started = now();
        event(run, WorkflowEventType.NODE_STARTED, Optional.of(node.id()), Map.of());
        AgentRunId agentRunId = null;
        try {
            WorkflowStateDelta delta;
            if (node.type() == WorkflowNodeType.AGENT_RUN) {
                WorkflowAgentGateway.AgentExecution execution = agents.execute(run.id, node, run.state);
                agentRunId = execution.agentRunId();
                delta = execution.delta();
            } else {
                delta = actions.execute(run.id, node, run.state);
            }
            run.state = run.state.apply(delta);
        } catch (RuntimeException exception) {
            WorkflowErrorCode code = exception instanceof WorkflowException workflowException
                    ? workflowException.code()
                    : WorkflowErrorCode.NODE_EXECUTION_FAILED;
            WorkflowNodeAttemptStatus status = code == WorkflowErrorCode.OUTCOME_UNKNOWN
                    ? WorkflowNodeAttemptStatus.OUTCOME_UNKNOWN
                    : WorkflowNodeAttemptStatus.FAILED;
            run.attempts.add(new WorkflowNodeAttempt(
                    node.id(),
                    run.visits.get(node.id()),
                    status,
                    Optional.ofNullable(agentRunId),
                    Optional.of(code),
                    started,
                    Optional.of(now())));
            throw exception;
        }
        run.attempts.add(new WorkflowNodeAttempt(
                node.id(),
                run.visits.get(node.id()),
                WorkflowNodeAttemptStatus.COMPLETED,
                Optional.ofNullable(agentRunId),
                Optional.empty(),
                started,
                Optional.of(now())));
        run.revision++;
        event(run, WorkflowEventType.NODE_COMPLETED, Optional.of(node.id()), Map.of());
    }

    private void executeFork(MutableRun run, WorkflowNodeDefinition fork) {
        List<WorkflowEdge> branches = outgoing(run, fork.id()).stream()
                .sorted(Comparator.comparingInt(WorkflowEdge::branchOrdinal).thenComparing(WorkflowEdge::target))
                .toList();
        WorkflowState base = run.state;
        List<BranchDelta> deltas = new ArrayList<>();
        WorkflowNodeId commonJoin = null;
        try {
            for (WorkflowEdge branch : branches) {
                run.state = base;
                WorkflowNodeId cursor = branch.target();
                while (node(run, cursor).type() != WorkflowNodeType.JOIN_ALL) {
                    WorkflowNodeDefinition branchNode = node(run, cursor);
                    if (branchNode.type() != WorkflowNodeType.ACTION
                            && branchNode.type() != WorkflowNodeType.AGENT_RUN
                            && branchNode.type() != WorkflowNodeType.SUBGRAPH) {
                        throw error(
                                WorkflowErrorCode.INVALID_DEFINITION,
                                "execute",
                                "fixed branch may only contain action, agent, or subgraph nodes before JOIN_ALL");
                    }
                    int visit = run.visits.merge(branchNode.id(), 1, Integer::sum);
                    if (visit > run.compiled.definition().limits().maximumIterationsPerNode()) {
                        throw error(
                                WorkflowErrorCode.ITERATION_LIMIT_EXCEEDED,
                                "execute",
                                "maximum branch node iteration count exceeded");
                    }
                    if (branchNode.type() == WorkflowNodeType.SUBGRAPH) {
                        executeSubgraph(run, branchNode);
                        if (run.status == WorkflowStatus.WAITING) {
                            throw error(
                                    WorkflowErrorCode.UNSUPPORTED_CAPABILITY,
                                    "execute",
                                    "interrupting subgraph inside fixed parallel branch is not supported");
                        }
                        if (run.status == WorkflowStatus.FAILED) {
                            WorkflowFailure failure = run.failure.orElseThrow();
                            throw error(failure.code(), failure.operation(), "parallel subgraph failed");
                        }
                        cursor = run.currentNode;
                    } else {
                        executeNode(run, branchNode);
                        cursor = onlySuccessor(run, cursor).target();
                    }
                }
                if (commonJoin != null && !commonJoin.equals(cursor)) {
                    throw error(
                            WorkflowErrorCode.INVALID_DEFINITION,
                            "execute",
                            "fixed branches must converge on one JOIN_ALL node");
                }
                commonJoin = cursor;
                deltas.add(new BranchDelta(branch.branchOrdinal(), branch.target(), difference(base, run.state)));
            }
            run.state = stateMerger.merge(base, deltas);
            run.currentNode = Objects.requireNonNull(commonJoin, "commonJoin must not be null");
        } catch (RuntimeException exception) {
            run.state = base;
            throw exception;
        }
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

    private void interrupt(MutableRun run, WorkflowNodeDefinition node) {
        Instant now = now();
        run.status = WorkflowStatus.WAITING;
        run.revision++;
        WorkflowWait wait = new WorkflowWait(new WorkflowWaitId(identifiers.nextValue()), node.id(), run.revision, now);
        run.wait = Optional.of(wait);
        run.checkpoint = Optional.of(new WorkflowCheckpoint(
                new WorkflowCheckpointId(identifiers.nextValue()), run.id, run.revision, node.id(), run.state, now));
        event(run, WorkflowEventType.WAITING, Optional.of(node.id()), Map.of());
    }

    private WorkflowEdge selectSuccessor(MutableRun run, WorkflowNodeId nodeId) {
        List<WorkflowEdge> outgoing = outgoing(run, nodeId);
        if (outgoing.size() == 1 && outgoing.getFirst().conditionId().isEmpty()) {
            return outgoing.getFirst();
        }
        List<WorkflowEdge> selected = outgoing.stream()
                .sorted(Comparator.comparingInt(WorkflowEdge::branchOrdinal).thenComparing(WorkflowEdge::target))
                .filter(edge -> edge.conditionId()
                        .map(condition -> conditions.evaluate(condition, run.state))
                        .orElse(false))
                .toList();
        if (selected.size() != 1) {
            throw error(
                    WorkflowErrorCode.NODE_EXECUTION_FAILED,
                    "route",
                    "exactly one workflow condition must select a successor");
        }
        return selected.getFirst();
    }

    private WorkflowEdge onlySuccessor(MutableRun run, WorkflowNodeId nodeId) {
        List<WorkflowEdge> outgoing = outgoing(run, nodeId);
        if (outgoing.size() != 1 || outgoing.getFirst().conditionId().isPresent()) {
            throw error(
                    WorkflowErrorCode.INVALID_DEFINITION,
                    "execute",
                    "node must have exactly one unconditional successor");
        }
        return outgoing.getFirst();
    }

    private List<WorkflowEdge> outgoing(MutableRun run, WorkflowNodeId nodeId) {
        return run.compiled.definition().edges().stream()
                .filter(edge -> edge.source().equals(nodeId))
                .toList();
    }

    private WorkflowNodeDefinition node(MutableRun run, WorkflowNodeId nodeId) {
        return run.compiled.definition().nodes().stream()
                .filter(node -> node.id().equals(nodeId))
                .findFirst()
                .orElseThrow(
                        () -> error(WorkflowErrorCode.INVALID_DEFINITION, "execute", "workflow node does not exist"));
    }

    private void fail(MutableRun run, WorkflowErrorCode code, String operation) {
        run.status = WorkflowStatus.FAILED;
        run.failure = Optional.of(new WorkflowFailure(code, operation, Optional.of(run.currentNode)));
        run.revision++;
        event(run, WorkflowEventType.FAILED, Optional.of(run.currentNode), Map.of("code", code.name()));
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
                run.compiled.reference(),
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

    private Instant now() {
        return TimePrecision.toMilliseconds(timeProvider.now());
    }

    private static WorkflowException error(WorkflowErrorCode code, String operation, String message) {
        return new WorkflowException(code, operation, message);
    }

    private record StartRecord(String fingerprint, WorkflowRunId runId) {}

    private record CommandRecord(String fingerprint, WorkflowRunSnapshot result) {}

    private static final class MutableRun {
        private final WorkflowRunId id;
        private final CompiledWorkflowDefinition compiled;
        private final Instant createdAt;
        private final List<WorkflowNodeAttempt> attempts = new ArrayList<>();
        private final List<WorkflowEvent> events = new ArrayList<>();
        private final Map<WorkflowNodeId, Integer> visits = new HashMap<>();
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
                CompiledWorkflowDefinition compiled,
                WorkflowState state,
                WorkflowNodeId currentNode,
                Instant createdAt) {
            this.id = id;
            this.compiled = compiled;
            this.state = state;
            this.currentNode = currentNode;
            this.createdAt = createdAt;
            this.updatedAt = createdAt;
        }
    }
}
