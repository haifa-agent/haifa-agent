package io.haifa.agent.runtime.core.execution;

import io.haifa.agent.runtime.core.attempt.AgentRunExecutionAttempt;

/** Worker/lease ownership boundary; distributed adapters can validate a durable lease here. */
@FunctionalInterface
public interface ExecutionOwnershipPort {
    boolean stillOwned(AgentRunExecutionAttempt attempt);

    static ExecutionOwnershipPort local() {
        return attempt -> attempt.workerId().isPresent();
    }
}
