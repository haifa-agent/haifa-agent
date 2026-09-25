package io.haifa.agent.runtime.core.lifecycle;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.error.AgentError;
import io.haifa.agent.core.reference.InteractionRequestRef;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunResult;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.run.AgentRunUsageDelta;
import io.haifa.agent.core.run.RunTerminationReason;
import io.haifa.agent.runtime.api.AgentRunListener;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.runtime.core.input.InMemoryRunInputPort;
import io.haifa.agent.runtime.core.input.RunInputPort;
import io.haifa.agent.runtime.core.input.RunInputReasonCodes;
import io.haifa.agent.runtime.core.input.RunInputRecord;
import io.haifa.agent.runtime.core.storage.OutboxMessage;
import io.haifa.agent.runtime.core.storage.RunStateRepository;
import io.haifa.agent.runtime.core.storage.RuntimeEvent;
import io.haifa.agent.runtime.core.storage.RuntimeEventAppender;
import io.haifa.agent.runtime.core.storage.RuntimeOutboxPublisher;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import io.haifa.agent.runtime.core.storage.RuntimeUnitOfWork;
import io.haifa.agent.runtime.core.storage.SessionMessageDraft;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Serializes orchestration while delegating all lifecycle legality to the Core aggregate. */
public final class RunTransitionCoordinator {
    private final RunStateRepository runs;
    private final RuntimeStateRepository state;
    private final RuntimeEventAppender events;
    private final RuntimeOutboxPublisher outbox;
    private final IdentifierGenerator ids;
    private final TimeProvider time;
    private final RunAwaiter awaiter;
    private final RuntimeUnitOfWork unitOfWork;
    private final RunInputPort runInputs;
    private final Map<AgentRunId, Object> locks = new ConcurrentHashMap<>();
    private final List<AgentRunListener> listeners = new CopyOnWriteArrayList<>();

    public RunTransitionCoordinator(
            RunStateRepository runs,
            RuntimeStateRepository state,
            RuntimeEventAppender events,
            RuntimeOutboxPublisher outbox,
            IdentifierGenerator ids,
            TimeProvider time,
            RunAwaiter awaiter,
            RuntimeUnitOfWork unitOfWork) {
        this(runs, state, events, outbox, ids, time, awaiter, unitOfWork, new InMemoryRunInputPort());
    }

    /**
     * Creates a coordinator that settles steer input in the same Unit of Work as every terminal transition, so an
     * accepted input is either applied at a safe point or observably rejected, never silently dropped.
     */
    public RunTransitionCoordinator(
            RunStateRepository runs,
            RuntimeStateRepository state,
            RuntimeEventAppender events,
            RuntimeOutboxPublisher outbox,
            IdentifierGenerator ids,
            TimeProvider time,
            RunAwaiter awaiter,
            RuntimeUnitOfWork unitOfWork,
            RunInputPort runInputs) {
        this.runs = Objects.requireNonNull(runs);
        this.state = Objects.requireNonNull(state);
        this.events = Objects.requireNonNull(events);
        this.outbox = Objects.requireNonNull(outbox);
        this.ids = Objects.requireNonNull(ids);
        this.time = Objects.requireNonNull(time);
        this.awaiter = Objects.requireNonNull(awaiter);
        this.unitOfWork = Objects.requireNonNull(unitOfWork);
        this.runInputs = Objects.requireNonNull(runInputs);
    }

    public AgentRunSnapshot queued(AgentRun run) {
        return mutate(run, "run.queued", value -> value.markQueued(time.now()));
    }

    public AgentRunSnapshot started(AgentRun run) {
        return mutate(run, "run.started", value -> value.start(time.now()));
    }

    public AgentRunSnapshot requestPause(AgentRun run) {
        return mutate(run, "run.pause-requested", value -> value.requestSuspend(time.now()));
    }

    public AgentRunSnapshot suspended(AgentRun run) {
        return mutate(run, "run.suspended", value -> value.suspend(time.now()));
    }

    public AgentRunSnapshot resumed(AgentRun run) {
        return mutate(run, "run.resumed", value -> value.resume(time.now()));
    }

    public AgentRunSnapshot waiting(AgentRun run, InteractionRequestRef request, boolean approval) {
        return mutate(run, approval ? "run.waiting-approval" : "run.waiting-interaction", value -> {
            if (approval) value.waitForApproval(request, time.now());
            else value.waitForInteraction(request, time.now());
        });
    }

    public AgentRunSnapshot beginCompleting(AgentRun run) {
        return mutate(run, "run.completing", value -> value.beginCompleting(time.now()));
    }

    public AgentRunSnapshot completed(AgentRun run, AgentRunResult result) {
        return mutate(run, "run.completed", value -> {
            recordWallTime(value);
            value.complete(result, time.now());
        });
    }

    /** Commits final assistant message, public output and terminal Run state in one Unit of Work. */
    public AgentRunSnapshot completedWithOutput(
            AgentRun run, AgentRunResult result, String output, SessionMessageDraft finalMessage) {
        return completeWithOutput(run, result, output, finalMessage, false).orElseThrow();
    }

    /**
     * Completes the Run with a model's final answer unless steer input is still waiting for a safe point.
     *
     * <p>The pending check and the terminal commit share one Unit of Work, which also serializes input acceptance.
     * An input accepted before this commit therefore defers completion and nothing is changed; the caller keeps the
     * Run in its loop so the input is applied at the next {@code BEFORE_ITERATION}. Input submitted after the commit
     * observes a terminal Run and is rejected.
     */
    public Optional<AgentRunSnapshot> completedWithOutputUnlessInputPending(
            AgentRun run, AgentRunResult result, String output, SessionMessageDraft finalMessage) {
        return completeWithOutput(run, result, output, finalMessage, true);
    }

    private Optional<AgentRunSnapshot> completeWithOutput(
            AgentRun run,
            AgentRunResult result,
            String output,
            SessionMessageDraft finalMessage,
            boolean deferForPendingInput) {
        synchronized (locks.computeIfAbsent(run.id(), ignored -> new Object())) {
            return unitOfWork.execute(() -> {
                if (deferForPendingInput && !runInputs.pending(run.id(), 1).isEmpty()) {
                    return Optional.<AgentRunSnapshot>empty();
                }
                long expectedVersion = run.version();
                AgentRunStatus previous = run.status();
                run.beginCompleting(time.now());
                state.saveFinalOutputAndMessage(run.id(), output, finalMessage);
                recordWallTime(run);
                run.complete(result, time.now());
                runs.save(run, expectedVersion);
                settlePendingInputs(run);
                RuntimeEvent event = events.append(
                        run.id(),
                        "run.completed",
                        Map.of(
                                "previousStatus",
                                previous.name(),
                                "status",
                                run.status().name(),
                                "version",
                                run.version()),
                        time.now());
                outbox.append(new OutboxMessage(
                        event.eventId(),
                        event.runId(),
                        event.sequence(),
                        event.type(),
                        OutboxMessage.CURRENT_SCHEMA_VERSION,
                        Map.of("status", run.status().name(), "version", run.version()),
                        event.occurredAt()));
                AgentRunSnapshot committed = AgentRunSnapshot.from(run, state.output(run.id()));
                unitOfWork.afterCommit(() -> notifyCommitted(committed));
                return Optional.of(committed);
            });
        }
    }

    public AgentRunSnapshot failed(AgentRun run, AgentError error) {
        return mutate(run, "run.failed", value -> {
            recordWallTime(value);
            value.fail(error, time.now());
        });
    }

    /** Commits a partial assistant summary, public output and failed Run state in one Unit of Work. */
    public AgentRunSnapshot failedWithOutput(
            AgentRun run, AgentError error, String output, SessionMessageDraft finalMessage) {
        synchronized (locks.computeIfAbsent(run.id(), ignored -> new Object())) {
            AgentRunSnapshot snapshot = unitOfWork.execute(() -> {
                long expectedVersion = run.version();
                AgentRunStatus previous = run.status();
                state.saveFinalOutputAndMessage(run.id(), output, finalMessage);
                recordWallTime(run);
                run.fail(error, time.now());
                runs.save(run, expectedVersion);
                settlePendingInputs(run);
                Map<String, Object> eventData = terminalEventData(run, previous);
                RuntimeEvent event = events.append(run.id(), "run.failed", eventData, time.now());
                outbox.append(new OutboxMessage(
                        event.eventId(),
                        event.runId(),
                        event.sequence(),
                        event.type(),
                        OutboxMessage.CURRENT_SCHEMA_VERSION,
                        eventData,
                        event.occurredAt()));
                AgentRunSnapshot committed = AgentRunSnapshot.from(run, state.output(run.id()));
                unitOfWork.afterCommit(() -> notifyCommitted(committed));
                return committed;
            });
            return snapshot;
        }
    }

    public AgentRunSnapshot cancelled(AgentRun run, RunTerminationReason reason) {
        return mutate(run, "run.cancelled", value -> {
            recordWallTime(value);
            value.cancel(reason, time.now());
        });
    }

    public AgentRunSnapshot timedOut(AgentRun run, RunTerminationReason reason) {
        return mutate(run, "run.timeout", value -> {
            recordWallTime(value);
            value.timeout(reason, time.now());
        });
    }

    public AgentRunSnapshot usage(AgentRun run, AgentRunUsageDelta delta) {
        return mutate(run, "run.usage-recorded", value -> value.recordUsage(delta));
    }

    public void addListener(AgentRunListener listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    /**
     * Brings the recorded wall time up to the run's active elapsed time before it turns terminal.
     *
     * <p>Nothing else increments it, so without this the persisted usage reports a run of zero
     * duration; the time excludes intervals the run spent waiting for a human.
     */
    private void recordWallTime(AgentRun run) {
        Instant now = time.now();
        if (now.isBefore(run.updatedAt())) {
            return;
        }
        long elapsed = run.activeElapsedMillis(now);
        long recorded = run.usage().wallTimeMillis();
        if (elapsed > recorded) {
            run.recordUsage(new AgentRunUsageDelta(0, 0, 0, 0, 0, 0, 0, elapsed - recorded));
        }
    }

    private AgentRunSnapshot mutate(AgentRun run, String eventType, Consumer<AgentRun> mutation) {
        synchronized (locks.computeIfAbsent(run.id(), ignored -> new Object())) {
            AgentRunSnapshot snapshot = unitOfWork.execute(() -> {
                long expectedVersion = run.version();
                AgentRunStatus previous = run.status();
                mutation.accept(run);
                runs.save(run, expectedVersion);
                if (run.status().isTerminal()) settlePendingInputs(run);
                Map<String, Object> safeEventData = terminalEventData(run, previous);
                RuntimeEvent event = events.append(run.id(), eventType, safeEventData, time.now());
                outbox.append(new OutboxMessage(
                        event.eventId(),
                        event.runId(),
                        event.sequence(),
                        event.type(),
                        OutboxMessage.CURRENT_SCHEMA_VERSION,
                        safeEventData,
                        event.occurredAt()));
                AgentRunSnapshot committed = AgentRunSnapshot.from(run, state.output(run.id()));
                unitOfWork.afterCommit(() -> notifyCommitted(committed));
                return committed;
            });
            return snapshot;
        }
    }

    /**
     * Rejects every accepted input that the now-terminal Run can no longer apply. It runs inside the terminal Unit of
     * Work and before the terminal event, so a subscriber that stops at the terminal event has already seen them.
     */
    private void settlePendingInputs(AgentRun run) {
        String reasonCode = RunInputReasonCodes.terminal(run.status());
        List<RunInputRecord> pending = runInputs.pending(run.id(), 100);
        while (!pending.isEmpty()) {
            for (RunInputRecord input : pending) {
                runInputs.markRejected(input.submission().inputId(), reasonCode);
                Map<String, Object> data =
                        Map.of("inputId", input.submission().inputId().value(), "reasonCode", reasonCode);
                RuntimeEvent event = events.append(run.id(), "run.input.rejected", data, time.now());
                outbox.append(new OutboxMessage(
                        event.eventId(),
                        event.runId(),
                        event.sequence(),
                        event.type(),
                        OutboxMessage.CURRENT_SCHEMA_VERSION,
                        data,
                        event.occurredAt()));
            }
            pending = runInputs.pending(run.id(), 100);
        }
    }

    private static Map<String, Object> terminalEventData(AgentRun run, AgentRunStatus previous) {
        Map<String, Object> eventData = new LinkedHashMap<>();
        eventData.put("previousStatus", previous.name());
        eventData.put("status", run.status().name());
        eventData.put("version", run.version());
        run.error().ifPresent(error -> {
            eventData.put("errorCode", error.code().wireCode());
            eventData.put("errorMessage", error.message());
            eventData.put("errorCategory", error.category().name());
            eventData.put("retryability", error.retryability().name());
            error.optionalDiagnosticId().ifPresent(diagnosticId -> eventData.put("diagnosticId", diagnosticId));
        });
        run.terminationReason().ifPresent(reason -> {
            eventData.put("terminationReason", reason.code());
            eventData.put("terminationDescription", reason.description());
        });
        return Map.copyOf(eventData);
    }

    private void notifyCommitted(AgentRunSnapshot snapshot) {
        awaiter.signal(snapshot.runId());
        for (AgentRunListener listener : listeners) {
            try {
                listener.onRunChanged(snapshot);
            } catch (RuntimeException ignored) {
                // Listener delivery is observational and must not change committed state.
            }
        }
    }
}
