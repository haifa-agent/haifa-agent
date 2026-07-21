package io.haifa.agent.application.project.tool;

import io.haifa.agent.core.run.AgentRun;

@FunctionalInterface
public interface RunWorkspaceAccessResolver {
    RunWorkspaceAccess resolve(AgentRun run);
}
