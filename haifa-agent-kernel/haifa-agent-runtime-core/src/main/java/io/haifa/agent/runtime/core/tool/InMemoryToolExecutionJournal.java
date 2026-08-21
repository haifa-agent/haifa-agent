package io.haifa.agent.runtime.core.tool;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.RuntimeIdempotencyKey;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.tool.api.ToolDispatchEvidence;
import io.haifa.agent.tool.api.ToolIdempotency;
import io.haifa.agent.tool.api.ToolReconciliationRecord;
import io.haifa.agent.tool.api.ToolReconciliationStatus;
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
    private final Map<String, ToolResult> uncertainResults = new HashMap<>();
    private final Map<String, ToolDispatchEvidence> dispatchEvidence = new HashMap<>();
    private final Map<String, ToolReconciliationRecord> reconciliations = new HashMap<>();
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
    public synchronized Optional<ToolResult> uncertainResult(AgentRunId runId, RuntimeIdempotencyKey key) {
        return Optional.ofNullable(uncertainResults.get(id(runId, key)));
    }

    @Override
    public synchronized void recordIntent(AgentRunId runId, RuntimeIdempotencyKey key) {
        recordIntent(runId, key, ToolIdempotency.UNKNOWN);
    }

    @Override
    public synchronized void recordIntent(
            AgentRunId runId, RuntimeIdempotencyKey key, ToolIdempotency toolIdempotency) {
        String id = id(runId, key);
        if (!intents.add(id)) throw new IllegalStateException("duplicate active tool intent: " + key);
        states.put(id, ToolJournalState.INTENT_RECORDED);
    }

    @Override
    public synchronized void recordDispatched(AgentRunId runId, RuntimeIdempotencyKey key) {
        String id = id(runId, key);
        if (states.get(id) != ToolJournalState.ACKNOWLEDGED) {
            states.put(id, ToolJournalState.DISPATCHED);
        }
    }

    @Override
    public synchronized void recordDispatched(
            AgentRunId runId, RuntimeIdempotencyKey key, ToolDispatchEvidence evidence) {
        recordDispatched(runId, key);
        String id = id(runId, key);
        ToolDispatchEvidence previous = dispatchEvidence.putIfAbsent(id, evidence);
        if (previous != null && !previous.equals(evidence)) {
            throw new IllegalStateException("tool dispatch evidence changed for the same idempotency key");
        }
    }

    @Override
    public synchronized Optional<ToolDispatchEvidence> dispatchEvidence(AgentRunId runId, RuntimeIdempotencyKey key) {
        return Optional.ofNullable(dispatchEvidence.get(id(runId, key)));
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
        uncertainResults.remove(id);
        pendingResults.remove(id);
        states.put(id, ToolJournalState.COMPLETED);
    }

    @Override
    public synchronized void recordPendingResult(AgentRunId runId, RuntimeIdempotencyKey key, ToolResult result) {
        String id = id(runId, key);
        ToolJournalState current = states.get(id);
        if (current != ToolJournalState.INTENT_RECORDED
                && current != ToolJournalState.DISPATCHED
                && current != ToolJournalState.ACKNOWLEDGED
                && current != ToolJournalState.OUTCOME_UNKNOWN
                && current != ToolJournalState.PENDING_RESULT) {
            throw new IllegalStateException("tool journal cannot accept a pending result from " + current);
        }
        ToolResult existing = pendingResults.putIfAbsent(id, result);
        if (existing != null && !existing.equals(result)) {
            throw new IllegalStateException("pending tool result changed for the same idempotency key");
        }
        uncertain.remove(id);
        uncertainResults.remove(id);
        states.put(id, ToolJournalState.PENDING_RESULT);
    }

    @Override
    public synchronized void recordUncertain(AgentRunId runId, RuntimeIdempotencyKey key) {
        uncertain.add(id(runId, key));
        states.put(id(runId, key), ToolJournalState.OUTCOME_UNKNOWN);
    }

    @Override
    public synchronized void recordUncertain(AgentRunId runId, RuntimeIdempotencyKey key, ToolResult observedResult) {
        recordUncertain(runId, key);
        String id = id(runId, key);
        ToolResult previous = uncertainResults.putIfAbsent(id, observedResult);
        if (previous != null && !previous.equals(observedResult)) {
            throw new IllegalStateException("uncertain tool result changed for the same idempotency key");
        }
    }

    @Override
    public synchronized void recordReconciliation(
            AgentRunId runId, RuntimeIdempotencyKey key, ToolReconciliationStatus status, String reasonCode) {
        String id = id(runId, key);
        ToolJournalState state = states.get(id);
        if (state != ToolJournalState.DISPATCHED
                && state != ToolJournalState.ACKNOWLEDGED
                && state != ToolJournalState.OUTCOME_UNKNOWN) {
            throw new IllegalStateException("tool is not in a reconcilable journal state");
        }
        reconciliations.put(id, new ToolReconciliationRecord(status, reasonCode));
    }

    @Override
    public synchronized Optional<ToolReconciliationRecord> reconciliation(AgentRunId runId, RuntimeIdempotencyKey key) {
        return Optional.ofNullable(reconciliations.get(id(runId, key)));
    }

    @Override
    public synchronized void recordFailed(AgentRunId runId, RuntimeIdempotencyKey key) {
        String id = id(runId, key);
        states.put(id, ToolJournalState.FAILED);
        uncertain.remove(id);
        uncertainResults.remove(id);
        pendingResults.remove(id);
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
