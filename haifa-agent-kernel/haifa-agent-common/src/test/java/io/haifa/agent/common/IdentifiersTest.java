package io.haifa.agent.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.common.id.Identifiers;
import org.junit.jupiter.api.Test;

class IdentifiersTest {

    @Test
    void generatesUniqueNonBlankValues() {
        String first = Identifiers.randomValue();
        String second = Identifiers.randomValue();

        assertThat(first).isNotBlank().isNotEqualTo(second);
    }

    @Test
    void normalizesAndValidatesValues() {
        assertThat(Identifiers.requireValid("  run-1  ", "runId")).isEqualTo("run-1");
        assertThatThrownBy(() -> Identifiers.requireValid(" ", "runId"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runId");
    }
}
