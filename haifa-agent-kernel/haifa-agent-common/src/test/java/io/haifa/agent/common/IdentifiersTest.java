package io.haifa.agent.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.common.id.Identifiers;
import io.haifa.agent.common.id.UuidV7IdentifierGenerator;
import io.haifa.agent.common.version.SchemaVersion;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.random.RandomGeneratorFactory;
import org.junit.jupiter.api.Test;

class IdentifiersTest {

    @Test
    void generatesUniqueNonBlankValues() {
        UuidV7IdentifierGenerator generator = new UuidV7IdentifierGenerator(
                Clock.fixed(Instant.parse("2026-07-21T00:00:00Z"), ZoneOffset.UTC),
                RandomGeneratorFactory.<java.util.random.RandomGenerator>of("L64X128MixRandom")
                        .create(7));
        String first = generator.nextValue();
        String second = generator.nextValue();

        assertThat(first).isNotBlank().isNotEqualTo(second);
        assertThat(UUID.fromString(first).version()).isEqualTo(7);
        assertThat(UUID.fromString(first).variant()).isEqualTo(2);
        assertThat(first.compareTo(second)).isNegative();
    }

    @Test
    void normalizesAndValidatesValues() {
        assertThat(Identifiers.requireValid("  run-1  ", "runId")).isEqualTo("run-1");
        assertThatThrownBy(() -> Identifiers.requireValid(" ", "runId"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runId");
    }

    @Test
    void parsesAndComparesSchemaVersions() {
        SchemaVersion version = SchemaVersion.parse("1.2");

        assertThat(version).isGreaterThan(SchemaVersion.V1);
        assertThat(version.toString()).isEqualTo("1.2");
        assertThatThrownBy(() -> SchemaVersion.parse("1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("major.minor");
    }
}
