package io.haifa.agent.runtime.core.completion;

import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import java.util.Objects;
import java.util.Optional;

/** Reconciles the latest persisted plan rather than trusting a model completion claim. */
public final class TodoReconciliationService {
    private final RuntimeStateRepository state;
    private final TodoConvergenceChecker checker;

    public TodoReconciliationService(RuntimeStateRepository state, TodoConvergenceChecker checker) {
        this.state = Objects.requireNonNull(state);
        this.checker = Objects.requireNonNull(checker);
    }

    public Optional<String> blocker(AgentRun run) {
        return state.plan(run.id()).filter(plan -> !checker.isConverged(plan)).map(plan -> "unfinished todo");
    }
}
