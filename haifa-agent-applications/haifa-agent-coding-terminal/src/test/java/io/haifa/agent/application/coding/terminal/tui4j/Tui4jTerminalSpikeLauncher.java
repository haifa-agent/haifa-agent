package io.haifa.agent.application.coding.terminal.tui4j;

import com.williamcallahan.tui4j.compat.bubbletea.Program;

/**
 * Real-terminal launcher for Stage A evidence. This test-classpath entrypoint is intentionally not
 * wired into the production CLI.
 */
public final class Tui4jTerminalSpikeLauncher {
    private Tui4jTerminalSpikeLauncher() {}

    public static void main(String[] args) {
        boolean failAfterInit = args.length == 1 && "--fail-after-init".equals(args[0]);
        var model = new Tui4jTerminalSpikeModel(80, 24);
        var program = new Program(model).withAltScreen();
        Thread.ofVirtual().name("tui4j-spike-runtime-action").start(() -> {
            program.waitForInit();
            if (failAfterInit) {
                program.send(new Tui4jTerminalSpikeModel.FailureMessage("SPIKE_RENDER_FAILURE"));
            } else {
                program.send(new Tui4jTerminalSpikeModel.RuntimeActionMessage(
                        "simulated runtime action delivered through Program.send()"));
            }
        });
        program.run();
    }
}
