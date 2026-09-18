package io.haifa.agent.sdk.internal;

import io.haifa.agent.context.prompt.PromptLayer;
import io.haifa.agent.context.trace.ContextReport;
import io.haifa.agent.context.trace.ContextReportComponent;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.core.trace.PromptDiagnosticsSink;
import io.haifa.agent.sdk.diagnostics.PromptDiagnosticComponent;
import io.haifa.agent.sdk.diagnostics.PromptDiagnosticSource;
import io.haifa.agent.sdk.diagnostics.PromptDiagnostics;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps only bounded redacted Context trace evidence for the lifetime of this SDK process. */
public final class ProcessLocalPromptDiagnostics implements PromptDiagnosticsSink {
    private static final int MAXIMUM_COMPONENTS = 256;
    private final Map<AgentRunId, PromptDiagnostics> snapshots = new ConcurrentHashMap<>();

    @Override
    public void record(ContextReport report) {
        List<PromptDiagnosticComponent> components = new ArrayList<>();
        for (ContextReportComponent item : report.components()) {
            if (components.size() == MAXIMUM_COMPONENTS) break;
            components.add(
                    item.kind() == ContextReportComponent.ComponentKind.PROMPT
                            ? promptComponent(components.size(), item)
                            : contextComponent(components.size(), item));
        }
        snapshots.put(report.runId(), PromptDiagnostics.available(report.runId(), report.iteration(), components));
    }

    public PromptDiagnostics find(AgentRunId runId) {
        return snapshots.getOrDefault(runId, PromptDiagnostics.unavailable(runId));
    }

    private static PromptDiagnosticComponent promptComponent(int order, ContextReportComponent item) {
        return new PromptDiagnosticComponent(
                order,
                item.id(),
                item.layer().name(),
                item.role().name(),
                item.version(),
                item.contentHash(),
                item.estimatedTokens(),
                promptSource(item));
    }

    private static PromptDiagnosticComponent contextComponent(int order, ContextReportComponent item) {
        return new PromptDiagnosticComponent(
                order,
                item.id(),
                "CONTEXT",
                "CONTEXT",
                item.version(),
                item.contentHash(),
                item.estimatedTokens(),
                contextSource(item.sourceType()));
    }

    private static PromptDiagnosticSource promptSource(ContextReportComponent item) {
        String id = item.id();
        if (id.startsWith("agent-definition-haifa-sdk-starter-agent")) {
            return PromptDiagnosticSource.STARTER_INSTRUCTIONS;
        }
        if (item.layer() == PromptLayer.AGENT_DEFINITION) return PromptDiagnosticSource.AGENT_INSTRUCTIONS;
        if (item.layer() == PromptLayer.SYSTEM_SAFETY) return PromptDiagnosticSource.RUNTIME_SAFETY;
        if (item.layer() == PromptLayer.PLATFORM_POLICY) return PromptDiagnosticSource.PLATFORM_POLICY;
        if (item.layer() == PromptLayer.RUNTIME_CONTROL) return PromptDiagnosticSource.RUNTIME_CONTROL;
        if (item.layer() == PromptLayer.TOOL_PROTOCOL) return PromptDiagnosticSource.TOOL_PROTOCOL;
        if (item.layer() == PromptLayer.SKILL) return PromptDiagnosticSource.SKILL;
        return PromptDiagnosticSource.OTHER_CONTEXT;
    }

    private static PromptDiagnosticSource contextSource(String sourceType) {
        return switch (sourceType) {
            case "governed-memory" -> PromptDiagnosticSource.MEMORY;
            case "conversation-summary" -> PromptDiagnosticSource.SUMMARY;
            case "session-message-group" -> PromptDiagnosticSource.SESSION_CONTEXT;
            case "runtime-control", "todo" -> PromptDiagnosticSource.RUNTIME_CONTROL;
            default -> PromptDiagnosticSource.OTHER_CONTEXT;
        };
    }
}
