package io.haifa.agent.runtime.core.storage;

import io.haifa.agent.core.run.AgentRunId;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/** One bounded Journal range observed against a fixed Run-local head. */
public record RuntimeEventSlice(
        AgentRunId runId,
        long exclusiveSequence,
        OptionalLong earliestSequence,
        OptionalLong headSequence,
        long scannedThrough,
        List<RuntimeEvent> events) {
    public RuntimeEventSlice {
        runId = Objects.requireNonNull(runId, "runId must not be null");
        if (exclusiveSequence < 0) throw new IllegalArgumentException("exclusiveSequence must not be negative");
        earliestSequence = Objects.requireNonNull(earliestSequence, "earliestSequence must not be null");
        headSequence = Objects.requireNonNull(headSequence, "headSequence must not be null");
        if (scannedThrough < exclusiveSequence) {
            throw new IllegalArgumentException("scannedThrough must not precede the request cursor");
        }
        events = List.copyOf(Objects.requireNonNull(events, "events must not be null"));
        long previous = exclusiveSequence;
        for (RuntimeEvent event : events) {
            if (!event.runId().equals(runId) || event.sequence() <= previous || event.sequence() > scannedThrough) {
                throw new IllegalArgumentException("events must be ordered inside the observed range");
            }
            previous = event.sequence();
        }
    }
}
