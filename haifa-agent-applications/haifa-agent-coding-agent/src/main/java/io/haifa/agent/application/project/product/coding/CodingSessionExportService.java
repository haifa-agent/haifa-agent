package io.haifa.agent.application.project.product.coding;

import io.haifa.agent.core.session.AgentSessionId;

@FunctionalInterface
public interface CodingSessionExportService {
    CodingSessionExportResult export(AgentSessionId sessionId, String logicalDestination);
}
