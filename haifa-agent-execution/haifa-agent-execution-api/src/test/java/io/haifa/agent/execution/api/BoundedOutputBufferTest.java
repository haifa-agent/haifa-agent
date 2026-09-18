package io.haifa.agent.execution.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class BoundedOutputBufferTest {
    @Test
    void retainsHeadAndTailWithExplicitOmissionMetadata() {
        var output = new BoundedOutputBuffer(64);
        output.write("BEGIN-".getBytes(StandardCharsets.UTF_8));
        output.write("x".repeat(200).getBytes(StandardCharsets.UTF_8));
        output.write("-END".getBytes(StandardCharsets.UTF_8));

        String retained = new String(output.bytes(), StandardCharsets.UTF_8);

        assertThat(output.truncated()).isTrue();
        assertThat(output.byteCount()).isEqualTo(210);
        assertThat(retained).startsWith("BEGIN-").contains("bytes omitted").endsWith("-END");
        assertThat(output.bytes()).hasSizeLessThanOrEqualTo(64);
    }

    @Test
    void retainsEveryByteWhenWithinBudget() {
        var output = new BoundedOutputBuffer(16);
        output.write("head-tail".getBytes(StandardCharsets.UTF_8));

        assertThat(new String(output.bytes(), StandardCharsets.UTF_8)).isEqualTo("head-tail");
        assertThat(output.truncated()).isFalse();
    }
}
