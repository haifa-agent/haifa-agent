package io.haifa.agent.runtime.core.loop;

import java.time.Instant;
import java.util.Objects;

public record AgentLoopIteration(int number, Instant startedAt) {
    public AgentLoopIteration {
        if (number < 1) throw new IllegalArgumentException("iteration number must be positive");
        startedAt = Objects.requireNonNull(startedAt, "startedAt must not be null");
    }
}
