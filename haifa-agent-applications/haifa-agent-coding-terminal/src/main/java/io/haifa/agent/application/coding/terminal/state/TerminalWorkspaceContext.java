package io.haifa.agent.application.coding.terminal.state;

import java.util.Objects;

/** Trusted local workspace facts supplied by the highest-layer application assembly. */
public record TerminalWorkspaceContext(String workingDirectory, String gitBranch) {
    public TerminalWorkspaceContext {
        workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory must not be null")
                .strip();
        gitBranch =
                Objects.requireNonNull(gitBranch, "gitBranch must not be null").strip();
    }

    public static TerminalWorkspaceContext empty() {
        return new TerminalWorkspaceContext("", "");
    }
}
