package io.haifa.agent.personalassistant.server.configuration.execution;

import io.haifa.agent.execution.core.change.WorkspaceChangeIgnorePolicy;
import io.haifa.agent.project.filesystem.FileType;
import io.haifa.agent.project.path.ProjectPath;

/** Versioned PA policy: every logical path in its private execution workspace is observable. */
final class PersonalWorkspaceChangeIgnorePolicy implements WorkspaceChangeIgnorePolicy {
    static final String VERSION = "personal-workspace-change-v1";

    @Override
    public boolean ignores(ProjectPath path, FileType type) {
        return false;
    }
}
