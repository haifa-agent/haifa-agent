package io.haifa.agent.common.time;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** Clock-backed system time provider with millisecond precision. */
public final class SystemTimeProvider implements TimeProvider {

    private final Clock clock;

    public SystemTimeProvider() {
        this(Clock.systemUTC());
    }

    public SystemTimeProvider(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public Instant now() {
        return Instant.ofEpochMilli(clock.millis());
    }
}
