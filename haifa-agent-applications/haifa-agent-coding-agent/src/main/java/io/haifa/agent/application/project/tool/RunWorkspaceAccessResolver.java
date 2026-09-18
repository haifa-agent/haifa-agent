package io.haifa.agent.application.project.tool;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.run.AgentRunId;

@FunctionalInterface
public interface RunWorkspaceAccessResolver {
    RunWorkspaceAccess resolve(AgentRunId runId, PrincipalRef principal);
}
