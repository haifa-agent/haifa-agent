package io.haifa.agent.execution.api;

import java.time.Duration;
import java.util.Objects;

public record ResourceUsageSummary(Duration wallTime, int observedProcessCount) {
    public ResourceUsageSummary {
        wallTime = Objects.requireNonNull(wallTime, "wallTime must not be null");
        if (observedProcessCount < 0) throw new IllegalArgumentException("observedProcessCount must not be negative");
    }
}
