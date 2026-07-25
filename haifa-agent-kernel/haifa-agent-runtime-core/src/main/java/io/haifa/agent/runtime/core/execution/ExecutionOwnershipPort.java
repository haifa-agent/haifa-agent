package io.haifa.agent.runtime.core.execution;

import io.haifa.agent.runtime.core.attempt.AgentRunExecutionAttempt;
import java.util.Objects;

/** Worker/lease ownership boundary; distributed adapters can validate a durable lease here. */
@FunctionalInterface
public interface ExecutionOwnershipPort {
    boolean stillOwned(AgentRunExecutionAttempt attempt);

    static ExecutionOwnershipPort local(String processInstanceId) {
        String currentInstance = Objects.requireNonNull(processInstanceId, "processInstanceId must not be null")
                .trim();
        if (currentInstance.isEmpty()) {
            throw new IllegalArgumentException("processInstanceId must not be blank");
        }
        return attempt -> attempt.workerId().filter(currentInstance::equals).isPresent();
    }
}
