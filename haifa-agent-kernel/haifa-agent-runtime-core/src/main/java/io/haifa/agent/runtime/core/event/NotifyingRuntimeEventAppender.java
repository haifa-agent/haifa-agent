package io.haifa.agent.runtime.core.event;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.core.storage.RuntimeEvent;
import io.haifa.agent.runtime.core.storage.RuntimeEventAppender;
import io.haifa.agent.runtime.core.storage.RuntimeEventSlice;
import io.haifa.agent.runtime.core.storage.RuntimeUnitOfWork;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

/** Adds post-commit Run-scoped wake-ups without changing Journal durability. */
public final class NotifyingRuntimeEventAppender implements RuntimeEventAppender {
    private final RuntimeEventAppender delegate;
    private final RuntimeUnitOfWork unitOfWork;
    private final RuntimeEventWakeupRegistry wakeups;

    public NotifyingRuntimeEventAppender(
            RuntimeEventAppender delegate, RuntimeUnitOfWork unitOfWork, RuntimeEventWakeupRegistry wakeups) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        this.wakeups = Objects.requireNonNull(wakeups, "wakeups must not be null");
    }

    @Override
    public RuntimeEvent append(AgentRunId runId, String type, Map<String, Object> data, Instant occurredAt) {
        return unitOfWork.execute(() -> {
            RuntimeEvent event = delegate.append(runId, type, data, occurredAt);
            unitOfWork.afterCommit(() -> wakeups.wake(runId));
            return event;
        });
    }

    @Override
    public List<RuntimeEvent> eventsFor(AgentRunId runId) {
        return delegate.eventsFor(runId);
    }

    @Override
    public RuntimeEventSlice eventsAfter(
            AgentRunId runId, long exclusiveSequence, OptionalLong observedHead, int limit) {
        return delegate.eventsAfter(runId, exclusiveSequence, observedHead, limit);
    }

    @Override
    public OptionalLong earliestSequence(AgentRunId runId) {
        return delegate.earliestSequence(runId);
    }

    @Override
    public OptionalLong headSequence(AgentRunId runId) {
        return delegate.headSequence(runId);
    }

    @Override
    public long deleteBefore(AgentRunId runId, long retainFromSequence, Instant deletedAt) {
        return unitOfWork.execute(() -> {
            long deleted = delegate.deleteBefore(runId, retainFromSequence, deletedAt);
            if (deleted > 0) unitOfWork.afterCommit(() -> wakeups.wake(runId));
            return deleted;
        });
    }
}
