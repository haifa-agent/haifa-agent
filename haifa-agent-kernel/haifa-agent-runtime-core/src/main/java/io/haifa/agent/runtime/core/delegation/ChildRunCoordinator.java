package io.haifa.agent.runtime.core.delegation;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.content.ArtifactRefPart;
import io.haifa.agent.core.content.AssetRefPart;
import io.haifa.agent.core.content.ContentPart;
import io.haifa.agent.core.content.ImageUrlContentPart;
import io.haifa.agent.core.content.StoredAudioContentPart;
import io.haifa.agent.core.content.StoredImageContentPart;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.content.ToolCallPart;
import io.haifa.agent.core.content.ToolResultPart;
import io.haifa.agent.core.error.AgentError;
import io.haifa.agent.core.error.AgentErrorCode;
import io.haifa.agent.core.message.AgentMessage;
import io.haifa.agent.core.message.AgentMessageId;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.message.MessageStatus;
import io.haifa.agent.core.message.MessageVisibility;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.run.AgentRunUsageDelta;
import io.haifa.agent.core.run.RunTerminationReason;
import io.haifa.agent.core.session.AgentSession;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.session.SessionScope;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.runtime.api.AgentRunRequest;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.runtime.core.attempt.AgentRunExecutionAttempt;
import io.haifa.agent.runtime.core.attempt.ExecutionAttemptId;
import io.haifa.agent.runtime.core.attempt.ExecutionAttemptStatus;
import io.haifa.agent.runtime.core.bootstrap.BootstrapResult;
import io.haifa.agent.runtime.core.bootstrap.DefinitionResolver;
import io.haifa.agent.runtime.core.bootstrap.ProfileResolver;
import io.haifa.agent.runtime.core.bootstrap.ResolvedDefinition;
import io.haifa.agent.runtime.core.bootstrap.ResolvedProfile;
import io.haifa.agent.runtime.core.bootstrap.RunBootstrapper;
import io.haifa.agent.runtime.core.bootstrap.RuntimeConfigurationSnapshot;
import io.haifa.agent.runtime.core.control.CancellationObservedException;
import io.haifa.agent.runtime.core.control.RunControlDirective;
import io.haifa.agent.runtime.core.control.RunControlRegistry;
import io.haifa.agent.runtime.core.control.RunControlSignal;
import io.haifa.agent.runtime.core.execution.AttemptExecutor;
import io.haifa.agent.runtime.core.execution.ExecutionOwnershipPort;
import io.haifa.agent.runtime.core.execution.ExecutionScheduler;
import io.haifa.agent.runtime.core.lifecycle.RunTransitionCoordinator;
import io.haifa.agent.runtime.core.recovery.InterruptedRunSettler;
import io.haifa.agent.runtime.core.storage.AgentSessionRepository;
import io.haifa.agent.runtime.core.storage.ExecutionAttemptRepository;
import io.haifa.agent.runtime.core.storage.OutboxMessage;
import io.haifa.agent.runtime.core.storage.RunStateRepository;
import io.haifa.agent.runtime.core.storage.RuntimeEventAppender;
import io.haifa.agent.runtime.core.storage.RuntimeOutboxPublisher;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import io.haifa.agent.runtime.core.storage.RuntimeUnitOfWork;
import io.haifa.agent.runtime.core.storage.SessionMessageDraft;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Process-local {@link DelegationPort}: a child is an ordinary {@link AgentRun} executed by the same Runtime on the
 * configured scheduler.
 *
 * <p>The parent thread owns its delegation batch (Actor discipline): it creates each child lazily when a
 * {@code maxParallelChildren} and process slot is free, so a request waiting for a slot has no Run yet and is
 * dropped when the parent stops. The child Run ID is derived from the parent Run ID and the Tool Call ID, so a
 * retried Tool Call re-attaches to its existing child instead of creating another one. Child state is read only
 * from the Run repository; this class keeps no status copy.
 *
 * <p>A process slot belongs to the child's execution, not to the parent's observation of it: it is taken before the
 * child is created and returned only once the child Run is terminal and none of its execution tasks (the first one
 * and any resumed after an approval) is still running. A parent that stops watching therefore never frees capacity
 * that a still-executing child occupies. A child's terminal fact reaches the parent's event feed from the child's
 * own terminal transition ({@link #projectTerminal(AgentRun)}), whether or not the parent is still waiting.
 */
public final class ChildRunCoordinator implements DelegationPort {
    public static final int DEFAULT_MAX_CONCURRENT_CHILD_RUNS = 3;
    private static final long POLL_MILLIS = 250;
    private static final long STOP_SETTLE_MILLIS = 2_000;
    private static final String DELEGATION_TOOL_CALL_ID = "delegationToolCallId";

    private final RunStateRepository runs;
    private final AgentSessionRepository sessions;
    private final ExecutionAttemptRepository attempts;
    private final RuntimeStateRepository state;
    private final RuntimeUnitOfWork unitOfWork;
    private final RunTransitionCoordinator transitions;
    private final RuntimeEventAppender events;
    private final RuntimeOutboxPublisher outbox;
    private final RunControlRegistry controls;
    private final ExecutionScheduler scheduler;
    private final RunBootstrapper bootstrapper;
    private final DefinitionResolver definitions;
    private final ProfileResolver profiles;
    private final ExecutionOwnershipPort ownership;
    private final InterruptedRunSettler settler;
    private final IdentifierGenerator ids;
    private final TimeProvider time;
    private final Semaphore processSlots;
    private final Object monitor = new Object();
    /** Children admitted by this process that still hold a process slot; guarded by itself for task counts. */
    private final Map<AgentRunId, ChildSlot> slots = new ConcurrentHashMap<>();

    private final ExecutionScheduler trackingScheduler = new ExecutionScheduler() {
        @Override
        public void submit(AgentRunId runId, Runnable task) {
            submitTracked(runId, task);
        }

        @Override
        public void cancel(AgentRunId runId) {
            scheduler.cancel(runId);
        }
    };
    private long wakeupSequence;
    private volatile AttemptExecutor executor;

    public ChildRunCoordinator(
            RunStateRepository runs,
            AgentSessionRepository sessions,
            ExecutionAttemptRepository attempts,
            RuntimeStateRepository state,
            RuntimeUnitOfWork unitOfWork,
            RunTransitionCoordinator transitions,
            RuntimeEventAppender events,
            RuntimeOutboxPublisher outbox,
            RunControlRegistry controls,
            ExecutionScheduler scheduler,
            RunBootstrapper bootstrapper,
            DefinitionResolver definitions,
            ProfileResolver profiles,
            ExecutionOwnershipPort ownership,
            InterruptedRunSettler settler,
            IdentifierGenerator ids,
            TimeProvider time,
            int maxConcurrentChildRuns) {
        this.runs = Objects.requireNonNull(runs);
        this.sessions = Objects.requireNonNull(sessions);
        this.attempts = Objects.requireNonNull(attempts);
        this.state = Objects.requireNonNull(state);
        this.unitOfWork = Objects.requireNonNull(unitOfWork);
        this.transitions = Objects.requireNonNull(transitions);
        this.events = Objects.requireNonNull(events);
        this.outbox = Objects.requireNonNull(outbox);
        this.controls = Objects.requireNonNull(controls);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.bootstrapper = Objects.requireNonNull(bootstrapper);
        this.definitions = Objects.requireNonNull(definitions);
        this.profiles = Objects.requireNonNull(profiles);
        this.ownership = Objects.requireNonNull(ownership);
        this.settler = Objects.requireNonNull(settler);
        this.ids = Objects.requireNonNull(ids);
        this.time = Objects.requireNonNull(time);
        if (maxConcurrentChildRuns < 1) throw new IllegalArgumentException("maxConcurrentChildRuns must be positive");
        this.processSlots = new Semaphore(maxConcurrentChildRuns, true);
    }

    /** Completes assembly; the attempt executor is created after the delegation port it depends on. */
    public void bind(AttemptExecutor attemptExecutor) {
        Objects.requireNonNull(attemptExecutor, "attemptExecutor must not be null");
        if (executor != null) throw new IllegalStateException("child run coordinator is already bound");
        executor = attemptExecutor;
    }

    /**
     * Wakes parents waiting for children and returns the slot of a child that has just settled while none of its
     * execution tasks runs; registered as a committed Run-change listener.
     */
    public void onRunChanged(AgentRunSnapshot snapshot) {
        if (snapshot.status().isTerminal()) releaseIfSettled(snapshot.runId());
        signal();
    }

    /**
     * The scheduler the Runtime must use for every execution task it submits. Tasks of children admitted here keep
     * their process slot while they run (including a child resumed after approval); other Runs pass through.
     */
    public ExecutionScheduler scheduler() {
        return trackingScheduler;
    }

    /**
     * Appends the child's terminal {@code child.run.*} event to its parent's feed. Runs inside the child's terminal
     * transition, so the parent sees exactly one terminal event per child even when it stopped waiting before the
     * child ended, or when the child was settled by recovery. Runs not created by delegation are ignored.
     */
    public void projectTerminal(AgentRun child) {
        Optional<AgentRunId> parentRunId = child.parentRunId();
        if (parentRunId.isEmpty() || !child.status().isTerminal()) return;
        Optional<ToolCallId> toolCallId = sessions.find(child.sessionId())
                .map(session -> session.metadata().get(DELEGATION_TOOL_CALL_ID))
                .filter(String.class::isInstance)
                .map(value -> new ToolCallId((String) value));
        if (toolCallId.isEmpty()) return;
        appendChildEvent(parentRunId.orElseThrow(), toolCallId.orElseThrow(), child, terminalEventType(child.status()));
    }

    /** Deterministic child Run identity: one Tool Call can create at most one child Run. */
    public static AgentRunId childRunId(AgentRunId parentRunId, ToolCallId toolCallId) {
        return new AgentRunId("child-run-" + digest("child-run-v1", parentRunId, toolCallId));
    }

    static AgentSessionId childSessionId(AgentRunId parentRunId, ToolCallId toolCallId) {
        return new AgentSessionId("child-session-" + digest("child-session-v1", parentRunId, toolCallId));
    }

    @Override
    public void executeChildren(AgentRun parent, List<ChildRunRequest> requests, Listener listener) {
        Objects.requireNonNull(parent, "parent must not be null");
        Objects.requireNonNull(requests, "requests must not be null");
        Objects.requireNonNull(listener, "listener must not be null");
        if (executor == null) throw new IllegalStateException("child run coordinator is not bound");
        Deque<ChildRunRequest> pending = new ArrayDeque<>(requests);
        Map<AgentRunId, Active> active = new LinkedHashMap<>();
        int maxParallel = Math.max(1, parent.limits().maxParallelChildren());
        try {
            while (!pending.isEmpty() || !active.isEmpty()) {
                long observed = wakeups();
                RunControlDirective directive = controls.directive(parent.id());
                if (directive.signal().stopsExecution()) {
                    stop(parent, pending, active, listener);
                    throw directive.terminationReason().isPresent()
                            ? new CancellationObservedException(directive)
                            : new CancellationObservedException(directive.signal());
                }
                Instant now = time.now();
                long remaining = parent.limits().maxWallTimeMillis() - activeElapsed(parent, now);
                if (remaining <= 0) {
                    stop(parent, pending, active, listener);
                    throw new CancellationObservedException(RunControlSignal.TIMEOUT);
                }
                collect(parent, active, listener, now, true);
                dispatch(parent, pending, active, listener, maxParallel);
                if (pending.isEmpty() && active.isEmpty()) break;
                awaitWakeup(observed, Math.min(POLL_MILLIS, remaining));
            }
        } catch (CancellationObservedException stopped) {
            throw stopped;
        } catch (RuntimeException failure) {
            stop(parent, pending, active, listener);
            throw failure;
        }
    }

    @Override
    public boolean hasPendingChildren(AgentRun parent) {
        return runs.children(parent.id()).stream()
                .anyMatch(child -> !child.status().isTerminal());
    }

    @Override
    public void terminateChildren(AgentRun parent) {
        for (AgentRun child : runs.children(parent.id())) terminate(child);
        signal();
    }

    private void collect(
            AgentRun parent, Map<AgentRunId, Active> active, Listener listener, Instant now, boolean enforceTimeouts) {
        Iterator<Map.Entry<AgentRunId, Active>> iterator = active.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<AgentRunId, Active> entry = iterator.next();
            Active value = entry.getValue();
            Optional<AgentRun> found = runs.find(entry.getKey());
            if (found.isEmpty()) {
                iterator.remove();
                listener.rejected(value.request().toolCallId(), "The child run is unavailable.");
                continue;
            }
            AgentRun child = found.orElseThrow();
            if (child.status().isTerminal()) {
                // The slot and the parent's child.run.* event follow the child's own lifecycle, not this observation.
                iterator.remove();
                controls.clear(child.id());
                listener.terminal(value.request().toolCallId(), child);
                continue;
            }
            if (enforceTimeouts && !value.timeoutRequested() && exceededOwnWallTime(child, now)) {
                controls.requestTimeout(
                        child.id(),
                        new RunTerminationReason("WALL_TIME_EXCEEDED", "Child run wall-time limit exceeded"));
                scheduler.cancel(child.id());
                entry.setValue(value.withTimeoutRequested());
            }
        }
    }

    private void dispatch(
            AgentRun parent,
            Deque<ChildRunRequest> pending,
            Map<AgentRunId, Active> active,
            Listener listener,
            int maxParallel) {
        while (!pending.isEmpty() && active.size() < maxParallel) {
            ChildRunRequest next = pending.peek();
            AgentRunId childId = childRunId(parent.id(), next.toolCallId());
            if (runs.find(childId).isPresent()) {
                // A retried Tool Call re-attaches to its existing child instead of creating a second one.
                pending.poll();
                active.put(childId, new Active(next, false));
                continue;
            }
            ChildSlot slot = admit(childId);
            if (slot == null) return;
            pending.poll();
            boolean committed = false;
            try {
                committed = launch(parent, next, childId);
                active.put(childId, new Active(next, false));
            } catch (ChildRejectedException rejected) {
                listener.rejected(next.toolCallId(), rejected.getMessage());
            } finally {
                // Nothing was committed, so no child exists that could ever run under this slot.
                if (!committed) forfeit(childId, slot);
            }
        }
    }

    /** Returns {@code true} when this call committed the child, which then owns the slot until it settles. */
    private boolean launch(AgentRun parent, ChildRunRequest request, AgentRunId childId) {
        RuntimeConfigurationSnapshot parentConfiguration = state.configuration(parent.configurationSnapshot())
                .orElseThrow(() -> new IllegalStateException("parent configuration snapshot is unavailable"));
        var childDefinitionId = request.childDefinitionId();
        if (!parentConfiguration.allowedChildAgents().contains(childDefinitionId)) {
            throw new ChildRejectedException(
                    "Child agent '" + childDefinitionId.value() + "' is not allowed; choose one of the listed agents.");
        }
        if (childDefinitionId.equals(parent.agentDefinitionId())) {
            throw new ChildRejectedException("An agent cannot delegate to itself.");
        }
        if (parent.depth() >= DelegationTool.MAX_DELEGATION_DEPTH
                || parent.depth() >= parent.limits().maxDepth()) {
            throw new ChildRejectedException("The delegation depth limit has been reached.");
        }
        ResolvedDefinition declared;
        try {
            declared = definitions.resolve(childDefinitionId, Optional.empty());
        } catch (RuntimeException unavailable) {
            throw new ChildRejectedException("Child agent '" + childDefinitionId.value() + "' is unavailable.");
        }
        if (!declared.id().equals(childDefinitionId)) {
            throw new ChildRejectedException("Child agent '" + childDefinitionId.value() + "' is unavailable.");
        }
        // Child capability = what the parent may delegate intersected with the child's own allowlist.
        Set<String> tools = intersection(declared.allowedTools(), parentConfiguration.allowedTools());
        Set<String> skills = intersection(declared.allowedSkills(), parentConfiguration.allowedSkills());
        ResolvedDefinition definition = declared.narrowedForChild(tools, skills);
        ResolvedProfile profile;
        try {
            profile = declared.childRunProfileId()
                    .map(profileId -> profiles.resolve(profileId, parentConfiguration.overrides()))
                    .orElseGet(() -> inheritedProfile(parentConfiguration));
        } catch (RuntimeException unavailable) {
            throw new ChildRejectedException(
                    "The run profile of child agent '" + childDefinitionId.value() + "' is unavailable.");
        }
        profile = narrowedProfile(profile, tools);
        if (profile.limits().maxDepth() < parent.depth() + 1) {
            throw new ChildRejectedException("The run profile of child agent '" + childDefinitionId.value()
                    + "' does not permit a delegated run.");
        }
        AgentSessionId sessionId = childSessionId(parent.id(), request.toolCallId());
        List<ContentPart> inheritedReferences = inheritedReferences(parent);
        List<ContentPart> brief = new ArrayList<>();
        brief.add(new TextPart(request.brief(), "plain"));
        brief.addAll(inheritedReferences);
        AgentRunRequest childRequest = new AgentRunRequest(
                "delegation:" + childId.value(),
                childDefinitionId,
                Optional.of(definition.version()),
                profile.id(),
                sessionId,
                parent.project(),
                request.objective(),
                inheritedReferences,
                parentConfiguration.overrides());
        BootstrapResult bootstrap;
        try {
            bootstrap = bootstrapper.bootstrapChild(parent, childId, childRequest, definition, profile);
        } catch (RuntimeException invalid) {
            throw new ChildRejectedException(
                    "Child agent '" + childDefinitionId.value() + "' cannot be configured for this run.");
        }
        AgentRun child = bootstrap.run();
        AgentRunExecutionAttempt attempt = new AgentRunExecutionAttempt(
                new ExecutionAttemptId(ids.nextValue()), childId, 1, time.now(), Optional.empty());
        ResolvedProfile frozenProfile = profile;
        boolean created = unitOfWork.execute(() -> {
            if (runs.find(childId).isPresent()) return false;
            Instant now = time.now();
            sessions.insert(AgentSession.open(
                    sessionId,
                    parent.tenant(),
                    parent.principal(),
                    parent.project().orElse(null),
                    SessionScope.EPHEMERAL,
                    now,
                    Map.of(
                            "parentRunId",
                            parent.id().value(),
                            DELEGATION_TOOL_CALL_ID,
                            request.toolCallId().value())));
            state.saveConfiguration(bootstrap.configuration());
            runs.insert(child);
            state.appendSessionMessage(new SessionMessageDraft(
                    new AgentMessageId(ids.nextValue()),
                    sessionId,
                    Optional.of(childId),
                    Optional.empty(),
                    MessageRole.USER,
                    MessageStatus.COMPLETED,
                    MessageVisibility.USER_VISIBLE,
                    List.copyOf(brief),
                    Map.of("delegatedByRunId", parent.id().value()),
                    now));
            append(
                    childId,
                    "run.created",
                    Map.of(
                            "definitionVersion", definition.version().toString(),
                            "version", child.version(),
                            "parentRunId", parent.id().value()),
                    Map.of("profileVersion", frozenProfile.version()));
            transitions.queued(child);
            attempts.insert(attempt);
            transitions.usage(parent, new AgentRunUsageDelta(0, 0, 0, 0, 0, 1, 0, 0));
            appendChildEvent(parent.id(), request.toolCallId(), child, "child.run.started");
            return true;
        });
        if (!created) return false;
        try {
            submitTracked(childId, () -> executor.execute(child, attempt));
        } catch (RuntimeException rejected) {
            settleUnscheduled(childId);
        } catch (Error failure) {
            settleUnscheduled(childId);
            throw failure;
        }
        return true;
    }

    /**
     * Settles a committed child that the scheduler refused, so no QUEUED child is left without an executor. The
     * child fails (nobody asked to cancel it; the Runtime could not run it), its attempt is closed, the terminal
     * transition projects {@code child.run.failed} to the parent and returns the slot, and the parent's Tool Result
     * reports the failure when it observes the terminal child.
     */
    private void settleUnscheduled(AgentRunId childId) {
        unitOfWork.execute(() -> {
            AgentRun current = runs.find(childId).orElseThrow();
            if (current.status().isTerminal()) return null;
            attempts.activeFor(childId).ifPresent(stored -> {
                long expected = stored.version();
                stored.finish(ExecutionAttemptStatus.FAILED, time.now(), Optional.empty());
                attempts.save(stored, expected);
            });
            transitions.failed(
                    current,
                    new AgentError(
                            AgentErrorCode.RUNTIME_EXECUTION_FAILED,
                            Map.of("reason", "CHILD_NOT_SCHEDULED", "automaticResume", false),
                            ids.nextValue(),
                            time.now()));
            return null;
        });
        releaseIfSettled(childId);
    }

    /**
     * Requests termination of started children, drops requests that never started, then briefly collects terminal
     * children for their Tool Results. Children still executing afterwards keep their slots until their tasks end,
     * and their terminal events still reach the parent feed through {@link #projectTerminal(AgentRun)}.
     */
    private void stop(
            AgentRun parent, Deque<ChildRunRequest> pending, Map<AgentRunId, Active> active, Listener listener) {
        while (!pending.isEmpty()) listener.notStarted(pending.poll().toolCallId());
        for (AgentRunId childId : List.copyOf(active.keySet()))
            runs.find(childId).ifPresent(this::terminate);
        long deadline = System.nanoTime() + STOP_SETTLE_MILLIS * 1_000_000L;
        while (!active.isEmpty()) {
            long observed = wakeups();
            collect(parent, active, listener, time.now(), false);
            long remainingMillis = (deadline - System.nanoTime()) / 1_000_000L;
            if (active.isEmpty() || remainingMillis <= 0) break;
            awaitWakeup(observed, Math.min(POLL_MILLIS, remainingMillis));
        }
    }

    private void terminate(AgentRun child) {
        if (child.status().isTerminal()) return;
        AgentRunId childId = child.id();
        Optional<AgentRunExecutionAttempt> activeAttempt = attempts.activeFor(childId);
        boolean executingHere = executing(childId)
                || activeAttempt
                        .filter(value -> value.workerId().isPresent() && ownership.stillOwned(value))
                        .isPresent();
        switch (child.status()) {
            case RUNNING, SUSPENDING -> {
                if (executingHere) {
                    controls.reportParentCancelled(childId);
                    scheduler.cancel(childId);
                } else if (activeAttempt.isPresent()) {
                    // The executor of this child disappeared: settle it as interrupted, never replay it.
                    unitOfWork.execute(() -> {
                        AgentRun current = runs.find(childId).orElse(child);
                        if (current.status().isTerminal()) return null;
                        AgentError error = settler.settle(current, activeAttempt.orElseThrow());
                        transitions.failed(current, error);
                        return null;
                    });
                } else {
                    transitions.failed(
                            child,
                            new AgentError(
                                    AgentErrorCode.RUNTIME_EXECUTION_INTERRUPTED,
                                    Map.of("reason", "EXECUTOR_LOST", "automaticResume", false),
                                    ids.nextValue(),
                                    time.now()));
                }
            }
            case PENDING, QUEUED -> {
                if (executingHere) {
                    controls.reportParentCancelled(childId);
                    scheduler.cancel(childId);
                } else {
                    cancelDirectly(childId, activeAttempt);
                }
            }
            case COMPLETING -> {
                // Completion commits atomically; the child becomes terminal without further action.
            }
            default -> cancelDirectly(childId, activeAttempt);
        }
    }

    private void cancelDirectly(AgentRunId childId, Optional<AgentRunExecutionAttempt> activeAttempt) {
        unitOfWork.execute(() -> {
            AgentRun current = runs.find(childId).orElseThrow();
            if (current.status().isTerminal()) return null;
            transitions.cancelled(current, new RunTerminationReason("PARENT_CANCELLED", "The parent run stopped"));
            activeAttempt.ifPresent(attempt -> {
                long expected = attempt.version();
                attempt.finish(ExecutionAttemptStatus.CANCELLED, time.now(), Optional.empty());
                attempts.save(attempt, expected);
            });
            return null;
        });
    }

    private boolean exceededOwnWallTime(AgentRun child, Instant now) {
        if (child.status() != AgentRunStatus.RUNNING
                && child.status() != AgentRunStatus.SUSPENDING
                && child.status() != AgentRunStatus.QUEUED) {
            return false;
        }
        return activeElapsed(child, now) > child.limits().maxWallTimeMillis();
    }

    private static long activeElapsed(AgentRun run, Instant now) {
        Instant at = now.isBefore(run.updatedAt()) ? run.updatedAt() : now;
        try {
            return run.activeElapsedMillis(at);
        } catch (IllegalArgumentException concurrentTransition) {
            return 0;
        }
    }

    /**
     * The non-text references the parent Run was started with ({@code AgentRunRequest.inputs}: uploaded images and
     * audio, image URLs, asset and artifact references), copied as the immutable values they are. Free text is not
     * inherited; the parent passes what matters in the {@code task} brief. The {@code task} arguments are text only,
     * so the model can neither add a reference nor broaden one.
     */
    private List<ContentPart> inheritedReferences(AgentRun parent) {
        return state.messages(parent.id()).stream()
                .filter(message -> message.role() == MessageRole.USER)
                .min(Comparator.comparingLong(AgentMessage::sequence))
                .map(message -> message.contents().stream()
                        .filter(ChildRunCoordinator::isInheritedReference)
                        .toList())
                .orElse(List.of());
    }

    private static boolean isInheritedReference(ContentPart part) {
        return switch (part) {
            case AssetRefPart asset -> true;
            case ArtifactRefPart artifact -> true;
            case ImageUrlContentPart image -> true;
            case StoredImageContentPart image -> true;
            case StoredAudioContentPart audio -> true;
            case TextPart text -> false;
            case ToolCallPart call -> false;
            case ToolResultPart result -> false;
        };
    }

    private static ResolvedProfile inheritedProfile(RuntimeConfigurationSnapshot parent) {
        return new ResolvedProfile(
                parent.profileId(),
                parent.profileVersion(),
                parent.runType(),
                parent.budget(),
                parent.limits(),
                parent.model(),
                Map.of(),
                parent.modelRequestOptions(),
                Optional.empty());
    }

    private static ResolvedProfile narrowedProfile(ResolvedProfile profile, Set<String> tools) {
        if (profile.allowedTools().isEmpty()) return profile;
        return new ResolvedProfile(
                profile.id(),
                profile.version(),
                profile.runType(),
                profile.budget(),
                profile.limits(),
                profile.model(),
                profile.capabilities(),
                profile.modelRequestOptions(),
                profile.allowedTools().map(allowed -> intersection(allowed, tools)));
    }

    private static Set<String> intersection(Set<String> left, Set<String> right) {
        return left.stream().filter(right::contains).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private void appendChildEvent(AgentRunId parentRunId, ToolCallId toolCallId, AgentRun child, String type) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("childRunId", child.id().value());
        data.put("toolCallId", toolCallId.value());
        data.put("childAgent", child.agentDefinitionId().value());
        data.put("status", child.status().name());
        data.put("reasonCode", reasonCode(child));
        append(parentRunId, type, Map.copyOf(data), Map.copyOf(data));
    }

    private void append(AgentRunId runId, String type, Map<String, Object> data, Map<String, Object> outboxData) {
        var event = events.append(runId, type, data, time.now());
        outbox.append(new OutboxMessage(
                event.eventId(),
                event.runId(),
                event.sequence(),
                event.type(),
                OutboxMessage.CURRENT_SCHEMA_VERSION,
                outboxData,
                event.occurredAt()));
    }

    private static String terminalEventType(AgentRunStatus status) {
        return switch (status) {
            case COMPLETED -> "child.run.completed";
            case FAILED -> "child.run.failed";
            case CANCELLED -> "child.run.cancelled";
            case TIMEOUT -> "child.run.timed-out";
            default -> throw new IllegalArgumentException("child run status is not terminal: " + status);
        };
    }

    private static String reasonCode(AgentRun child) {
        return switch (child.status()) {
            case COMPLETED ->
                child.result().map(result -> result.outcome().name()).orElse("NONE");
            case FAILED -> child.error().map(error -> error.code().wireCode()).orElse("NONE");
            case CANCELLED, TIMEOUT ->
                child.terminationReason().map(RunTerminationReason::code).orElse("NONE");
            default -> "NONE";
        };
    }

    /** Takes a process slot for a child about to be created, or returns {@code null} when none is free. */
    private ChildSlot admit(AgentRunId childId) {
        if (!processSlots.tryAcquire()) return null;
        ChildSlot slot = new ChildSlot();
        if (slots.putIfAbsent(childId, slot) != null) {
            processSlots.release();
            return null;
        }
        return slot;
    }

    /** Returns the slot of a child that was never committed. */
    private void forfeit(AgentRunId childId, ChildSlot slot) {
        if (slots.remove(childId, slot)) {
            processSlots.release();
            signal();
        }
    }

    private void submitTracked(AgentRunId runId, Runnable task) {
        ChildSlot slot = slots.get(runId);
        if (slot == null) {
            scheduler.submit(runId, task);
            return;
        }
        synchronized (slot) {
            slot.tasks++;
        }
        try {
            scheduler.submit(runId, () -> {
                try {
                    task.run();
                } finally {
                    taskEnded(runId, slot);
                }
            });
        } catch (RuntimeException | Error failure) {
            taskEnded(runId, slot);
            throw failure;
        }
    }

    private void taskEnded(AgentRunId runId, ChildSlot slot) {
        synchronized (slot) {
            slot.tasks--;
        }
        releaseIfSettled(runId);
        signal();
    }

    private boolean executing(AgentRunId childId) {
        ChildSlot slot = slots.get(childId);
        if (slot == null) return false;
        synchronized (slot) {
            return slot.tasks > 0;
        }
    }

    /**
     * Returns the child's slot once the child is terminal and no execution task of it is running. Both the task's
     * {@code finally} and the committed terminal transition call this, so whichever happens last frees the slot. The
     * status is read outside the slot lock: a terminal status is final and never gains another task.
     */
    private void releaseIfSettled(AgentRunId childId) {
        ChildSlot slot = slots.get(childId);
        if (slot == null) return;
        boolean settled =
                runs.find(childId).map(run -> run.status().isTerminal()).orElse(true);
        if (!settled) return;
        synchronized (slot) {
            if (slot.tasks > 0 || slot.released) return;
            slot.released = true;
        }
        if (slots.remove(childId, slot)) {
            processSlots.release();
            signal();
        }
    }

    private void signal() {
        synchronized (monitor) {
            wakeupSequence++;
            monitor.notifyAll();
        }
    }

    private long wakeups() {
        synchronized (monitor) {
            return wakeupSequence;
        }
    }

    private void awaitWakeup(long observed, long millis) {
        synchronized (monitor) {
            if (wakeupSequence != observed) return;
            try {
                monitor.wait(Math.max(1, millis));
            } catch (InterruptedException interrupted) {
                // The interrupt flag is cleared; the next pass re-reads the authoritative control signal.
            }
        }
    }

    private static String digest(String purpose, AgentRunId parentRunId, ToolCallId toolCallId) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((purpose + "|" + parentRunId.value() + "|" + toolCallId.value())
                            .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 32);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    private record Active(ChildRunRequest request, boolean timeoutRequested) {
        private Active withTimeoutRequested() {
            return new Active(request, true);
        }
    }

    /** One admitted child's process slot and the number of its execution tasks currently submitted or running. */
    private static final class ChildSlot {
        private int tasks;
        private boolean released;
    }

    private static final class ChildRejectedException extends RuntimeException {
        private ChildRejectedException(String safeMessage) {
            super(safeMessage, null, false, false);
        }
    }
}
