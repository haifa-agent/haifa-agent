package io.haifa.agent.execution.core;

import io.haifa.agent.execution.api.ExecutionRequest;
import io.haifa.agent.policy.api.PolicyDigest;
import java.util.List;

public final class ExecutionPolicyBinding {
    private ExecutionPolicyBinding() {}

    public static String resourceDigest(ExecutionRequest request) {
        String executionProfile = request.sandboxProfileRef().value() + "@"
                + request.sandboxProfileRef().version();
        return PolicyDigest.sha256Fields(List.of(request.invocationDigest(), executionProfile));
    }
}
