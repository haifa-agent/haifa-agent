package io.haifa.agent.runtime.core.control;

public final class CancellationObservedException extends RuntimeException {
    private final RunControlDirective directive;

    public CancellationObservedException() {
        this(new RunControlDirective(
                RunControlSignal.CANCEL,
                java.util.Optional.of(new io.haifa.agent.core.run.RunTerminationReason(
                        "USER_CANCELLED", "Cancellation requested by the user"))));
    }

    public CancellationObservedException(RunControlSignal signal) {
        this(new RunControlDirective(signal, terminationReason(signal)));
    }

    public CancellationObservedException(RunControlDirective directive) {
        super("run stop signal observed at an execution safe point: " + requireStopSignal(directive.signal()));
        this.directive = java.util.Objects.requireNonNull(directive, "directive must not be null");
    }

    public RunControlSignal signal() {
        return directive.signal();
    }

    public RunControlDirective directive() {
        return directive;
    }

    private static RunControlSignal requireStopSignal(RunControlSignal signal) {
        if (signal == null || !signal.stopsExecution()) {
            throw new IllegalArgumentException("signal must stop execution");
        }
        return signal;
    }

    private static java.util.Optional<io.haifa.agent.core.run.RunTerminationReason> terminationReason(
            RunControlSignal signal) {
        if (signal == RunControlSignal.CANCEL) {
            return java.util.Optional.of(new io.haifa.agent.core.run.RunTerminationReason(
                    "USER_CANCELLED", "Cancellation requested by the user"));
        }
        if (signal == RunControlSignal.TIMEOUT) {
            return java.util.Optional.of(new io.haifa.agent.core.run.RunTerminationReason(
                    "WALL_TIME_EXCEEDED", "Run wall-time limit exceeded"));
        }
        return java.util.Optional.empty();
    }
}
