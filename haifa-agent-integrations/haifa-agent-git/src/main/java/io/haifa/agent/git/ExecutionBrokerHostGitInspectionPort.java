package io.haifa.agent.git;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.execution.api.ExecutionBroker;
import io.haifa.agent.execution.api.ExecutionStatus;
import io.haifa.agent.execution.api.SandboxProfileRef;
import io.haifa.agent.project.hostworkspace.HostGitInspectionPort;
import io.haifa.agent.project.hostworkspace.HostGitInspectionStatus;
import io.haifa.agent.project.hostworkspace.scope.AuthorizedHostDirectory;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.path.WorkspacePath;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Validates candidate worktree roots through the existing ExecutionBroker path. */
public final class ExecutionBrokerHostGitInspectionPort implements HostGitInspectionPort {
    private final ExecutionBrokerGitReadClient git;
    private final GitCommandContext context;

    public ExecutionBrokerHostGitInspectionPort(
            ExecutionBroker broker,
            IdentifierGenerator identifiers,
            SandboxProfileRef profile,
            String gitExecutable,
            GitCommandContext context) {
        this.git = new ExecutionBrokerGitReadClient(broker, identifiers, profile, gitExecutable);
        this.context = Objects.requireNonNull(context, "context must not be null");
    }

    @Override
    public HostGitInspectionStatus inspect(AuthorizedHostDirectory boundary, Path candidateDirectory) {
        Objects.requireNonNull(boundary, "boundary must not be null");
        Path candidate = Objects.requireNonNull(candidateDirectory, "candidateDirectory must not be null")
                .toAbsolutePath()
                .normalize();
        if (!boundary.encloses(candidate)) {
            throw new IllegalArgumentException("candidate is outside the authorized directory");
        }
        String relative = boundary.realPath().relativize(candidate).toString().replace('\\', '/');
        WorkspacePath workdir = new WorkspacePath(boundary.workspaceId(), ProjectPath.of(relative));
        try {
            var result = git.run(context, workdir, List.of("rev-parse", "--show-toplevel"), 4096);
            if (result.status() != ExecutionStatus.SUCCEEDED) {
                return result.optionalExitCode().filter(code -> code == 128).isPresent()
                        ? HostGitInspectionStatus.NOT_WORKTREE_ROOT
                        : HostGitInspectionStatus.UNAVAILABLE;
            }
            if (result.stdout().truncated()) {
                return HostGitInspectionStatus.NOT_WORKTREE_ROOT;
            }
            Path reported =
                    Path.of(result.stdout().summary().trim()).toAbsolutePath().normalize();
            try {
                reported = reported.toRealPath();
                candidate = candidate.toRealPath();
            } catch (java.io.IOException ignored) {
                return HostGitInspectionStatus.NOT_WORKTREE_ROOT;
            }
            return reported.equals(candidate)
                    ? HostGitInspectionStatus.WORKTREE_ROOT
                    : HostGitInspectionStatus.NOT_WORKTREE_ROOT;
        } catch (RuntimeException exception) {
            return HostGitInspectionStatus.UNAVAILABLE;
        }
    }
}
