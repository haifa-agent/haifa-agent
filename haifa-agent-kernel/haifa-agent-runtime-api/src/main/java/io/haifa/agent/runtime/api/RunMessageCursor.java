package io.haifa.agent.runtime.api;

import io.haifa.agent.core.run.AgentRunId;
import java.util.Objects;

/** Exclusive cursor over the existing ordered message facts of one Run. */
public record RunMessageCursor(AgentRunId runId, long exclusiveSequence) {
    public RunMessageCursor {
        Objects.requireNonNull(runId, "runId must not be null");
        if (exclusiveSequence < 0) throw new IllegalArgumentException("message sequence must be non-negative");
    }

    public static RunMessageCursor beforeFirst(AgentRunId runId) {
        return new RunMessageCursor(runId, 0);
    }
}
