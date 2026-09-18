package io.haifa.agent.application.project.product;

import io.haifa.agent.core.session.AgentSessionId;
import java.util.Optional;

public interface ProjectProductSessionStore {
    void create(ProjectProductSession session);

    Optional<ProjectProductSession> find(AgentSessionId sessionId);
}
