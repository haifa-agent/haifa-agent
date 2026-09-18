package io.haifa.agent.runtime.core.storage;

import io.haifa.agent.core.run.AgentRunId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

public interface RuntimeEventAppender {
    RuntimeEvent append(AgentRunId runId, String type, Map<String, Object> data, Instant occurredAt);

    List<RuntimeEvent> eventsFor(AgentRunId runId);

    RuntimeEventSlice eventsAfter(AgentRunId runId, long exclusiveSequence, OptionalLong observedHead, int limit);

    OptionalLong earliestSequence(AgentRunId runId);

    OptionalLong headSequence(AgentRunId runId);

    /**
     * Deletes committed client-journal history strictly before {@code retainFromSequence}.
     *
     * <p>Adapters must retain a durable head/earliest marker so later appends never reuse a sequence.
     */
    long deleteBefore(AgentRunId runId, long retainFromSequence, Instant deletedAt);
}
