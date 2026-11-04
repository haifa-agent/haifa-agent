package io.haifa.agent.application.project.product.coding;

import io.haifa.agent.core.session.AgentSessionId;
import java.util.Optional;

/** Host-lifetime receipt. Its ID is the plan token, known before submitting any side effect. */
public record CodingShellExecution(
        String executionId,
        AgentSessionId sessionId,
        long acceptedAtMillis,
        State state,
        boolean cancellationRequested,
        Optional<CodingShellResult> result,
        Optional<String> errorCode) {
    public enum State { RUNNING, COMPLETED, FAILED }
}
