package io.haifa.agent.runtime.core.bootstrap;

import io.haifa.agent.core.reference.ProjectRef;
import io.haifa.agent.core.session.AgentSessionId;
import java.util.Optional;

/** Hosting-boundary authorization for session and project references supplied by a start request. */
@FunctionalInterface
public interface RunAccessValidator {
    void validate(RuntimeCallerContext caller, AgentSessionId sessionId, Optional<ProjectRef> project);

    static RunAccessValidator allowLocalReferences() {
        return (caller, sessionId, project) -> {};
    }
}
