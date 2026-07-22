package io.haifa.agent.runtime.core.tool;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.RuntimeIdempotencyKey;
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
    private final Map<String, ToolResult> pendingResults = new HashMap<>();
    private final Map<String, ToolJournalState> states = new HashMap<>();

    @Override
    public synchronized Optional<ToolResult> completed(AgentRunId runId, RuntimeIdempotencyKey key) {
        return Optional.ofNullable(completed.get(id(runId, key)));
    }

    @Override
    public synchronized Optional<ToolResult> pendingResult(AgentRunId runId, RuntimeIdempotencyKey key) {
        return Optional.ofNullable(pendingResults.get(id(runId, key)));
    }

    @Override
    public synchronized void recordIntent(AgentRunId runId, RuntimeIdempotencyKey key) {
        String id = id(runId, key);
        if (!intents.add(id)) throw new IllegalStateException("duplicate active tool intent: " + key);
        states.put(id, ToolJournalState.INTENT_RECORDED);
    }

    @Override
    public synchronized void recordDispatched(AgentRunId runId, RuntimeIdempotencyKey key) {
        states.put(id(runId, key), ToolJournalState.DISPATCHED);
    }

    @Override
    public synchronized void recordAcknowledged(AgentRunId runId, RuntimeIdempotencyKey key) {
        states.put(id(runId, key), ToolJournalState.ACKNOWLEDGED);
    }

    @Override
    public synchronized void recordCompleted(AgentRunId runId, RuntimeIdempotencyKey key, ToolResult result) {
        String id = id(runId, key);
        completed.put(id, result);
        uncertain.remove(id);
        pendingResults.remove(id);
        states.put(id, ToolJournalState.COMPLETED);
    }

    @Override
    public synchronized void recordPendingResult(AgentRunId runId, RuntimeIdempotencyKey key, ToolResult result) {
        ToolResult existing = pendingResults.putIfAbsent(id(runId, key), result);
        if (existing != null && !existing.equals(result)) {
            throw new IllegalStateException("pending tool result changed for the same idempotency key");
        }
        states.put(id(runId, key), ToolJournalState.PENDING_RESULT);
    }

    @Override
    public synchronized void recordUncertain(AgentRunId runId, RuntimeIdempotencyKey key) {
        uncertain.add(id(runId, key));
        states.put(id(runId, key), ToolJournalState.OUTCOME_UNKNOWN);
    }

    @Override
    public synchronized void recordFailed(AgentRunId runId, RuntimeIdempotencyKey key) {
        String id = id(runId, key);
        states.put(id, ToolJournalState.FAILED);
        uncertain.remove(id);
    }

    @Override
    public synchronized Optional<ToolJournalState> state(AgentRunId runId, RuntimeIdempotencyKey key) {
        return Optional.ofNullable(states.get(id(runId, key)));
    }

    @Override
    public synchronized boolean hasUncertain(AgentRunId runId) {
        String prefix = runId.value() + "|";
        return uncertain.stream().anyMatch(value -> value.startsWith(prefix));
    }

    private static String id(AgentRunId runId, RuntimeIdempotencyKey key) {
        return runId.value() + "|" + key.value();
    }
}
