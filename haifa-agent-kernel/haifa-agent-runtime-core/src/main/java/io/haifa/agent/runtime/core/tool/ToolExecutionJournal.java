package io.haifa.agent.runtime.core.tool;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.RuntimeIdempotencyKey;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.tool.api.ToolDispatchEvidence;
import io.haifa.agent.tool.api.ToolIdempotency;
import java.util.Optional;

public interface ToolExecutionJournal {
    Optional<ToolResult> pendingResult(AgentRunId runId, RuntimeIdempotencyKey idempotencyKey);

    default Optional<ToolResult> uncertainResult(AgentRunId runId, RuntimeIdempotencyKey idempotencyKey) {
        return Optional.empty();
    }

    void recordIntent(AgentRunId runId, RuntimeIdempotencyKey idempotencyKey);

    default void recordIntent(AgentRunId runId, RuntimeIdempotencyKey idempotencyKey, ToolIdempotency toolIdempotency) {
        recordIntent(runId, idempotencyKey);
    }

    void recordDispatched(AgentRunId runId, RuntimeIdempotencyKey idempotencyKey);

    default void recordDispatched(
            AgentRunId runId, RuntimeIdempotencyKey idempotencyKey, ToolDispatchEvidence evidence) {
        recordDispatched(runId, idempotencyKey);
    }

    default Optional<ToolDispatchEvidence> dispatchEvidence(AgentRunId runId, RuntimeIdempotencyKey idempotencyKey) {
        return Optional.empty();
    }

    void recordAcknowledged(AgentRunId runId, RuntimeIdempotencyKey idempotencyKey);

    void recordCompleted(AgentRunId runId, RuntimeIdempotencyKey idempotencyKey);

    void recordPendingResult(AgentRunId runId, RuntimeIdempotencyKey idempotencyKey, ToolResult result);

    void recordUncertain(AgentRunId runId, RuntimeIdempotencyKey idempotencyKey);

    default void recordUncertain(AgentRunId runId, RuntimeIdempotencyKey idempotencyKey, ToolResult observedResult) {
        recordUncertain(runId, idempotencyKey);
    }

    void recordFailed(AgentRunId runId, RuntimeIdempotencyKey idempotencyKey);

    Optional<ToolJournalState> state(AgentRunId runId, RuntimeIdempotencyKey idempotencyKey);

    boolean hasUncertain(AgentRunId runId);
}
