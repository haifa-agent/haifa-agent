package io.haifa.agent.application.coding.terminal.tui4j;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TerminalScreenCellsTest {
    private static final String ESC = "\u001B";

    @Test
    void copiesAcrossAnsiCjkEmojiCombiningTextAndRowsWithoutControlSequences() {
        String family = "👨‍👩‍👧‍👦";
        String content = ESC + "[31mA中" + ESC + "[0m" + family + "e\u0301Z\nnext";
        var range = new TerminalTextSelection.Range(
                new TerminalTextSelection.Point(0, 1), new TerminalTextSelection.Point(1, 2));

        String selected = TerminalScreenCells.selectedText(content, range, 65_536).orElseThrow();

        assertThat(selected).isEqualTo("中" + family + "e\u0301Z\nnex");
        assertThat(selected).doesNotContain(ESC);
    }

    @Test
    void expandsASelectionInsideADoubleWidthCellToTheWholeGrapheme() {
        var range = new TerminalTextSelection.Range(
                new TerminalTextSelection.Point(0, 1), new TerminalTextSelection.Point(0, 1));

        assertThat(TerminalScreenCells.selectedText("A中B", range, 100)).contains("中");
    }

    @Test
    void highlightsOnlySelectedCellsAndPreservesExistingAnsiStyles() {
        String content = ESC + "[31mred" + ESC + "[0m plain";
        var range = new TerminalTextSelection.Range(
                new TerminalTextSelection.Point(0, 1), new TerminalTextSelection.Point(0, 4));

        String highlighted = TerminalScreenCells.highlight(content, range);

        assertThat(highlighted)
                .contains(ESC + "[31m")
                .contains(ESC + "[7m")
                .contains(ESC + "[27m");
        assertThat(com.williamcallahan.tui4j.compat.x.ansi.Strip.strip(highlighted))
                .isEqualTo("red plain");
    }

    @Test
    void rejectsOversizedClipboardPayloadAndFiltersUnsafeControls() {
        var full = new TerminalTextSelection.Range(
                new TerminalTextSelection.Point(0, 0), new TerminalTextSelection.Point(0, 20));

        assertThat(TerminalScreenCells.selectedText("safe\u0000text\u001B[31m!", full, 100))
                .contains("safetext!");
        assertThat(TerminalScreenCells.selectedText("too long", full, 3)).isEmpty();
    }
}
