package io.haifa.agent.sandbox.api;

import io.haifa.agent.execution.api.ExecutionCommand;
import io.haifa.agent.execution.api.ExecutionLimits;
import io.haifa.agent.project.path.WorkspacePath;
import java.util.Map;
import java.util.Objects;

public record SandboxExecution(
        ExecutionCommand command,
        WorkspacePath workingDirectory,
        Map<String, String> environment,
        ExecutionLimits limits) {
    public SandboxExecution {
        command = Objects.requireNonNull(command, "command must not be null");
        workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory must not be null");
        environment = Map.copyOf(Objects.requireNonNull(environment, "environment must not be null"));
        limits = Objects.requireNonNull(limits, "limits must not be null");
    }
}
