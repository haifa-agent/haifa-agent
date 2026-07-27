package io.haifa.agent.application.project.product.coding;

import io.haifa.agent.core.reference.AssetRef;
import io.haifa.agent.core.session.AgentSessionId;
import java.util.List;
import java.util.Objects;

public record CodingRestoredMessage(
        String followUpId, AgentSessionId sessionId, String message, List<AssetRef> attachments, long revision) {
    public CodingRestoredMessage {
        followUpId = CodingProductValues.requireText(followUpId, "followUpId", 256);
        sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        message = CodingProductValues.requireText(message, "message", 65_536);
        attachments = List.copyOf(Objects.requireNonNull(attachments, "attachments must not be null"));
        if (revision < 0) throw new IllegalArgumentException("revision must not be negative");
    }
}
