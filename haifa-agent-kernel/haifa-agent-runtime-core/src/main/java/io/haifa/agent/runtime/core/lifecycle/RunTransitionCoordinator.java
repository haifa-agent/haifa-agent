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
import io.haifa.agent.runtime.core.retry.PersistenceRetryPolicy;
import io.haifa.agent.runtime.core.retry.RetryExecutor;
import io.haifa.agent.runtime.core.storage.OutboxMessage;
import io.haifa.agent.runtime.core.storage.RunStateRepository;
import io.haifa.agent.runtime.core.storage.RuntimeEventAppender;
import io.haifa.agent.runtime.core.storage.RuntimeOutboxPublisher;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import io.haifa.agent.runtime.core.storage.RuntimeUnitOfWork;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final RetryExecutor retries;
    private final PersistenceRetryPolicy persistenceRetry;
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
            RuntimeUnitOfWork unitOfWork,
            RetryExecutor retries,
            PersistenceRetryPolicy persistenceRetry) {
        this.runs = Objects.requireNonNull(runs);
        this.state = Objects.requireNonNull(state);
        this.events = Objects.requireNonNull(events);
        this.outbox = Objects.requireNonNull(outbox);
        this.ids = Objects.requireNonNull(ids);
        this.time = Objects.requireNonNull(time);
        this.awaiter = Objects.requireNonNull(awaiter);
        this.unitOfWork = Objects.requireNonNull(unitOfWork);
        this.retries = Objects.requireNonNull(retries);
        this.persistenceRetry = Objects.requireNonNull(persistenceRetry);
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
        return mutate(run, "run.completed", value -> value.complete(result, time.now()));
    }

    public AgentRunSnapshot failed(AgentRun run, AgentError error) {
        return mutate(run, "run.failed", value -> value.fail(error, time.now()));
    }

    public AgentRunSnapshot cancelled(AgentRun run, RunTerminationReason reason) {
        return mutate(run, "run.cancelled", value -> value.cancel(reason, time.now()));
    }

    public AgentRunSnapshot timedOut(AgentRun run, RunTerminationReason reason) {
        return mutate(run, "run.timeout", value -> value.timeout(reason, time.now()));
    }

    public AgentRunSnapshot usage(AgentRun run, AgentRunUsageDelta delta) {
        return mutate(run, "run.usage-recorded", value -> value.recordUsage(delta));
    }

    public void addListener(AgentRunListener listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    private AgentRunSnapshot mutate(AgentRun run, String eventType, Consumer<AgentRun> mutation) {
        synchronized (locks.computeIfAbsent(run.id(), ignored -> new Object())) {
            AgentRunSnapshot snapshot = retries.execute(
                    () -> unitOfWork.execute(() -> {
                        long expectedVersion = run.version();
                        AgentRunStatus previous = run.status();
                        mutation.accept(run);
                        runs.save(run, expectedVersion);
                        events.append(
                                run.id(),
                                eventType,
                                Map.of(
                                        "previousStatus",
                                        previous.name(),
                                        "status",
                                        run.status().name(),
                                        "version",
                                        run.version()),
                                time.now());
                        outbox.append(new OutboxMessage(
                                ids.nextValue(),
                                run.id(),
                                eventType,
                                Map.of("status", run.status().name(), "version", run.version()),
                                time.now()));
                        return AgentRunSnapshot.from(run, state.output(run.id()));
                    }),
                    persistenceRetry.policy());
            awaiter.signal(run.id());
            for (AgentRunListener listener : listeners) {
                try {
                    listener.onRunChanged(snapshot);
                } catch (RuntimeException ignored) {
                    // Listener delivery is observational and must not change a committed lifecycle transition.
                }
            }
            return snapshot;
        }
    }
}
