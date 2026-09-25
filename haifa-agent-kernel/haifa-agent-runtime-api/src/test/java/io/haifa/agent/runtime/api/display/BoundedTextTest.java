package io.haifa.agent.runtime.api.display;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class BoundedTextTest {

    @Test
    void keepsValueVerbatimWhenWithinBudget() {
        BoundedText text = BoundedText.of("hello\nworld", new ToolDisplayBudget(64, 10));

        assertThat(text.text()).isEqualTo("hello\nworld");
        assertThat(text.truncated()).isFalse();
        assertThat(text.byteCount()).isEqualTo(11);
        assertThat(text.lineCount()).isEqualTo(2);
        assertThat(text.truncationReason()).isEmpty();
    }

    @Test
    void truncatesOnByteBudgetKeepingHeadAndTail() {
        String value = "0123456789".repeat(20);

        BoundedText text = BoundedText.of(value, 40, 100);

        assertThat(text.truncated()).isTrue();
        assertThat(text.truncationReason()).contains(BoundedText.TruncationReason.OUTPUT_BYTES);
        assertThat(text.byteCount()).isEqualTo(200);
        assertThat(text.lineCount()).isEqualTo(1);
        assertThat(text.text()).contains("[truncated]");
        assertThat(text.text()).startsWith("012");
        assertThat(text.text()).endsWith("789");
        assertThat(utf8Length(text.text())).isLessThanOrEqualTo(40);
    }

    @Test
    void truncatesOnLineBudgetKeepingFirstAndLastLines() {
        String value = "l1\nl2\nl3\nl4\nl5\nl6";

        BoundedText text = BoundedText.of(value, 4_096, 4);

        assertThat(text.truncated()).isTrue();
        assertThat(text.truncationReason()).contains(BoundedText.TruncationReason.OUTPUT_LINES);
        assertThat(text.lineCount()).isEqualTo(6);
        assertThat(text.text()).contains("l1", "l6", "[truncated]");
        assertThat(utf8Length(text.text())).isLessThanOrEqualTo(4_096);
    }

    @Test
    void keepsUtf8CodePointsIntactAtByteBoundaries() {
        BoundedText text = BoundedText.of("é".repeat(40), 41, 10);

        assertThat(text.truncated()).isTrue();
        assertThat(text.text()).doesNotContain("\uFFFD");
        assertThat(new String(text.text().getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8))
                .isEqualTo(text.text());
        assertThat(utf8Length(text.text())).isLessThanOrEqualTo(41);
    }

    @Test
    void emptyValueCarriesNoSizeFacts() {
        BoundedText text = BoundedText.empty();

        assertThat(text.text()).isEmpty();
        assertThat(text.byteCount()).isZero();
        assertThat(text.lineCount()).isZero();
        assertThat(text.truncated()).isFalse();
        assertThat(text.truncationReason()).isEmpty();
    }

    @Test
    void rejectsNonPositiveBudgets() {
        assertThatThrownBy(() -> new ToolDisplayBudget(0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolDisplayBudget(1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BoundedText.of("value", 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BoundedText.of("value", 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BoundedText.of(null, 1, 1)).isInstanceOf(NullPointerException.class);
    }

    private static long utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }
}
