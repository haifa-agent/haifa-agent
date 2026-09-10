package io.haifa.agent.runtime.core.loop;

import io.haifa.agent.runtime.core.recovery.RunBudgetSnapshot;
import io.haifa.agent.runtime.core.trace.RuntimeTraceContext;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

public final class AgentLoopContext {
    private int iteration;
    private int forcedContextRebuildAttempts;
    private final Set<Integer> issuedBudgetThresholds = new LinkedHashSet<>();
    private RunBudgetSnapshot budgetSnapshot;
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
}
