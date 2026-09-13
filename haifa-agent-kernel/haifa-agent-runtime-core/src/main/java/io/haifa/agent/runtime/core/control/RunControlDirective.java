package io.haifa.agent.runtime.core.control;

import io.haifa.agent.core.run.RunTerminationReason;
import java.util.Objects;
import java.util.Optional;

/** Atomic cooperative-control signal and its terminal reason, when applicable. */
public record RunControlDirective(RunControlSignal signal, Optional<RunTerminationReason> terminationReason) {
    public static final RunControlDirective NONE = new RunControlDirective(RunControlSignal.NONE, Optional.empty());

    public RunControlDirective {
        signal = Objects.requireNonNull(signal, "signal must not be null");
        terminationReason = Objects.requireNonNull(terminationReason, "terminationReason must not be null");
        if ((signal == RunControlSignal.CANCEL || signal == RunControlSignal.TIMEOUT) && terminationReason.isEmpty()) {
            throw new IllegalArgumentException("terminal control signals require a termination reason");
        }
        if (signal != RunControlSignal.CANCEL && signal != RunControlSignal.TIMEOUT && terminationReason.isPresent()) {
            throw new IllegalArgumentException("only cancel and timeout signals accept a termination reason");
        }
    }
}
