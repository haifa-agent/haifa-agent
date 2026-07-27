package io.haifa.agent.application.coding.terminal.state;

public record TerminalFooter(
        String project,
        String gitBranch,
        String session,
        String metrics,
        String provider,
        String model,
        String runStatus,
        String sandbox) {
    public static TerminalFooter empty() {
        return new TerminalFooter(
                "project",
                "git: unavailable",
                "new session",
                "context: —",
                "provider: —",
                "model: —",
                "IDLE",
                "sandbox: —");
    }
}
