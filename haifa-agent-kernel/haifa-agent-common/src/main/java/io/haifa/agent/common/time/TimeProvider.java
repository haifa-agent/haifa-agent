package io.haifa.agent.common.time;

import java.time.Instant;

/** Supplies domain time without coupling domain objects to the system clock. */
@FunctionalInterface
public interface TimeProvider {

    Instant now();
}
