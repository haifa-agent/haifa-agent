package io.haifa.agent.runtime.core.tool;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolResult;
import java.util.Optional;

public interface ToolExecutionJournal {
    Optional<ToolResult> completed(AgentRunId runId, String idempotencyKey);

    void recordIntent(AgentRunId runId, String idempotencyKey);

    void recordCompleted(AgentRunId runId, String idempotencyKey, ToolResult result);

    void recordUncertain(AgentRunId runId, String idempotencyKey);

    boolean hasUncertain(AgentRunId runId);
}
