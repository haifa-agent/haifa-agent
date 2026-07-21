package io.haifa.agent.application.project.product;

import io.haifa.agent.core.session.AgentSessionId;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryProjectProductSessionStore implements ProjectProductSessionStore {
    private final ConcurrentHashMap<AgentSessionId, ProjectProductSession> values = new ConcurrentHashMap<>();

    @Override
    public void create(ProjectProductSession session) {
        if (values.putIfAbsent(session.sessionId(), session) != null) {
            throw new IllegalStateException("product session already exists");
        }
    }

    @Override
    public Optional<ProjectProductSession> find(AgentSessionId sessionId) {
        return Optional.ofNullable(values.get(sessionId));
    }
}
