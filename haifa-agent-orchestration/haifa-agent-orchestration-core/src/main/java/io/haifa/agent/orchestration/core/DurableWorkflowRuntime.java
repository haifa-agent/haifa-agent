package io.haifa.agent.orchestration.core;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimePrecision;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.orchestration.api.CompiledWorkflowDefinition;
import io.haifa.agent.orchestration.api.RecoverableWorkflowRuntime;
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
import io.haifa.agent.orchestration.core.spi.DurableWorkflowAgentGateway;
import io.haifa.agent.orchestration.core.spi.StoredWorkflowCommand;
import io.haifa.agent.orchestration.core.spi.StoredWorkflowRun;
import io.haifa.agent.orchestration.core.spi.WorkflowActionGateway;
import io.haifa.agent.orchestration.core.spi.WorkflowAgentGateway;
import io.haifa.agent.orchestration.core.spi.WorkflowConditionEvaluator;
import io.haifa.agent.orchestration.core.spi.WorkflowFailureInjector;
import io.haifa.agent.orchestration.core.spi.WorkflowFailurePoint;
import io.haifa.agent.orchestration.core.spi.WorkflowForkState;
import io.haifa.agent.orchestration.core.spi.WorkflowPersistenceBinding;
import io.haifa.agent.orchestration.core.spi.WorkflowStore;
import io.haifa.agent.orchestration.core.spi.WorkflowUnitOfWork;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Provider-neutral durable workflow coordinator.
 *
 * <p>Node dispatch and result application are separate commits. A committed result delta can be
 * applied after restart without replaying the node; a running attempt without a committed result
 * is conservatively classified as outcome unknown.
 */
public final class DurableWorkflowRuntime implements RecoverableWorkflowRuntime {
    private final Map<WorkflowDefinitionRef, CompiledWorkflowDefinition> definitions;
    private final WorkflowActionGateway actions;
    private final WorkflowAgentGateway agents;
    private final WorkflowConditionEvaluator conditions;
    private final IdentifierGenerator identifiers;
    private final TimeProvider timeProvider;
    private final WorkflowStore store;
    private final WorkflowUnitOfWork unitOfWork;
    private final WorkflowPersistenceBinding binding;
    private final WorkflowFailureInjector failures;
    private final DeterministicStateMerger merger = new DeterministicStateMerger();

    public DurableWorkflowRuntime(
            List<CompiledWorkflowDefinition> definitions,
            WorkflowActionGateway actions,
            WorkflowAgentGateway agents,
            WorkflowConditionEvaluator conditions,
            IdentifierGenerator identifiers,
            TimeProvider timeProvider,
            WorkflowStore store,
            WorkflowUnitOfWork unitOfWork,
            WorkflowPersistenceBinding binding) {
        this(
                definitions,
                actions,
                agents,
                conditions,
                identifiers,
                timeProvider,
                store,
                unitOfWork,
                binding,
                WorkflowFailureInjector.NONE);
    }

    public DurableWorkflowRuntime(
            List<CompiledWorkflowDefinition> definitions,
            WorkflowActionGateway actions,
            WorkflowAgentGateway agents,
            WorkflowConditionEvaluator conditions,
            IdentifierGenerator identifiers,
            TimeProvider timeProvider,
            WorkflowStore store,
            WorkflowUnitOfWork unitOfWork,
            WorkflowPersistenceBinding binding,
            WorkflowFailureInjector failures) {
        Objects.requireNonNull(definitions, "definitions must not be null");
        this.definitions = new LinkedHashMap<>();
        definitions.forEach(definition -> {
            register(definition);
        });
        this.actions = Objects.requireNonNull(actions, "actions must not be null");
        this.agents = Objects.requireNonNull(agents, "agents must not be null");
        this.conditions = Objects.requireNonNull(conditions, "conditions must not be null");
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers must not be null");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        this.binding = Objects.requireNonNull(binding, "binding must not be null");
        this.failures = Objects.requireNonNull(failures, "failures must not be null");
        boolean hasAgentNodes = this.definitions.values().stream()
                .flatMap(definition -> definition.definition().nodes().stream())
                .anyMatch(node -> node.type() == WorkflowNodeType.AGENT_RUN);
        if (hasAgentNodes && !(agents instanceof DurableWorkflowAgentGateway)) {
            throw new IllegalArgumentException(
                    "durable Agent nodes require DurableWorkflowAgentGateway for atomic Run association");
        }
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
    public WorkflowRunSnapshot start(WorkflowStartRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String keyDigest = digest(request.idempotencyKey());
        String requestDigest = digest(
                request.definition() + "|" + canonical(request.initialState().values()));
        Optional<StoredWorkflowCommand> existing = store.findCommand("start", "global", keyDigest);
        if (existing.isPresent()) return existing(existing.get(), requestDigest, "start");

        CompiledWorkflowDefinition compiled = requireDefinition(request.definition(), "start");
        if (!compiled.definition().stateSchema().equals(request.initialState().schema())) {
            throw error(WorkflowErrorCode.INVALID_STATE, "start", "initial state schema does not match definition");
        }
        Instant now = now();
        MutableRun run = MutableRun.create(
                new WorkflowRunId(identifiers.nextValue()), compiled, binding, request.initialState(), now);
        WorkflowEvent started = event(run, WorkflowEventType.RUN_STARTED, Optional.empty(), Map.of());
        StoredWorkflowCommand receipt = command("start", "global", keyDigest, requestDigest, run);
        unitOfWork.execute(() -> {
            store.create(run.stored(), receipt, List.of(started));
            return null;
        });
        failures.afterCommit(WorkflowFailurePoint.AFTER_RUN_CREATED);
        drive(run);
        save(run, List.of(), Optional.of(command("start", "global", keyDigest, requestDigest, run)));
        return run.snapshot();
    }

    @Override
    public Optional<WorkflowRunSnapshot> find(WorkflowRunId runId) {
        return store.find(Objects.requireNonNull(runId, "runId must not be null"))
                .map(this::load)
                .map(MutableRun::snapshot);
    }

    @Override
    public WorkflowRunSnapshot resume(WorkflowResumeRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String scope = request.runId().value();
        String keyDigest = digest(request.idempotencyKey());
        String requestDigest = digest(request.waitId().value()
                + '|'
                + request.expectedRevision()
                + '|'
                + request.signalId().value()
                + '|'
                + canonical(request.delta().values()));
        Optional<StoredWorkflowCommand> existing = store.findCommand("resume", scope, keyDigest);
        if (existing.isPresent()) return existing(existing.get(), requestDigest, "resume");

        MutableRun run = requireRun(request.runId());
        WorkflowWait wait = run.wait.orElseThrow(
                () -> error(WorkflowErrorCode.INVALID_RESUME, "resume", "workflow run is not waiting"));
        if (!wait.id().equals(request.waitId())
                || run.revision != request.expectedRevision()
                || run.consumedSignals.contains(request.signalId().value())) {
            throw error(WorkflowErrorCode.INVALID_RESUME, "resume", "wait, revision, or signal is stale");
        }
        if (run.activeSubgraph.isPresent()) {
            resumeSubgraph(run, request, keyDigest, requestDigest);
            save(run, List.of(), Optional.of(command("resume", scope, keyDigest, requestDigest, run)));
            return run.snapshot();
        }
        run.consumedSignals.add(request.signalId().value());
        run.state = run.state.apply(request.delta());
        run.status = WorkflowStatus.RUNNING;
        run.wait = Optional.empty();
        run.checkpoint = Optional.empty();
        run.currentNode = onlySuccessor(run, wait.nodeId()).target();
        run.revision++;
        WorkflowEvent resumed = event(run, WorkflowEventType.RESUMED, Optional.of(wait.nodeId()), Map.of());
        save(run, List.of(resumed), Optional.of(command("resume", scope, keyDigest, requestDigest, run)));
        failures.afterCommit(WorkflowFailurePoint.AFTER_RESUME_CONSUMED);
        drive(run);
        save(run, List.of(), Optional.of(command("resume", scope, keyDigest, requestDigest, run)));
        return run.snapshot();
    }

    @Override
    public WorkflowRunSnapshot cancel(WorkflowCancelRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String scope = request.runId().value();
        String keyDigest = digest(request.idempotencyKey());
        String requestDigest = digest(scope);
        Optional<StoredWorkflowCommand> existing = store.findCommand("cancel", scope, keyDigest);
        if (existing.isPresent()) return existing(existing.get(), requestDigest, "cancel");

        MutableRun run = requireRun(request.runId());
        run.activeSubgraph.ifPresent(link -> cancelChild(link.runId()));
        run.activeSubgraph = Optional.empty();
        Optional<AgentRunId> linked = run.activeAttempt().flatMap(WorkflowNodeAttempt::agentRunId);
        List<WorkflowEvent> newEvents = List.of();
        if (!run.status.terminal()) {
            terminateActiveAttempt(run, WorkflowErrorCode.TERMINAL_RUN);
            run.status = WorkflowStatus.CANCELLED;
            run.wait = Optional.empty();
            run.checkpoint = Optional.empty();
            run.revision++;
            run.pendingAgentCancellation = linked;
            newEvents = List.of(event(run, WorkflowEventType.CANCELLED, Optional.of(run.currentNode), Map.of()));
        }
        save(run, newEvents, Optional.of(command("cancel", scope, keyDigest, requestDigest, run)));
        failures.afterCommit(WorkflowFailurePoint.AFTER_CANCEL_COMMITTED);
        propagateCancellation(run);
        return run.snapshot();
    }

    @Override
    public WorkflowRunSnapshot timeout(WorkflowTimeoutRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String scope = request.runId().value();
        String keyDigest = digest(request.idempotencyKey());
        String requestDigest = digest(scope);
        Optional<StoredWorkflowCommand> existing = store.findCommand("timeout", scope, keyDigest);
        if (existing.isPresent()) return existing(existing.get(), requestDigest, "timeout");

        MutableRun run = requireRun(request.runId());
        run.activeSubgraph.ifPresent(link -> timeoutChild(link.runId()));
        run.activeSubgraph = Optional.empty();
        Optional<AgentRunId> linked = run.activeAttempt().flatMap(WorkflowNodeAttempt::agentRunId);
        List<WorkflowEvent> newEvents = List.of();
        if (!run.status.terminal()) {
            terminateActiveAttempt(run, WorkflowErrorCode.TIMED_OUT);
            run.status = WorkflowStatus.TIMED_OUT;
            run.wait = Optional.empty();
            run.checkpoint = Optional.empty();
            run.revision++;
            run.pendingAgentCancellation = linked;
            newEvents = List.of(event(run, WorkflowEventType.TIMED_OUT, Optional.of(run.currentNode), Map.of()));
        }
        save(run, newEvents, Optional.of(command("timeout", scope, keyDigest, requestDigest, run)));
        propagateCancellation(run);
        return run.snapshot();
    }

    @Override
    public List<WorkflowEvent> events(WorkflowRunId runId, long afterSequence, int limit) {
        if (afterSequence < 0 || limit < 1) {
            throw new IllegalArgumentException("event cursor must be non-negative and limit positive");
        }
        return store.events(Objects.requireNonNull(runId, "runId must not be null"), afterSequence, limit);
    }

    @Override
    public WorkflowRunSnapshot recover(WorkflowRunId runId) {
        MutableRun run = requireRun(runId);
        propagateCancellation(run);
        if (run.status.terminal() || run.status == WorkflowStatus.WAITING) return run.snapshot();
        if (run.pendingDelta.isPresent()) {
            completeScheduledNode(run);
        } else if (run.activeAttempt().isPresent()) {
            WorkflowNodeAttempt attempt = run.activeAttempt().orElseThrow();
            WorkflowNodeDefinition node = node(run, attempt.nodeId());
            if (node.type() == WorkflowNodeType.SUBGRAPH) {
                recoverSubgraph(run, node, attempt);
                if (run.status == WorkflowStatus.RUNNING) drive(run);
                return run.snapshot();
            }
            Optional<WorkflowAgentGateway.AgentExecution> recovered = attempt.agentRunId()
                    .filter(ignored -> node.type() == WorkflowNodeType.AGENT_RUN)
                    .flatMap(agentRunId -> agents.recover(run.id, node, run.state, agentRunId));
            if (recovered.isPresent()
                    && recovered.get().agentRunId().equals(attempt.agentRunId().orElseThrow())) {
                storeNodeResult(
                        run,
                        recovered.get().delta(),
                        Optional.of(recovered.get().agentRunId()));
                completeScheduledNode(run);
            } else {
                failActive(run, WorkflowErrorCode.OUTCOME_UNKNOWN, "recover");
                return run.snapshot();
            }
        }
        drive(run);
        return run.snapshot();
    }

    private void drive(MutableRun run) {
        while (run.status == WorkflowStatus.RUNNING) {
            if (run.forkState.isPresent()) {
                driveFork(run);
                continue;
            }
            WorkflowNodeDefinition node = node(run, run.currentNode);
            if (node.type() == WorkflowNodeType.TERMINAL) {
                run.status = WorkflowStatus.COMPLETED;
                run.revision++;
                save(
                        run,
                        List.of(event(run, WorkflowEventType.COMPLETED, Optional.of(node.id()), Map.of())),
                        Optional.empty());
            } else if (node.type() == WorkflowNodeType.WAIT) {
                interrupt(run, node);
            } else if (node.type() == WorkflowNodeType.FORK_ALL) {
                beginFork(run, node);
            } else if (node.type() == WorkflowNodeType.JOIN_ALL) {
                run.currentNode = onlySuccessor(run, node.id()).target();
                save(run, List.of(), Optional.empty());
            } else {
                executeScheduledNode(run, node);
            }
        }
    }

    private void executeScheduledNode(MutableRun run, WorkflowNodeDefinition node) {
        if (!scheduleNode(run, node)) return;
        if (node.type() == WorkflowNodeType.SUBGRAPH) {
            executeSubgraph(run, node);
            return;
        }
        if (node.type() == WorkflowNodeType.AGENT_RUN) {
            executeAgentNode(run, node);
            return;
        }
        WorkflowStateDelta delta;
        try {
            delta = actions.execute(run.id, node, run.state);
        } catch (WorkflowException exception) {
            failActive(run, exception.code(), exception.operation());
            return;
        } catch (RuntimeException exception) {
            failActive(run, WorkflowErrorCode.OUTCOME_UNKNOWN, "execute");
            return;
        }
        storeNodeResult(run, delta, Optional.empty());
        completeScheduledNode(run);
    }

    private void executeSubgraph(MutableRun parent, WorkflowNodeDefinition node) {
        WorkflowNodeAttempt attempt = parent.activeAttempt().orElseThrow();
        WorkflowSubgraphBinding subgraph = node.subgraphBinding().orElseThrow();
        CompiledWorkflowDefinition childDefinition = requireDefinition(subgraph.definition(), "subgraph");
        WorkflowRunId childId = new WorkflowRunId(identifiers.nextValue());
        MutableRun child = MutableRun.create(
                childId, childDefinition, binding, mapInputs(parent.state, childDefinition, subgraph), now());
        child.parent = Optional.of(new WorkflowParentLink(parent.id, node.id(), attempt.attempt()));
        String keyDigest = subgraphKey(parent.id, node.id(), attempt.attempt());
        String requestDigest = digest(subgraph.definition() + "|" + canonical(child.state.values()));
        WorkflowEvent childStarted =
                event(child, WorkflowEventType.RUN_STARTED, Optional.empty(), Map.of("parentRunId", parent.id.value()));
        unitOfWork.execute(() -> {
            store.create(
                    child.stored(),
                    command("subgraph-start", parent.id.value(), keyDigest, requestDigest, child),
                    List.of(childStarted));
            return null;
        });
        failures.afterCommit(WorkflowFailurePoint.AFTER_SUBGRAPH_CREATED);
        parent.activeSubgraph =
                Optional.of(new WorkflowSubgraphLink(child.id, subgraph.definition(), node.id(), attempt.attempt()));
        save(
                parent,
                List.of(event(
                        parent,
                        WorkflowEventType.SUBGRAPH_STARTED,
                        Optional.of(node.id()),
                        Map.of(
                                "childRunId",
                                child.id.value(),
                                "definitionDigest",
                                subgraph.definition().digest().value()))),
                Optional.empty());
        failures.afterCommit(WorkflowFailurePoint.AFTER_SUBGRAPH_LINKED);
        drive(child);
        if (child.status == WorkflowStatus.COMPLETED) {
            failures.afterCommit(WorkflowFailurePoint.AFTER_SUBGRAPH_CHILD_COMPLETED);
        }
        synchronizeSubgraph(parent, child, node, subgraph);
    }

    private void recoverSubgraph(MutableRun parent, WorkflowNodeDefinition node, WorkflowNodeAttempt attempt) {
        WorkflowSubgraphBinding binding = node.subgraphBinding().orElseThrow();
        MutableRun child;
        if (parent.activeSubgraph.isPresent()) {
            child = requireRun(parent.activeSubgraph.orElseThrow().runId());
        } else {
            String key = subgraphKey(parent.id, node.id(), attempt.attempt());
            StoredWorkflowCommand receipt = store.findCommand("subgraph-start", parent.id.value(), key)
                    .orElseThrow(() -> error(
                            WorkflowErrorCode.OUTCOME_UNKNOWN,
                            "recover-subgraph",
                            "subgraph start outcome is unknown"));
            child = requireRun(receipt.runId());
            parent.activeSubgraph =
                    Optional.of(new WorkflowSubgraphLink(child.id, binding.definition(), node.id(), attempt.attempt()));
            save(parent, List.of(), Optional.empty());
        }
        if (child.status == WorkflowStatus.RUNNING) {
            recover(child.id);
            child = requireRun(child.id);
        }
        synchronizeSubgraph(parent, child, node, binding);
    }

    private void resumeSubgraph(
            MutableRun parent, WorkflowResumeRequest request, String keyDigest, String requestDigest) {
        WorkflowSubgraphLink link = parent.activeSubgraph.orElseThrow();
        MutableRun child = requireRun(link.runId());
        WorkflowWait childWait = child.wait.orElseThrow(
                () -> error(WorkflowErrorCode.INVALID_RESUME, "resume-subgraph", "child workflow is not waiting"));
        child.consumedSignals.add(request.signalId().value());
        child.state = child.state.apply(request.delta());
        child.status = WorkflowStatus.RUNNING;
        child.wait = Optional.empty();
        child.checkpoint = Optional.empty();
        child.currentNode = onlySuccessor(child, childWait.nodeId()).target();
        child.revision++;
        parent.consumedSignals.add(request.signalId().value());
        parent.status = WorkflowStatus.RUNNING;
        parent.wait = Optional.empty();
        parent.checkpoint = Optional.empty();
        parent.revision++;
        WorkflowEvent childResumed = event(child, WorkflowEventType.RESUMED, Optional.of(childWait.nodeId()), Map.of());
        WorkflowEvent parentResumed = event(
                parent,
                WorkflowEventType.RESUMED,
                Optional.of(link.parentNodeId()),
                Map.of("childRunId", child.id.value()));
        unitOfWork.execute(() -> {
            save(child, List.of(childResumed), Optional.empty());
            save(
                    parent,
                    List.of(parentResumed),
                    Optional.of(command("resume", parent.id.value(), keyDigest, requestDigest, parent)));
            return null;
        });
        failures.afterCommit(WorkflowFailurePoint.AFTER_RESUME_CONSUMED);
        drive(child);
        WorkflowNodeDefinition node = node(parent, link.parentNodeId());
        synchronizeSubgraph(parent, child, node, node.subgraphBinding().orElseThrow());
        if (parent.status == WorkflowStatus.RUNNING) drive(parent);
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
            save(
                    parent,
                    List.of(event(
                            parent,
                            WorkflowEventType.WAITING,
                            Optional.of(node.id()),
                            Map.of(
                                    "childRunId",
                                    child.id.value(),
                                    "childNodeId",
                                    childWait.nodeId().value()))),
                    Optional.empty());
            failures.afterCommit(WorkflowFailurePoint.AFTER_CHECKPOINT_STORED);
            return;
        }
        if (child.status == WorkflowStatus.COMPLETED) {
            storeNodeResult(parent, mapOutputs(child.state, binding), Optional.empty());
            completeScheduledNode(parent);
            parent.activeSubgraph = Optional.empty();
            save(
                    parent,
                    List.of(event(
                            parent,
                            WorkflowEventType.SUBGRAPH_COMPLETED,
                            Optional.of(node.id()),
                            Map.of("childRunId", child.id.value()))),
                    Optional.empty());
            return;
        }
        WorkflowErrorCode code =
                child.failure.map(WorkflowFailure::code).orElse(WorkflowErrorCode.NODE_EXECUTION_FAILED);
        parent.activeSubgraph = Optional.empty();
        failActive(parent, code, "subgraph");
    }

    private void cancelChild(WorkflowRunId childId) {
        MutableRun child = requireRun(childId);
        child.activeSubgraph.ifPresent(link -> cancelChild(link.runId()));
        child.activeSubgraph = Optional.empty();
        if (!child.status.terminal()) {
            terminateActiveAttempt(child, WorkflowErrorCode.TERMINAL_RUN);
            child.status = WorkflowStatus.CANCELLED;
            child.wait = Optional.empty();
            child.checkpoint = Optional.empty();
            child.revision++;
            save(
                    child,
                    List.of(event(child, WorkflowEventType.CANCELLED, Optional.of(child.currentNode), Map.of())),
                    Optional.empty());
            propagateCancellation(child);
        }
    }

    private void timeoutChild(WorkflowRunId childId) {
        MutableRun child = requireRun(childId);
        child.activeSubgraph.ifPresent(link -> timeoutChild(link.runId()));
        child.activeSubgraph = Optional.empty();
        if (!child.status.terminal()) {
            terminateActiveAttempt(child, WorkflowErrorCode.TIMED_OUT);
            child.status = WorkflowStatus.TIMED_OUT;
            child.wait = Optional.empty();
            child.checkpoint = Optional.empty();
            child.revision++;
            save(
                    child,
                    List.of(event(child, WorkflowEventType.TIMED_OUT, Optional.of(child.currentNode), Map.of())),
                    Optional.empty());
            propagateCancellation(child);
        }
    }

    private static WorkflowState mapInputs(
            WorkflowState parent, CompiledWorkflowDefinition child, WorkflowSubgraphBinding binding) {
        Map<String, Object> values = new LinkedHashMap<>();
        binding.stateMapping().inputs().forEach((parentKey, childKey) -> {
            if (parent.values().containsKey(parentKey))
                values.put(childKey, parent.values().get(parentKey));
        });
        return new WorkflowState(child.definition().stateSchema(), values);
    }

    private void terminateActiveAttempt(MutableRun run, WorkflowErrorCode code) {
        run.activeAttempt().ifPresent(active -> {
            replaceLastAttempt(
                    run,
                    new WorkflowNodeAttempt(
                            active.nodeId(),
                            active.attempt(),
                            WorkflowNodeAttemptStatus.FAILED,
                            active.agentRunId(),
                            Optional.of(code),
                            active.startedAt(),
                            Optional.of(now())));
            run.pendingDelta = Optional.empty();
        });
    }

    private static WorkflowStateDelta mapOutputs(WorkflowState child, WorkflowSubgraphBinding binding) {
        Map<String, Object> values = new LinkedHashMap<>();
        binding.stateMapping().outputs().forEach((childKey, parentKey) -> {
            if (child.values().containsKey(childKey))
                values.put(parentKey, child.values().get(childKey));
        });
        return new WorkflowStateDelta(values);
    }

    private static String subgraphKey(WorkflowRunId parent, WorkflowNodeId node, int attempt) {
        return digest(parent.value() + '|' + node.value() + '|' + attempt);
    }

    private void executeAgentNode(MutableRun run, WorkflowNodeDefinition node) {
        DurableWorkflowAgentGateway durableAgents = (DurableWorkflowAgentGateway) agents;
        AgentRunId linked = unitOfWork.execute(() -> {
            AgentRunId created = durableAgents.start(run.id, node, run.state);
            associateAgentRun(run, created);
            return created;
        });
        failures.afterCommit(WorkflowFailurePoint.AFTER_AGENT_RUN_ASSOCIATED);
        WorkflowAgentGateway.AgentExecution result;
        try {
            result = durableAgents.await(run.id, node, run.state, linked);
            if (!linked.equals(result.agentRunId())) {
                throw error(
                        WorkflowErrorCode.NODE_EXECUTION_FAILED,
                        "agent",
                        "Agent gateway returned a different authoritative Run id");
            }
        } catch (WorkflowException exception) {
            failActive(run, exception.code(), exception.operation());
            return;
        } catch (RuntimeException exception) {
            failActive(run, WorkflowErrorCode.OUTCOME_UNKNOWN, "agent");
            return;
        }
        storeNodeResult(run, result.delta(), Optional.of(linked));
        completeScheduledNode(run);
    }

    private boolean scheduleNode(MutableRun run, WorkflowNodeDefinition node) {
        int visit = run.visits.merge(node.id(), 1, Integer::sum);
        if (visit > run.compiled.definition().limits().maximumIterationsPerNode()) {
            fail(run, WorkflowErrorCode.ITERATION_LIMIT_EXCEEDED, "execute");
            return false;
        }
        run.attempts.add(new WorkflowNodeAttempt(
                node.id(),
                visit,
                WorkflowNodeAttemptStatus.RUNNING,
                Optional.empty(),
                Optional.empty(),
                now(),
                Optional.empty()));
        save(
                run,
                List.of(event(run, WorkflowEventType.NODE_STARTED, Optional.of(node.id()), Map.of())),
                Optional.empty());
        failures.afterCommit(WorkflowFailurePoint.AFTER_ATTEMPT_SCHEDULED);
        return true;
    }

    private void storeNodeResult(MutableRun run, WorkflowStateDelta delta, Optional<AgentRunId> agentRunId) {
        WorkflowNodeAttempt active = run.activeAttempt().orElseThrow();
        replaceLastAttempt(
                run,
                new WorkflowNodeAttempt(
                        active.nodeId(),
                        active.attempt(),
                        active.status(),
                        agentRunId,
                        active.failureCode(),
                        active.startedAt(),
                        active.finishedAt()));
        run.pendingDelta = Optional.of(Objects.requireNonNull(delta, "delta must not be null"));
        save(run, List.of(), Optional.empty());
        failures.afterCommit(WorkflowFailurePoint.AFTER_NODE_RESULT_STORED);
    }

    private void associateAgentRun(MutableRun run, AgentRunId agentRunId) {
        WorkflowNodeAttempt active = run.activeAttempt().orElseThrow();
        replaceLastAttempt(
                run,
                new WorkflowNodeAttempt(
                        active.nodeId(),
                        active.attempt(),
                        active.status(),
                        Optional.of(Objects.requireNonNull(agentRunId, "agentRunId must not be null")),
                        active.failureCode(),
                        active.startedAt(),
                        active.finishedAt()));
        save(run, List.of(), Optional.empty());
    }

    private void completeScheduledNode(MutableRun run) {
        WorkflowNodeAttempt active = run.activeAttempt().orElseThrow();
        run.state = run.state.apply(run.pendingDelta.orElseThrow());
        replaceLastAttempt(
                run,
                new WorkflowNodeAttempt(
                        active.nodeId(),
                        active.attempt(),
                        WorkflowNodeAttemptStatus.COMPLETED,
                        active.agentRunId(),
                        Optional.empty(),
                        active.startedAt(),
                        Optional.of(now())));
        run.pendingDelta = Optional.empty();
        if (run.forkState.isPresent()) {
            WorkflowForkState fork = run.forkState.orElseThrow();
            run.forkState = Optional.of(new WorkflowForkState(
                    fork.forkNode(),
                    fork.baseState(),
                    fork.branchEntries(),
                    fork.branchIndex(),
                    onlySuccessor(run, active.nodeId()).target(),
                    fork.completedBranches()));
        } else {
            run.currentNode = selectSuccessor(run, active.nodeId()).target();
        }
        run.revision++;
        save(
                run,
                List.of(event(run, WorkflowEventType.NODE_COMPLETED, Optional.of(active.nodeId()), Map.of())),
                Optional.empty());
    }

    private void beginFork(MutableRun run, WorkflowNodeDefinition forkNode) {
        List<WorkflowEdge> branches = outgoing(run, forkNode.id()).stream()
                .sorted(Comparator.comparingInt(WorkflowEdge::branchOrdinal).thenComparing(WorkflowEdge::target))
                .toList();
        run.forkState = Optional.of(new WorkflowForkState(
                forkNode.id(),
                run.state,
                branches.stream().map(WorkflowEdge::target).toList(),
                0,
                branches.getFirst().target(),
                List.of()));
        save(run, List.of(), Optional.empty());
    }

    private void driveFork(MutableRun run) {
        WorkflowForkState fork = run.forkState.orElseThrow();
        WorkflowNodeDefinition cursor = node(run, fork.cursor());
        if (cursor.type() != WorkflowNodeType.JOIN_ALL) {
            if (cursor.type() != WorkflowNodeType.ACTION
                    && cursor.type() != WorkflowNodeType.AGENT_RUN
                    && cursor.type() != WorkflowNodeType.SUBGRAPH) {
                fail(run, WorkflowErrorCode.INVALID_DEFINITION, "fork");
                return;
            }
            executeScheduledNode(run, cursor);
            return;
        }
        List<WorkflowForkState.CompletedBranch> completed = new ArrayList<>(fork.completedBranches());
        completed.add(new WorkflowForkState.CompletedBranch(
                fork.branchIndex(),
                fork.branchEntries().get(fork.branchIndex()),
                difference(fork.baseState(), run.state)));
        if (fork.branchIndex() + 1 < fork.branchEntries().size()) {
            int next = fork.branchIndex() + 1;
            run.state = fork.baseState();
            run.forkState = Optional.of(new WorkflowForkState(
                    fork.forkNode(),
                    fork.baseState(),
                    fork.branchEntries(),
                    next,
                    fork.branchEntries().get(next),
                    completed));
            save(run, List.of(), Optional.empty());
            return;
        }
        List<BranchDelta> deltas = completed.stream()
                .map(branch -> new BranchDelta(branch.ordinal(), branch.entryNode(), branch.delta()))
                .toList();
        run.state = merger.merge(fork.baseState(), deltas);
        run.currentNode = cursor.id();
        run.forkState = Optional.empty();
        save(run, List.of(), Optional.empty());
    }

    private void interrupt(MutableRun run, WorkflowNodeDefinition node) {
        Instant at = now();
        run.status = WorkflowStatus.WAITING;
        run.revision++;
        run.wait =
                Optional.of(new WorkflowWait(new WorkflowWaitId(identifiers.nextValue()), node.id(), run.revision, at));
        run.checkpoint = Optional.of(new WorkflowCheckpoint(
                new WorkflowCheckpointId(identifiers.nextValue()), run.id, run.revision, node.id(), run.state, at));
        save(run, List.of(event(run, WorkflowEventType.WAITING, Optional.of(node.id()), Map.of())), Optional.empty());
        failures.afterCommit(WorkflowFailurePoint.AFTER_CHECKPOINT_STORED);
    }

    private void failActive(MutableRun run, WorkflowErrorCode code, String operation) {
        WorkflowNodeAttempt active = run.activeAttempt().orElseThrow();
        WorkflowNodeAttemptStatus status = code == WorkflowErrorCode.OUTCOME_UNKNOWN
                ? WorkflowNodeAttemptStatus.OUTCOME_UNKNOWN
                : WorkflowNodeAttemptStatus.FAILED;
        replaceLastAttempt(
                run,
                new WorkflowNodeAttempt(
                        active.nodeId(),
                        active.attempt(),
                        status,
                        active.agentRunId(),
                        Optional.of(code),
                        active.startedAt(),
                        Optional.of(now())));
        run.pendingDelta = Optional.empty();
        fail(run, code, operation);
    }

    private void fail(MutableRun run, WorkflowErrorCode code, String operation) {
        run.status = WorkflowStatus.FAILED;
        run.failure = Optional.of(new WorkflowFailure(code, operation, Optional.of(run.currentNode)));
        run.revision++;
        save(
                run,
                List.of(event(
                        run, WorkflowEventType.FAILED, Optional.of(run.currentNode), Map.of("code", code.name()))),
                Optional.empty());
    }

    private void propagateCancellation(MutableRun run) {
        run.pendingAgentCancellation.ifPresent(agentRunId -> {
            agents.cancel(agentRunId);
            run.pendingAgentCancellation = Optional.empty();
            save(run, List.of(), Optional.empty());
        });
    }

    private void save(MutableRun run, List<WorkflowEvent> events, Optional<StoredWorkflowCommand> command) {
        long expected = run.storageVersion;
        run.storageVersion++;
        unitOfWork.execute(() -> {
            store.save(expected, run.stored(), events, command);
            return null;
        });
    }

    private MutableRun requireRun(WorkflowRunId runId) {
        return store.find(Objects.requireNonNull(runId, "runId must not be null"))
                .map(this::load)
                .orElseThrow(() -> error(WorkflowErrorCode.RUN_NOT_FOUND, "find", "workflow run does not exist"));
    }

    private MutableRun load(StoredWorkflowRun stored) {
        if (stored.binding().stateCodecVersion() != binding.stateCodecVersion()) {
            throw error(WorkflowErrorCode.CODEC_MISMATCH, "recover", "workflow state codec version does not match");
        }
        if (!stored.binding().equals(binding)) {
            throw error(WorkflowErrorCode.BINDING_MISMATCH, "recover", "workflow adapter binding does not match");
        }
        CompiledWorkflowDefinition compiled =
                requireDefinition(stored.snapshot().definition(), "recover");
        validateStored(stored, compiled);
        return new MutableRun(stored, compiled);
    }

    private static void validateStored(StoredWorkflowRun stored, CompiledWorkflowDefinition compiled) {
        WorkflowRunSnapshot snapshot = stored.snapshot();
        if (!snapshot.state().schema().equals(compiled.definition().stateSchema())) {
            throw error(WorkflowErrorCode.INVALID_STATE, "recover", "persisted State Schema does not match Definition");
        }
        boolean waiting = snapshot.status() == WorkflowStatus.WAITING;
        if (waiting != snapshot.activeWait().isPresent()
                || waiting != snapshot.checkpoint().isPresent()) {
            throw error(
                    WorkflowErrorCode.PERSISTENCE_CONFLICT, "recover", "Wait and Checkpoint facts are inconsistent");
        }
        if (waiting) {
            WorkflowWait wait = snapshot.activeWait().orElseThrow();
            WorkflowCheckpoint checkpoint = snapshot.checkpoint().orElseThrow();
            if (wait.revision() != snapshot.revision()
                    || checkpoint.revision() != snapshot.revision()
                    || !checkpoint.runId().equals(snapshot.id())
                    || !checkpoint.state().equals(snapshot.state())) {
                throw error(
                        WorkflowErrorCode.PERSISTENCE_CONFLICT, "recover", "Checkpoint does not match Workflow Run");
            }
        }
        if (snapshot.status().terminal()
                && snapshot.attempts().stream()
                        .anyMatch(attempt -> attempt.status() == WorkflowNodeAttemptStatus.RUNNING)) {
            throw error(WorkflowErrorCode.PERSISTENCE_CONFLICT, "recover", "terminal Workflow has an active Attempt");
        }
    }

    private CompiledWorkflowDefinition requireDefinition(WorkflowDefinitionRef reference, String operation) {
        CompiledWorkflowDefinition definition = definitions.get(reference);
        if (definition == null) {
            throw error(WorkflowErrorCode.DEFINITION_NOT_FOUND, operation, "exact workflow definition is unavailable");
        }
        return definition;
    }

    private StoredWorkflowCommand command(
            String operation, String scope, String keyDigest, String requestDigest, MutableRun run) {
        return new StoredWorkflowCommand(operation, scope, keyDigest, requestDigest, run.id, run.snapshot());
    }

    private WorkflowRunSnapshot existing(StoredWorkflowCommand existing, String requestDigest, String operation) {
        if (!existing.requestDigest().equals(requestDigest)) {
            throw error(WorkflowErrorCode.IDEMPOTENCY_CONFLICT, operation, "idempotency key request digest differs");
        }
        if (!existing.result().status().terminal()) {
            return store.find(existing.runId()).map(StoredWorkflowRun::snapshot).orElse(existing.result());
        }
        return existing.result();
    }

    private WorkflowEvent event(
            MutableRun run, WorkflowEventType type, Optional<WorkflowNodeId> nodeId, Map<String, String> attributes) {
        Instant at = now();
        run.updatedAt = at;
        return new WorkflowEvent(run.id, ++run.eventSequence, type, nodeId, attributes, at);
    }

    private WorkflowNodeDefinition node(MutableRun run, WorkflowNodeId id) {
        return run.compiled.definition().nodes().stream()
                .filter(node -> node.id().equals(id))
                .findFirst()
                .orElseThrow(() -> error(WorkflowErrorCode.INVALID_DEFINITION, "execute", "workflow node is missing"));
    }

    private List<WorkflowEdge> outgoing(MutableRun run, WorkflowNodeId id) {
        return run.compiled.definition().edges().stream()
                .filter(edge -> edge.source().equals(id))
                .toList();
    }

    private WorkflowEdge onlySuccessor(MutableRun run, WorkflowNodeId id) {
        List<WorkflowEdge> outgoing = outgoing(run, id);
        if (outgoing.size() != 1 || outgoing.getFirst().conditionId().isPresent()) {
            throw error(WorkflowErrorCode.INVALID_DEFINITION, "execute", "node requires one unconditional successor");
        }
        return outgoing.getFirst();
    }

    private WorkflowEdge selectSuccessor(MutableRun run, WorkflowNodeId id) {
        List<WorkflowEdge> outgoing = outgoing(run, id);
        if (outgoing.size() == 1 && outgoing.getFirst().conditionId().isEmpty()) return outgoing.getFirst();
        List<WorkflowEdge> selected = outgoing.stream()
                .sorted(Comparator.comparingInt(WorkflowEdge::branchOrdinal).thenComparing(WorkflowEdge::target))
                .filter(edge -> edge.conditionId()
                        .map(value -> conditions.evaluate(value, run.state))
                        .orElse(false))
                .toList();
        if (selected.size() != 1) {
            throw error(WorkflowErrorCode.NODE_EXECUTION_FAILED, "route", "exactly one condition must match");
        }
        return selected.getFirst();
    }

    private static WorkflowStateDelta difference(WorkflowState base, WorkflowState updated) {
        Map<String, Object> changed = new LinkedHashMap<>();
        updated.values().forEach((key, value) -> {
            if (!Objects.equals(base.values().get(key), value)) changed.put(key, value);
        });
        return new WorkflowStateDelta(changed);
    }

    private static void replaceLastAttempt(MutableRun run, WorkflowNodeAttempt replacement) {
        run.attempts.set(run.attempts.size() - 1, replacement);
    }

    private Instant now() {
        return TimePrecision.toMilliseconds(timeProvider.now());
    }

    private static String canonical(Map<String, Object> values) {
        StringBuilder result = new StringBuilder();
        appendCanonical(result, new TreeMap<>(values));
        return result.toString();
    }

    private static void appendCanonical(StringBuilder target, Object value) {
        if (value instanceof Map<?, ?> map) {
            target.append('{');
            new TreeMap<>(map.entrySet().stream()
                            .collect(java.util.stream.Collectors.toMap(
                                    entry -> String.valueOf(entry.getKey()), Map.Entry::getValue)))
                    .forEach((key, item) -> {
                        target.append(key.length()).append(':').append(key).append('=');
                        appendCanonical(target, item);
                        target.append(';');
                    });
            target.append('}');
        } else if (value instanceof List<?> list) {
            target.append('[');
            list.forEach(item -> appendCanonical(target, item));
            target.append(']');
        } else {
            target.append(value.getClass().getName()).append(':').append(value);
        }
    }

    private static String digest(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static WorkflowException error(WorkflowErrorCode code, String operation, String message) {
        return new WorkflowException(code, operation, message);
    }

    private static final class MutableRun {
        private final WorkflowRunId id;
        private final CompiledWorkflowDefinition compiled;
        private final WorkflowPersistenceBinding binding;
        private final Instant createdAt;
        private final List<WorkflowNodeAttempt> attempts;
        private final Map<WorkflowNodeId, Integer> visits;
        private final Set<String> consumedSignals;
        private WorkflowState state;
        private WorkflowNodeId currentNode;
        private WorkflowStatus status;
        private long revision;
        private long storageVersion;
        private long eventSequence;
        private Optional<WorkflowWait> wait;
        private Optional<WorkflowCheckpoint> checkpoint;
        private Optional<WorkflowFailure> failure;
        private Optional<WorkflowStateDelta> pendingDelta;
        private Optional<WorkflowForkState> forkState;
        private Optional<AgentRunId> pendingAgentCancellation;
        private Optional<WorkflowParentLink> parent;
        private Optional<WorkflowSubgraphLink> activeSubgraph;
        private Instant updatedAt;

        private static MutableRun create(
                WorkflowRunId id,
                CompiledWorkflowDefinition compiled,
                WorkflowPersistenceBinding binding,
                WorkflowState state,
                Instant at) {
            return new MutableRun(
                    id, compiled, binding, state, compiled.definition().entryNode(), at);
        }

        private MutableRun(
                WorkflowRunId id,
                CompiledWorkflowDefinition compiled,
                WorkflowPersistenceBinding binding,
                WorkflowState state,
                WorkflowNodeId currentNode,
                Instant at) {
            this.id = id;
            this.compiled = compiled;
            this.binding = binding;
            this.state = state;
            this.currentNode = currentNode;
            this.createdAt = at;
            this.updatedAt = at;
            this.attempts = new ArrayList<>();
            this.visits = new LinkedHashMap<>();
            this.consumedSignals = new java.util.LinkedHashSet<>();
            this.status = WorkflowStatus.RUNNING;
            this.revision = 1;
            this.storageVersion = 1;
            this.wait = Optional.empty();
            this.checkpoint = Optional.empty();
            this.failure = Optional.empty();
            this.pendingDelta = Optional.empty();
            this.forkState = Optional.empty();
            this.pendingAgentCancellation = Optional.empty();
            this.parent = Optional.empty();
            this.activeSubgraph = Optional.empty();
        }

        private MutableRun(StoredWorkflowRun stored, CompiledWorkflowDefinition compiled) {
            WorkflowRunSnapshot snapshot = stored.snapshot();
            this.id = snapshot.id();
            this.compiled = compiled;
            this.binding = stored.binding();
            this.state = snapshot.state();
            this.currentNode =
                    snapshot.currentNode().orElse(compiled.definition().entryNode());
            this.createdAt = snapshot.createdAt();
            this.updatedAt = snapshot.updatedAt();
            this.attempts = new ArrayList<>(snapshot.attempts());
            this.visits = new LinkedHashMap<>();
            stored.nodeVisits().forEach((key, value) -> visits.put(new WorkflowNodeId(key), value));
            this.consumedSignals = new java.util.LinkedHashSet<>(stored.consumedSignalIds());
            this.status = snapshot.status();
            this.revision = snapshot.revision();
            this.storageVersion = stored.storageVersion();
            this.eventSequence = stored.eventSequence();
            this.wait = snapshot.activeWait();
            this.checkpoint = snapshot.checkpoint();
            this.failure = snapshot.failure();
            this.pendingDelta = stored.pendingDelta();
            this.forkState = stored.forkState();
            this.pendingAgentCancellation = stored.pendingAgentCancellation();
            this.parent = snapshot.parent();
            this.activeSubgraph = snapshot.activeSubgraph();
        }

        private Optional<WorkflowNodeAttempt> activeAttempt() {
            if (attempts.isEmpty()) return Optional.empty();
            WorkflowNodeAttempt latest = attempts.getLast();
            return latest.status() == WorkflowNodeAttemptStatus.RUNNING ? Optional.of(latest) : Optional.empty();
        }

        private WorkflowRunSnapshot snapshot() {
            return new WorkflowRunSnapshot(
                    id,
                    compiled.reference(),
                    status,
                    revision,
                    state,
                    Optional.ofNullable(currentNode),
                    wait,
                    checkpoint,
                    failure,
                    attempts,
                    parent,
                    activeSubgraph,
                    createdAt,
                    updatedAt);
        }

        private StoredWorkflowRun stored() {
            Map<String, Integer> persistedVisits = new LinkedHashMap<>();
            visits.forEach((key, value) -> persistedVisits.put(key.value(), value));
            return new StoredWorkflowRun(
                    snapshot(),
                    binding,
                    storageVersion,
                    eventSequence,
                    persistedVisits,
                    consumedSignals,
                    pendingDelta,
                    forkState,
                    pendingAgentCancellation);
        }
    }
}
