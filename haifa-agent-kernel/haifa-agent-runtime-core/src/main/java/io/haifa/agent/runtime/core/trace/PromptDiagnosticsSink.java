package io.haifa.agent.runtime.core.trace;

import io.haifa.agent.context.trace.ContextReport;

/** Best-effort process-local sink for already-redacted Context trace facts. */
@FunctionalInterface
public interface PromptDiagnosticsSink {
    void record(ContextReport report);

    static PromptDiagnosticsSink noop() {
        return report -> {};
    }
}
