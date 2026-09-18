package io.haifa.agent.application.project.product.coding;

import io.haifa.agent.core.session.AgentSessionId;

public interface CodingShellService {
    CodingShellPlan plan(AgentSessionId sessionId, String command, boolean includeInContext);

    CodingShellResult execute(String token, boolean approved);

    void discard(String token);
}
