package io.haifa.agent.project.hostworkspace;

import io.haifa.agent.project.hostworkspace.scope.AuthorizedHostDirectory;
import java.nio.file.Path;

/**
 * Host-only Git validity check. Implementations may delegate to Git/Execution integrations, while
 * Project remains independent of those modules and never runs a process itself.
 */
@FunctionalInterface
public interface HostGitInspectionPort {
    HostGitInspectionStatus inspect(AuthorizedHostDirectory boundary, Path candidateDirectory);
}
