package io.haifa.agent.application.coding.terminal.tui4j;

import io.haifa.agent.application.coding.terminal.application.CodingTerminalController;
import io.haifa.agent.application.coding.terminal.event.TerminalEventPump;
import java.util.Objects;

/** Owns the production tui4j Program lifecycle without owning product facts. */
public final class Tui4jCodingTerminal {
    public static final String TUI_UNAVAILABLE = "TUI_UNAVAILABLE";

    private final CodingTerminalController controller;
    private final TerminalEventPump pump;
    private final Tui4jTerminalIo terminalIo;

    public Tui4jCodingTerminal(
            CodingTerminalController controller, TerminalEventPump pump, Tui4jTerminalIo terminalIo) {
        this.controller = Objects.requireNonNull(controller, "controller must not be null");
        this.pump = Objects.requireNonNull(pump, "pump must not be null");
        this.terminalIo = Objects.requireNonNull(terminalIo, "terminalIo must not be null");
    }

    public void run() {
        terminalIo.requireInteractive();
        terminalIo
                .compatibilityNotice()
                .ifPresent(code -> pump.offer(
                        new io.haifa.agent.application.coding.terminal.event.TerminalUiAction.RecoverableFailure(
                                code)));
        var model = new Tui4jCodingTerminalModel(controller, pump, System::nanoTime, terminalIo.hostInfo());
        terminalIo.run(terminalIo.program(model));
    }
}
