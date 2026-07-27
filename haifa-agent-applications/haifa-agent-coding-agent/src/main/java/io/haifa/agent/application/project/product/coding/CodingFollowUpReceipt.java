package io.haifa.agent.application.project.product.coding;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import java.util.Objects;
import java.util.Optional;

public record CodingFollowUpReceipt(
        String followUpId,
        AgentSessionId sessionId,
        CodingFollowUpStatus status,
        long sequence,
        long revision,
        Optional<AgentRunId> dispatchedRunId) {
    public CodingFollowUpReceipt {
        followUpId = CodingProductValues.requireText(followUpId, "followUpId", 256);
        sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        status = Objects.requireNonNull(status, "status must not be null");
        if (sequence < 1) throw new IllegalArgumentException("sequence must be positive");
        if (revision < 0) throw new IllegalArgumentException("revision must not be negative");
        dispatchedRunId = Objects.requireNonNull(dispatchedRunId, "dispatchedRunId must not be null");
    }

    public static CodingFollowUpReceipt from(CodingFollowUp value) {
        return new CodingFollowUpReceipt(
                value.followUpId(),
                value.sessionId(),
                value.status(),
                value.sequence(),
                value.revision(),
                value.dispatchedRunId());
    }
}
