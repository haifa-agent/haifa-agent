package io.haifa.agent.runtime.core.tool;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolResult;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class InMemoryToolExecutionJournal implements ToolExecutionJournal {
    private final Set<String> intents = new HashSet<>();
    private final Set<String> uncertain = new HashSet<>();
    private final Map<String, ToolResult> completed = new HashMap<>();

    @Override
    public synchronized Optional<ToolResult> completed(AgentRunId runId, String key) {
        return Optional.ofNullable(completed.get(id(runId, key)));
    }

    @Override
    public synchronized void recordIntent(AgentRunId runId, String key) {
        String id = id(runId, key);
        if (!intents.add(id)) throw new IllegalStateException("duplicate active tool intent: " + key);
    }

    @Override
    public synchronized void recordCompleted(AgentRunId runId, String key, ToolResult result) {
        String id = id(runId, key);
        completed.put(id, result);
        uncertain.remove(id);
    }

    @Override
    public synchronized void recordUncertain(AgentRunId runId, String key) {
        uncertain.add(id(runId, key));
    }

    @Override
    public synchronized boolean hasUncertain(AgentRunId runId) {
        String prefix = runId.value() + "|";
        return uncertain.stream().anyMatch(value -> value.startsWith(prefix));
    }

    private static String id(AgentRunId runId, String key) {
        return runId.value() + "|" + key;
    }
}
