package io.haifa.agent.runtime.core.loop;

import io.haifa.agent.context.item.ContextItem;
import io.haifa.agent.core.message.AgentMessageId;
import io.haifa.agent.runtime.core.recovery.RunBudgetSnapshot;
import io.haifa.agent.runtime.core.trace.RuntimeTraceContext;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class AgentLoopContext {
    private int iteration;
    private int forcedContextRebuildAttempts;
    private final Set<Integer> issuedBudgetThresholds = new LinkedHashSet<>();
    private RunBudgetSnapshot budgetSnapshot;
    private MemoryTurnSelection memoryTurnSelection;
    private final List<ChildWait> childWaits = new java.util.ArrayList<>();
    private final Optional<RuntimeTraceContext> traceContext;

    public AgentLoopContext(int iteration) {
        this(iteration, 0, null);
    }

    public AgentLoopContext(int iteration, int forcedContextRebuildAttempts, RuntimeTraceContext traceContext) {
        if (iteration < 1) throw new IllegalArgumentException("iteration must be positive");
        if (forcedContextRebuildAttempts < 0 || forcedContextRebuildAttempts > 1) {
            throw new IllegalArgumentException("forced context rebuild attempts must be zero or one");
        }
        this.iteration = iteration;
        this.forcedContextRebuildAttempts = forcedContextRebuildAttempts;
        this.traceContext = Optional.ofNullable(traceContext);
    }

    public int iteration() {
        return iteration;
    }

    public RuntimeTraceContext traceContext() {
        return traceContext.orElseThrow(() -> new IllegalStateException("runtime trace context is unavailable"));
    }

    public void next() {
        iteration++;
    }

    public void restoreBudgetThresholds(RunBudgetSnapshot snapshot) {
        issuedBudgetThresholds.addAll(snapshot.crossedThresholds());
        budgetSnapshot = snapshot;
    }

    public Set<Integer> updateBudgetSnapshot(RunBudgetSnapshot snapshot) {
        budgetSnapshot = snapshot;
        Set<Integer> newlyCrossed = new LinkedHashSet<>(snapshot.crossedThresholds());
        newlyCrossed.removeAll(issuedBudgetThresholds);
        issuedBudgetThresholds.addAll(newlyCrossed);
        return Set.copyOf(newlyCrossed);
    }

    public Optional<RunBudgetSnapshot> budgetSnapshot() {
        return Optional.ofNullable(budgetSnapshot);
    }

    public int recordForcedContextRebuild() {
        if (forcedContextRebuildAttempts >= 1) {
            throw new ContextRebuildExhaustedException("model context remained too long after forced rebuild");
        }
        return ++forcedContextRebuildAttempts;
    }

    public int forcedContextRebuildAttempts() {
        return forcedContextRebuildAttempts;
    }

    public Optional<List<ContextItem>> memorySelectionFor(AgentMessageId userMessageId) {
        if (memoryTurnSelection == null || !memoryTurnSelection.userMessageId().equals(userMessageId)) {
            return Optional.empty();
        }
        return Optional.of(memoryTurnSelection.items());
    }

    /** Records one interval in which this run waited for delegated child runs (excluded from idle time). */
    public void recordChildWait(java.time.Instant startedAt, java.time.Instant endedAt) {
        java.util.Objects.requireNonNull(startedAt, "startedAt must not be null");
        java.util.Objects.requireNonNull(endedAt, "endedAt must not be null");
        if (endedAt.isAfter(startedAt)) childWaits.add(new ChildWait(startedAt, endedAt));
    }

    /** Milliseconds within {@code [since, now]} this run spent waiting for child runs. */
    public long childWaitMillis(java.time.Instant since, java.time.Instant now) {
        long total = 0;
        for (ChildWait wait : childWaits) {
            java.time.Instant start = wait.startedAt().isAfter(since) ? wait.startedAt() : since;
            java.time.Instant end = wait.endedAt().isBefore(now) ? wait.endedAt() : now;
            if (end.isAfter(start))
                total += java.time.Duration.between(start, end).toMillis();
        }
        return total;
    }

    public void cacheMemorySelection(AgentMessageId userMessageId, List<ContextItem> items) {
        memoryTurnSelection = new MemoryTurnSelection(userMessageId, items);
    }

    private record ChildWait(java.time.Instant startedAt, java.time.Instant endedAt) {}

    private record MemoryTurnSelection(AgentMessageId userMessageId, List<ContextItem> items) {
        private MemoryTurnSelection {
            userMessageId = java.util.Objects.requireNonNull(userMessageId, "userMessageId must not be null");
            items = List.copyOf(java.util.Objects.requireNonNull(items, "items must not be null"));
        }
    }
}
