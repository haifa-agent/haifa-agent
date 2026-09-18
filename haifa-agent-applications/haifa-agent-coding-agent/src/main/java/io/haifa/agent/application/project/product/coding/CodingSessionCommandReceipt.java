package io.haifa.agent.application.project.product.coding;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import java.util.Objects;

public record CodingSessionCommandReceipt(
        String operation, AgentSessionId sessionId, AgentRunId runId, boolean replayed) {
    public CodingSessionCommandReceipt {
        operation = CodingProductValues.requireText(operation, "operation", 64);
        sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        runId = Objects.requireNonNull(runId, "runId must not be null");
    }
}
