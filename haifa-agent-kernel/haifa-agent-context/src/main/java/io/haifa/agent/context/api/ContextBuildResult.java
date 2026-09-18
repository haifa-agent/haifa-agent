package io.haifa.agent.context.api;

import io.haifa.agent.context.trace.ContextReport;
import java.util.Objects;

public record ContextBuildResult(AgentContext context, ContextReport report) {
    public ContextBuildResult {
        context = Objects.requireNonNull(context, "context must not be null");
        report = Objects.requireNonNull(report, "report must not be null");
    }
}
