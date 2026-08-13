package io.haifa.agent.runtime.core.trace;

import io.haifa.agent.context.item.ContextItemId;
import io.haifa.agent.context.prompt.PromptComponentId;
import io.haifa.agent.context.trace.ContextTrace;
import java.util.List;

/** Best-effort process-local sink for already-redacted Context trace facts. */
@FunctionalInterface
public interface PromptDiagnosticsSink {
    void record(ContextTrace trace, List<PromptComponentId> promptOrder, List<ContextItemId> itemOrder);

    static PromptDiagnosticsSink noop() {
        return (trace, promptOrder, itemOrder) -> {};
    }
}
