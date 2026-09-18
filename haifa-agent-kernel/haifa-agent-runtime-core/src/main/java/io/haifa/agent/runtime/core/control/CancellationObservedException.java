package io.haifa.agent.runtime.core.control;

public final class CancellationObservedException extends RuntimeException {
    private final RunControlSignal signal;

    public CancellationObservedException() {
        this(RunControlSignal.CANCEL);
    }

    public CancellationObservedException(RunControlSignal signal) {
        super("run stop signal observed at an execution safe point: " + requireStopSignal(signal));
        this.signal = signal;
    }

    public RunControlSignal signal() {
        return signal;
    }

    private static RunControlSignal requireStopSignal(RunControlSignal signal) {
        if (signal == null || !signal.stopsExecution()) {
            throw new IllegalArgumentException("signal must stop execution");
        }
        return signal;
    }
}
