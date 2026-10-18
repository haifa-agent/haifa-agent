package io.haifa.agent.application.coding.terminal.state;

public record TerminalFooter(
        String project,
        String gitBranch,
        String worktree,
        String worktreeBranch,
        String session,
        String metrics,
        String provider,
        String model,
        String runStatus,
        String sandbox) {
    public TerminalFooter(
            String project,
            String gitBranch,
            String session,
            String metrics,
            String provider,
            String model,
            String runStatus,
            String sandbox) {
        this(project, gitBranch, "", "", session, metrics, provider, model, runStatus, sandbox);
    }

    public static TerminalFooter empty() {
        return new TerminalFooter("", "", "", "", "", "context: —", "", "", "IDLE", "");
    }
}
