package io.haifa.agent.application.coding.terminal.tui4j;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TerminalTextSelectionTest {
    @Test
    void normalizesForwardAndReverseSelectionsWithoutLosingTheAnchor() {
        var selection = new TerminalTextSelection();

        selection.start(new TerminalTextSelection.Point(4, 8));
        selection.extend(new TerminalTextSelection.Point(7, 3));

        assertThat(selection.range())
                .contains(new TerminalTextSelection.Range(
                        new TerminalTextSelection.Point(4, 8), new TerminalTextSelection.Point(7, 3)));
        assertThat(selection.selecting()).isTrue();

        selection.extend(new TerminalTextSelection.Point(2, 5));
        selection.finish(new TerminalTextSelection.Point(2, 5));

        assertThat(selection.range())
                .contains(new TerminalTextSelection.Range(
                        new TerminalTextSelection.Point(2, 5), new TerminalTextSelection.Point(4, 8)));
        assertThat(selection.selecting()).isFalse();
    }

    @Test
    void clearRemovesBothAnActiveAndACompletedSelection() {
        var selection = new TerminalTextSelection();
        selection.start(new TerminalTextSelection.Point(1, 1));
        selection.extend(new TerminalTextSelection.Point(1, 4));

        selection.clear();

        assertThat(selection.range()).isEmpty();
        assertThat(selection.selecting()).isFalse();
    }
}
