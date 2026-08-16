package io.haifa.agent.testing.harness;

import java.util.Objects;

/** One shared Resolve -> Finalize lifecycle. Suite adapters own only native semantics. */
public final class HarnessLifecycle<R, P, E, G, F> {
    private final Stages<R, P, E, G, F> stages;

    public HarnessLifecycle(Stages<R, P, E, G, F> stages) {
        this.stages = Objects.requireNonNull(stages, "stages must not be null");
    }

    public F run(TestRunRequest request) throws Exception {
        R resolved = stages.resolve(request);
        stages.preflight(request, resolved);
        P prepared = stages.provision(request, resolved);
        E executed = stages.execute(request, resolved, prepared);
        G graded = stages.grade(request, resolved, executed);
        return stages.finalizeRun(request, resolved, executed, graded);
    }

    public interface Stages<R, P, E, G, F> {
        R resolve(TestRunRequest request) throws Exception;

        void preflight(TestRunRequest request, R resolved) throws Exception;

        P provision(TestRunRequest request, R resolved) throws Exception;

        E execute(TestRunRequest request, R resolved, P prepared) throws Exception;

        G grade(TestRunRequest request, R resolved, E executed) throws Exception;

        F finalizeRun(TestRunRequest request, R resolved, E executed, G graded) throws Exception;
    }
}
