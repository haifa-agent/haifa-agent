package io.haifa.agent.orchestration.api;

/** Durable workflow runtime extension used by explicit application assembly. */
@Incubating
public interface RecoverableWorkflowRuntime extends WorkflowRuntime {
    /**
     * Reconciles a non-terminal durable run after process restart.
     *
     * <p>The implementation must never replay an unresolved side effect. A run whose outcome
     * cannot be proven is moved to a stable {@link WorkflowErrorCode#OUTCOME_UNKNOWN} failure.
     */
    WorkflowRunSnapshot recover(WorkflowRunId runId);
}
