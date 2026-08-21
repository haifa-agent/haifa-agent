package io.haifa.agent.execution.api;

/** Host-local process identity exposed only as bounded reconciliation evidence. */
public record ExecutionProcessIdentity(long processId) {
    public ExecutionProcessIdentity {
        if (processId < 1) throw new IllegalArgumentException("processId must be positive");
    }
}
