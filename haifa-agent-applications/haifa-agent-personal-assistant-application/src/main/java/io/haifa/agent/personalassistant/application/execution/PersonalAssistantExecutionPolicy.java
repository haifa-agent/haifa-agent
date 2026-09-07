package io.haifa.agent.personalassistant.application.execution;

import io.haifa.agent.execution.api.ExecutionOrigin;
import io.haifa.agent.execution.api.ExecutionRequest;
import io.haifa.agent.execution.core.ExecutionPolicy;
import io.haifa.agent.execution.core.ExecutionRejectedException;

/** PA owns only Runtime-originated execution in M3; every other entry fails closed. */
public final class PersonalAssistantExecutionPolicy implements ExecutionPolicy {
    @Override
    public void authorize(ExecutionRequest request) {
        if (request.context().origin() == ExecutionOrigin.RUNTIME_TOOL
                && request.context().sourceToolCallId().isPresent()
                && request.context().allows("execution.run")) {
            return;
        }
        throw new ExecutionRejectedException(
                "PERSONAL_EXECUTION_ORIGIN_DENIED", "Personal Assistant does not authorize this execution entry");
    }
}
