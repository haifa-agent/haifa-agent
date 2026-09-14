package io.haifa.agent.runtime.core.model;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;

/** Per-physical-call content-free accounting for sensitive reasoning deltas. */
final class ReasoningBudget {
    private final OptionalLong maxBytes;
    private final OptionalLong maxDurationMillis;
    private Instant firstDeltaAt;
    private long bytes;
    private long events;
    private long durationMillis;
    private boolean exceeded;

    ReasoningBudget(OptionalLong maxBytes, OptionalLong maxDurationMillis) {
        this.maxBytes = maxBytes;
        this.maxDurationMillis = maxDurationMillis;
    }

    boolean observe(String delta, Instant observedAt) {
        if (firstDeltaAt == null) firstDeltaAt = observedAt;
        bytes = Math.addExact(bytes, delta.getBytes(StandardCharsets.UTF_8).length);
        events = Math.addExact(events, 1);
        durationMillis = Math.max(0, Duration.between(firstDeltaAt, observedAt).toMillis());
        exceeded = maxBytes.isPresent() && bytes > maxBytes.getAsLong()
                || maxDurationMillis.isPresent() && durationMillis > maxDurationMillis.getAsLong();
        return !exceeded;
    }

    boolean exceeded() {
        return exceeded;
    }

    long bytes() {
        return bytes;
    }

    long events() {
        return events;
    }

    long durationMillis() {
        return durationMillis;
    }

    long maxBytes() {
        return maxBytes.orElse(0);
    }

    long maxDurationMillis() {
        return maxDurationMillis.orElse(0);
    }
}
