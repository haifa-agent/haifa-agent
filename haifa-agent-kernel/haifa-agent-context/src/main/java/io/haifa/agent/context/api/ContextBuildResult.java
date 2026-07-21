package io.haifa.agent.context.api;

import io.haifa.agent.context.trace.ContextTrace;
import java.util.Objects;

public record ContextBuildResult(AgentContext context, ContextTrace trace) {
    public ContextBuildResult {
        context = Objects.requireNonNull(context, "context must not be null");
        trace = Objects.requireNonNull(trace, "trace must not be null");
    }
}
