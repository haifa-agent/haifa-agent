package io.haifa.agent.common.id;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.random.RandomGenerator;

/** Thread-safe, time-ordered UUID version 7 identifier generator. */
public final class UuidV7IdentifierGenerator implements IdentifierGenerator {

    private static final long TIMESTAMP_MASK = 0x0000FFFFFFFFFFFFL;
    private static final long RANDOM_B_MASK = 0x3FFFFFFFFFFFFFFFL;

    private final Clock clock;
    private final RandomGenerator random;
    private long lastTimestamp = -1;
    private int sequence;

    public UuidV7IdentifierGenerator() {
        this(Clock.systemUTC(), RandomGenerator.getDefault());
    }

    public UuidV7IdentifierGenerator(Clock clock, RandomGenerator random) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.random = Objects.requireNonNull(random, "random must not be null");
    }

    @Override
    public synchronized String nextValue() {
        long timestamp = clock.millis();
        if (timestamp < lastTimestamp) {
            timestamp = lastTimestamp;
        }
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & 0x0FFF;
            if (sequence == 0) {
                timestamp = ++lastTimestamp;
            }
        } else {
            sequence = random.nextInt(0x1000);
            lastTimestamp = timestamp;
        }

        long mostSignificantBits = ((timestamp & TIMESTAMP_MASK) << 16) | 0x7000L | sequence;
        long leastSignificantBits = 0x8000000000000000L | (random.nextLong() & RANDOM_B_MASK);
        return new UUID(mostSignificantBits, leastSignificantBits).toString();
    }
}
