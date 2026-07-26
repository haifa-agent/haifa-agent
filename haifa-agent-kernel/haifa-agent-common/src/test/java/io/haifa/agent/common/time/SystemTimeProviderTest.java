package io.haifa.agent.common.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class SystemTimeProviderTest {

    @Test
    void exposesSystemTimeAtMillisecondPrecision() {
        Instant fixed = Instant.parse("2026-07-26T12:00:00.123Z");
        SystemTimeProvider provider = new SystemTimeProvider(Clock.fixed(fixed, ZoneOffset.UTC));

        assertThat(provider.now()).isEqualTo(Instant.parse("2026-07-26T12:00:00.123Z"));
    }
}
