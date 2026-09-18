package io.haifa.agent.application.project.product.coding;

import io.haifa.agent.core.session.AgentSessionId;
import java.util.Objects;

public record CodingShellPlan(
        String token,
        AgentSessionId sessionId,
        String safeCommand,
        boolean includeInContext,
        State state,
        String reasonCode) {
    public enum State {
        READY,
        APPROVAL_REQUIRED,
        DENIED
    }

    public CodingShellPlan {
        token = CodingProductValues.requireText(token, "token", 256);
        sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        safeCommand = CodingProductValues.requireText(safeCommand, "safeCommand", 4_096);
        state = Objects.requireNonNull(state, "state must not be null");
        reasonCode = CodingProductValues.requireText(reasonCode, "reasonCode", 256);
    }
}
