package io.haifa.agent.application.project.product.coding;

import io.haifa.agent.core.session.AgentSessionId;
import java.time.Instant;

/**
 * Atomically coordinates the authoritative Core Session lifecycle with the coding product
 * projection.
 */
public interface CodingSessionLifecycle {
    CodingSessionActivity archive(AgentSessionId sessionId, long expectedActivityRevision, Instant at);

    CodingSessionActivity delete(AgentSessionId sessionId, long expectedActivityRevision, Instant at);
}
