package io.haifa.agent.runtime.api;

import io.haifa.agent.core.run.AgentRunId;
import java.time.Instant;
import java.util.Objects;

/** Safe public projection of incremental assistant output. */
public record AgentRunOutputEvent(
        AgentRunId runId,
        String modelCallId,
        String generationId,
        int physicalAttempt,
        long sequence,
        AgentRunOutputEventType type,
        String textDelta,
        Instant occurredAt) {
    public AgentRunOutputEvent {
        runId = Objects.requireNonNull(runId, "runId must not be null");
        modelCallId = requireText(modelCallId, "modelCallId");
        generationId = requireText(generationId, "generationId");
        if (physicalAttempt < 1) throw new IllegalArgumentException("physicalAttempt must be positive");
        if (sequence < 1) throw new IllegalArgumentException("sequence must be positive");
        type = Objects.requireNonNull(type, "type must not be null");
        textDelta = Objects.requireNonNull(textDelta, "textDelta must not be null");
        if (type != AgentRunOutputEventType.ASSISTANT_TEXT_DELTA && !textDelta.isEmpty()) {
            throw new IllegalArgumentException("only assistant text delta events may contain text");
        }
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }

    public RunOutputCursor cursor() {
        return new RunOutputCursor(sequence);
    }

    private static String requireText(String value, String field) {
        String result =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (result.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return result;
    }
}
