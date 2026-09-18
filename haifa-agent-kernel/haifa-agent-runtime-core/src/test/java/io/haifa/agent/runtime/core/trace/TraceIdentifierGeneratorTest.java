package io.haifa.agent.runtime.core.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TraceIdentifierGeneratorTest {
    @Test
    void encodesExactly128BitsAsUnpaddedBase64Url() {
        var generator = new TraceIdentifierGenerator(target -> {
            for (int index = 0; index < target.length; index++) target[index] = (byte) index;
        });

        assertThat(generator.nextTraceId())
                .isEqualTo("tr_AAECAwQFBgcICQoLDA0ODw")
                .hasSize(25);
        assertThat(generator.nextStreamId())
                .isEqualTo("ts_AAECAwQFBgcICQoLDA0ODw")
                .hasSize(25);
    }

    @Test
    void doesNotSwallowEntropySourceFailure() {
        var generator = new TraceIdentifierGenerator(target -> {
            throw new IllegalStateException("entropy unavailable");
        });

        assertThatThrownBy(generator::nextTraceId)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("entropy unavailable");
    }

    @Test
    void generatedValuesRemainUniqueForDistinct128BitInputs() {
        AtomicInteger counter = new AtomicInteger();
        var generator = new TraceIdentifierGenerator(target -> {
            int value = counter.getAndIncrement();
            target[12] = (byte) (value >>> 24);
            target[13] = (byte) (value >>> 16);
            target[14] = (byte) (value >>> 8);
            target[15] = (byte) value;
        });

        var values = new HashSet<String>();
        for (int index = 0; index < 10_000; index++) values.add(generator.nextTraceId());

        assertThat(values).hasSize(10_000).allMatch(value -> value.matches("tr_[A-Za-z0-9_-]{22}"));
    }
}
