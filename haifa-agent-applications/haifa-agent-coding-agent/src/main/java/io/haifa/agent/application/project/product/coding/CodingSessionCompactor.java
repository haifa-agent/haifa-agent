package io.haifa.agent.application.project.product.coding;

import io.haifa.agent.core.session.AgentSessionId;

@FunctionalInterface
public interface CodingSessionCompactor {
    CodingCompactionResult compact(AgentSessionId sessionId);
}
