package io.haifa.agent.runtime.core.trace;

/**
 * Internal-only sink for unexpected failures. Implementations must redact and bound Throwable data
 * before storage; sink failures must never change Runtime state.
 */
@FunctionalInterface
public interface FailureDiagnosticSink {
    void record(RuntimeTraceEvent context, Throwable failure);

    static FailureDiagnosticSink noop() {
        return (context, failure) -> {};
    }
}
