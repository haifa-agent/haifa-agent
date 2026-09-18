package io.haifa.agent.runtime.core.storage;

import io.haifa.agent.core.session.AgentSession;
import io.haifa.agent.core.session.AgentSessionId;
import java.util.Optional;

/** Persistence boundary for the Core session aggregate. */
public interface AgentSessionRepository {

    void insert(AgentSession session);

    void save(AgentSession session, long expectedVersion);

    Optional<AgentSession> find(AgentSessionId sessionId);
}
