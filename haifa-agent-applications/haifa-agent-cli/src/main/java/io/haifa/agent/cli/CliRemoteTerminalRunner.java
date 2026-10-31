package io.haifa.agent.cli;

import io.haifa.agent.application.coding.terminal.application.CodingTerminalStartup;
import java.io.PrintStream;
import java.net.URI;

/** Narrow launch seam that proves remote mode selection without connecting to a real Host or terminal. */
@FunctionalInterface
interface CliRemoteTerminalRunner {
    void run(URI endpoint, CodingTerminalStartup startup, PrintStream output);
}
