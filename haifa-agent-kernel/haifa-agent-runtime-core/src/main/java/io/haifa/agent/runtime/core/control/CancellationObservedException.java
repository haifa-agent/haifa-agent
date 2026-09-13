package io.haifa.agent.runtime.core.control;

public final class CancellationObservedException extends RuntimeException {
    private final RunControlDirective directive;

    public CancellationObservedException() {
        this(new RunControlDirective(
                RunControlSignal.CANCEL,
                java.util.Optional.of(new io.haifa.agent.core.run.RunTerminationReason(
                        "USER_CANCELLED", "Cancellation requested by the user"))));
    }

    public CancellationObservedException(RunControlDirective directive) {
        super("run cancellation observed at an execution safe point");
        this.directive = java.util.Objects.requireNonNull(directive, "directive must not be null");
        if (directive.signal() != RunControlSignal.CANCEL && directive.signal() != RunControlSignal.TIMEOUT) {
            throw new IllegalArgumentException("cancellation observation requires cancel or timeout signal");
        }
    }

    public RunControlDirective directive() {
        return directive;
    }
}
