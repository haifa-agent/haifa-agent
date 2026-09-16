package io.haifa.agent.sdk.conversation;

import io.haifa.agent.core.run.AgentRunId;
import java.util.Objects;

public record ConversationRun(ConversationRecord record, AgentRunId runId, long runVersion) {
    public ConversationRun {
        record = Objects.requireNonNull(record, "record must not be null");
        runId = Objects.requireNonNull(runId, "runId must not be null");
        if (runVersion < 0) throw new IllegalArgumentException("runVersion must not be negative");
    }
}
