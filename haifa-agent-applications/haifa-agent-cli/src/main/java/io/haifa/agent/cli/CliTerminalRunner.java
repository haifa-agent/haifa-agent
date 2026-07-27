package io.haifa.agent.cli;

import io.haifa.agent.runtime.core.trace.RuntimeTraceEvent;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Narrow launch seam used to prove mode selection without initializing a real model or terminal. */
@FunctionalInterface
interface CliTerminalRunner {
    void run(
            Path workspace,
            CliConfiguration configuration,
            PrintStream output,
            Consumer<RuntimeTraceEvent> traceObserver);
}
