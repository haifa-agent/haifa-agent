package io.haifa.agent.common.time;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** Canonicalizes externally visible and persisted timestamps to UTC epoch milliseconds. */
public final class TimePrecision {

    private TimePrecision() {}

    public static Instant now(Clock clock) {
        return Instant.ofEpochMilli(
                Objects.requireNonNull(clock, "clock must not be null").millis());
    }

    public static Instant toMilliseconds(Instant value) {
        return Instant.ofEpochMilli(
                Objects.requireNonNull(value, "value must not be null").toEpochMilli());
    }
}
