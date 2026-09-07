package io.haifa.agent.application.project.policy;

import io.haifa.agent.execution.api.ExecutionCommandMode;
import io.haifa.agent.execution.api.ExecutionOrigin;
import io.haifa.agent.execution.api.ExecutionRequest;
import io.haifa.agent.execution.core.ExecutionPolicy;
import io.haifa.agent.execution.core.ExecutionRejectedException;
import java.nio.file.Path;

/** Minimal product-owned terminal policy for the Coding Agent's currently supported entry paths. */
public final class CodingAgentExecutionPolicy implements ExecutionPolicy {
    @Override
    public void authorize(ExecutionRequest request) {
        ExecutionOrigin origin = request.context().origin();
        if (origin == ExecutionOrigin.RUNTIME_TOOL
                && request.context().sourceToolCallId().isPresent()
                && request.context().allows("execution.run")) {
            return;
        }
        if (origin == ExecutionOrigin.PRODUCT_USER_COMMAND
                && request.context().sourceToolCallId().isEmpty()
                && request.context().allows("execution.run")) {
            return;
        }
        if (origin == ExecutionOrigin.PRODUCT_INTERNAL
                && request.context().sourceToolCallId().isEmpty()
                && request.context().allows("git.read")
                && request.command().mode() == ExecutionCommandMode.DIRECT
                && Path.of(request.command().executable())
                        .getFileName()
                        .toString()
                        .equalsIgnoreCase("git")) {
            return;
        }
        throw new ExecutionRejectedException(
                "CODING_EXECUTION_ORIGIN_DENIED", "Coding Agent does not authorize this execution entry");
    }
}
