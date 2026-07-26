package io.haifa.agent.runtime.api;

import io.haifa.agent.core.run.AgentRunId;
import java.util.Objects;
import java.util.OptionalLong;

/** Structured embedded cursor; remote transports encode it as an opaque value. */
public record RunEventCursor(AgentRunId runId, String feedVersion, OptionalLong exclusiveSequence) {
    public RunEventCursor {
        runId = Objects.requireNonNull(runId, "runId must not be null");
        feedVersion = InteractionOption.requireText(feedVersion, "feedVersion", 32);
        exclusiveSequence = Objects.requireNonNull(exclusiveSequence, "exclusiveSequence must not be null");
        if (exclusiveSequence.isPresent() && exclusiveSequence.getAsLong() < 1) {
            throw new IllegalArgumentException("exclusiveSequence must be positive");
        }
    }

    public static RunEventCursor beforeFirst(AgentRunId runId) {
        return new RunEventCursor(runId, "1", OptionalLong.empty());
    }
}
