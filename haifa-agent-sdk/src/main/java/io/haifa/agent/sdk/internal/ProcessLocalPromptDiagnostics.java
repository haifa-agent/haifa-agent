package io.haifa.agent.sdk.internal;

import io.haifa.agent.context.item.ContextItemId;
import io.haifa.agent.context.item.ContextItemType;
import io.haifa.agent.context.prompt.PromptComponentId;
import io.haifa.agent.context.prompt.PromptLayer;
import io.haifa.agent.context.trace.ContextSelectionDecision;
import io.haifa.agent.context.trace.ContextTrace;
import io.haifa.agent.context.trace.ContextTraceItem;
import io.haifa.agent.context.trace.PromptTraceItem;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.core.trace.PromptDiagnosticsSink;
import io.haifa.agent.sdk.diagnostics.PromptDiagnosticComponent;
import io.haifa.agent.sdk.diagnostics.PromptDiagnosticSource;
import io.haifa.agent.sdk.diagnostics.PromptDiagnostics;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps only bounded redacted Context trace evidence for the lifetime of this SDK process. */
public final class ProcessLocalPromptDiagnostics implements PromptDiagnosticsSink {
    private static final int MAXIMUM_COMPONENTS = 256;
    private final Map<AgentRunId, PromptDiagnostics> snapshots = new ConcurrentHashMap<>();

    @Override
    public void record(ContextTrace trace, List<PromptComponentId> promptOrder, List<ContextItemId> itemOrder) {
        Map<PromptComponentId, PromptTraceItem> prompts = new HashMap<>();
        trace.prompts().forEach(item -> prompts.put(item.componentId(), item));
        Map<ContextItemId, ContextTraceItem> items = new HashMap<>();
        trace.items().stream()
                .filter(item -> item.decision() == ContextSelectionDecision.SELECTED)
                .forEach(item -> items.put(item.itemId(), item));

        List<PromptDiagnosticComponent> components = new ArrayList<>();
        for (PromptComponentId id : promptOrder) {
            PromptTraceItem item = prompts.get(id);
            if (item != null && components.size() < MAXIMUM_COMPONENTS) {
                components.add(promptComponent(components.size(), item));
            }
        }
        for (ContextItemId id : itemOrder) {
            ContextTraceItem item = items.get(id);
            if (item != null && components.size() < MAXIMUM_COMPONENTS) {
                components.add(contextComponent(components.size(), item));
            }
        }
        snapshots.put(trace.runId(), PromptDiagnostics.available(trace.runId(), trace.iteration(), components));
    }

    public PromptDiagnostics find(AgentRunId runId) {
        return snapshots.getOrDefault(runId, PromptDiagnostics.unavailable(runId));
    }

    private static PromptDiagnosticComponent promptComponent(int order, PromptTraceItem item) {
        return new PromptDiagnosticComponent(
                order,
                item.componentId().value(),
                item.layer().name(),
                item.role().name(),
                item.version(),
                item.contentHash(),
                item.estimatedTokens(),
                promptSource(item));
    }

    private static PromptDiagnosticComponent contextComponent(int order, ContextTraceItem item) {
        return new PromptDiagnosticComponent(
                order,
                item.itemId().value(),
                "CONTEXT",
                "CONTEXT",
                item.sourceVersion(),
                item.contentHash(),
                item.estimatedTokens(),
                contextSource(item.type()));
    }

    private static PromptDiagnosticSource promptSource(PromptTraceItem item) {
        String id = item.componentId().value();
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

    private static PromptDiagnosticSource contextSource(ContextItemType type) {
        return switch (type) {
            case MEMORY_REFERENCE -> PromptDiagnosticSource.MEMORY;
            case CONVERSATION_SUMMARY -> PromptDiagnosticSource.SUMMARY;
            case RUNTIME_STATE -> PromptDiagnosticSource.RUNTIME_CONTROL;
            case TOOL_CALL_REFERENCE, TOOL_RESULT_REFERENCE -> PromptDiagnosticSource.TOOL_PROTOCOL;
            case MESSAGE -> PromptDiagnosticSource.SESSION_CONTEXT;
            case ASSET_DERIVED_TEXT -> PromptDiagnosticSource.OTHER_CONTEXT;
        };
    }
}
