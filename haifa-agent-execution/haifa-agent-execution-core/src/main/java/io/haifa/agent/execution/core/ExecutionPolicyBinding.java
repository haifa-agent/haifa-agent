package io.haifa.agent.execution.core;

import io.haifa.agent.execution.api.ExecutionCommandMode;
import io.haifa.agent.execution.api.ExecutionRequest;
import io.haifa.agent.policy.api.PolicyDigest;
import java.util.List;

public final class ExecutionPolicyBinding {
    private ExecutionPolicyBinding() {}

    public static String resourceDigest(ExecutionRequest request) {
        String command = request.command().mode() == ExecutionCommandMode.SHELL
                ? request.command().shellCommand()
                : String.join("\u0000", request.command().argv());
        String invocationDigest = PolicyDigest.sha256Fields(
                List.of(command, request.workingDirectory().projectPath().toString()));
        String executionProfile = request.sandboxProfileRef().value() + "@"
                + request.sandboxProfileRef().version();
        return PolicyDigest.sha256Fields(List.of(invocationDigest, executionProfile));
    }
}
