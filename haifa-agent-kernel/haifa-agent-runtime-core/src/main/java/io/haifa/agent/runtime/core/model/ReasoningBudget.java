package io.haifa.agent.runtime.core.model;

import io.haifa.agent.model.api.ModelErrorCategory;
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
    private ModelErrorCategory limitFailure;

    ReasoningBudget(OptionalLong maxBytes, OptionalLong maxDurationMillis) {
        this.maxBytes = maxBytes;
        this.maxDurationMillis = maxDurationMillis;
    }

    boolean observe(String delta, Instant observedAt) {
        if (firstDeltaAt == null) firstDeltaAt = observedAt;
        bytes = Math.addExact(bytes, delta.getBytes(StandardCharsets.UTF_8).length);
        events = Math.addExact(events, 1);
        durationMillis = Math.max(0, Duration.between(firstDeltaAt, observedAt).toMillis());
        if (maxBytes.isPresent() && bytes > maxBytes.getAsLong()) {
            // A byte breach remains terminal even when the same delta also exceeds the time limit.
            limitFailure = ModelErrorCategory.REASONING_BYTE_LIMIT_EXCEEDED;
        } else if (limitFailure == null
                && maxDurationMillis.isPresent()
                && durationMillis > maxDurationMillis.getAsLong()) {
            limitFailure = ModelErrorCategory.REASONING_TIME_LIMIT_EXCEEDED;
        }
        return limitFailure == null;
    }

    ModelErrorCategory limitFailure() {
        return limitFailure;
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
