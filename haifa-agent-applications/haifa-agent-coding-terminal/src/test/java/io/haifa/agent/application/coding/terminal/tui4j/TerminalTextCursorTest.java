package io.haifa.agent.application.coding.terminal.tui4j;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TerminalTextCursorTest {
    @Test
    void movesAndDeletesWholeEmojiAndCombiningGraphemes() {
        String family = "👨‍👩‍👧‍👦";
        String combining = "e\u0301";
        String value = "A" + family + combining + "B";
        int afterFamily = 1 + family.length();
        int afterCombining = afterFamily + combining.length();

        assertThat(TerminalTextCursor.previous(value, afterFamily)).isEqualTo(1);
        assertThat(TerminalTextCursor.next(value, 1)).isEqualTo(afterFamily);
        assertThat(TerminalTextCursor.previous(value, afterCombining)).isEqualTo(afterFamily);
        assertThat(TerminalTextCursor.backspace(value, afterCombining)).isEqualTo("A" + family + "B");
        assertThat(TerminalTextCursor.delete(value, 1)).isEqualTo("A" + combining + "B");
    }

    @Test
    void keepsVerticalMovementAtCellColumnsForCjkAndSurrogatePairs() {
        String value = "ab中\n1234\n🚀x";
        int afterCjk = "ab中".length();
        int afterSecondLine = "ab中\n1234".length();

        assertThat(TerminalTextCursor.vertical(value, afterCjk, 1)).isEqualTo(afterSecondLine);
        assertThat(TerminalTextCursor.vertical(value, afterSecondLine, -1)).isEqualTo(afterCjk);
        assertThat(TerminalTextCursor.next(value, afterSecondLine + 1)).isEqualTo(afterSecondLine + 3);
    }

    @Test
    void clampsAnInvalidUtf16OffsetToThePreviousGraphemeBoundary() {
        String value = "x🚀y";

        assertThat(TerminalTextCursor.clamp(value, 2)).isEqualTo(1);
    }
}
