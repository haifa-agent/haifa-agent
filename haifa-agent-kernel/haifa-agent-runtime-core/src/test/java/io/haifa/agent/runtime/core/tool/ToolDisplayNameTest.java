package io.haifa.agent.runtime.core.tool;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ToolDisplayNameTest {
    @Test
    void prefersTitleAndFallsBackToMachineName() {
        assertThat(ToolDisplayName.resolve("Read File", "file_read")).isEqualTo("Read File");
        assertThat(ToolDisplayName.resolve(" ", "file_read")).isEqualTo("file_read");
        assertThat(ToolDisplayName.resolve(null, null)).isEqualTo("Tool");
    }

    @Test
    void stripsAnsiAndControlCharactersAndCollapsesWhitespace() {
        assertThat(ToolDisplayName.resolve("\u001B[31mRead\u001B[0m\n  File\tNow", "file_read"))
                .isEqualTo("Read File Now");
    }

    @Test
    void truncatesLongTitlesWithoutSplittingSurrogatePairs() {
        String resolved = ToolDisplayName.resolve("Read " + "文".repeat(300), "file_read");
        assertThat(resolved.length()).isLessThanOrEqualTo(ToolDisplayName.MAXIMUM_LENGTH);
        assertThat(resolved).endsWith("…");

        String emoji = ToolDisplayName.resolve("😀".repeat(200), "file_read");
        assertThat(emoji.length()).isLessThanOrEqualTo(ToolDisplayName.MAXIMUM_LENGTH);
        assertThat(emoji).endsWith("…");
        String body = emoji.substring(0, emoji.length() - 1);
        for (int index = 0; index < body.length(); index++) {
            char value = body.charAt(index);
            if (Character.isHighSurrogate(value)) {
                assertThat(index + 1).isLessThan(body.length());
                assertThat(Character.isLowSurrogate(body.charAt(index + 1))).isTrue();
                index++;
            } else {
                assertThat(Character.isLowSurrogate(value)).isFalse();
            }
        }
    }
}
