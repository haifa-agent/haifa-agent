package io.haifa.agent.runtime.core.tool;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.RuntimeIdempotencyKey;
import io.haifa.agent.core.tool.ToolResult;
import java.util.Optional;

public interface ToolExecutionJournal {
    Optional<ToolResult> completed(AgentRunId runId, RuntimeIdempotencyKey idempotencyKey);

    Optional<ToolResult> pendingResult(AgentRunId runId, RuntimeIdempotencyKey idempotencyKey);

    void recordIntent(AgentRunId runId, RuntimeIdempotencyKey idempotencyKey);

    void recordDispatched(AgentRunId runId, RuntimeIdempotencyKey idempotencyKey);

    void recordAcknowledged(AgentRunId runId, RuntimeIdempotencyKey idempotencyKey);

    void recordCompleted(AgentRunId runId, RuntimeIdempotencyKey idempotencyKey, ToolResult result);

    void recordPendingResult(AgentRunId runId, RuntimeIdempotencyKey idempotencyKey, ToolResult result);

    void recordUncertain(AgentRunId runId, RuntimeIdempotencyKey idempotencyKey);

    void recordFailed(AgentRunId runId, RuntimeIdempotencyKey idempotencyKey);

    Optional<ToolJournalState> state(AgentRunId runId, RuntimeIdempotencyKey idempotencyKey);

    boolean hasUncertain(AgentRunId runId);
}
