package io.haifa.agent.application.coding.terminal.tui4j;

import static org.assertj.core.api.Assertions.assertThat;

import com.williamcallahan.tui4j.ansi.TextWidth;
import com.williamcallahan.tui4j.compat.lipgloss.color.NoColor;
import com.williamcallahan.tui4j.term.TerminalInfo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class IncrementalTerminalMarkdownRendererTest {
    private final IncrementalTerminalMarkdownRenderer renderer =
            new IncrementalTerminalMarkdownRenderer(new Tui4jTerminalTheme());

    @BeforeAll
    static void configureHeadlessTerminalInfo() {
        TerminalInfo.provide(() -> new TerminalInfo(false, new NoColor()));
    }

    @Test
    void rendersTheSupportedMarkdownSubsetAsReadableTerminalText() {
        String markdown =
                """
                # Heading
                Paragraph with **bold**, *emphasis*, `inline code`, and [docs](https://example.test/docs).

                - first
                  2. second
                > quoted
                ```java
                int value = 1;
                ```
                """;

        String rendered = renderer.render("assistant-1", markdown, 80);

        assertThat(rendered)
                .containsSubsequence(
                        "Heading",
                        "Paragraph with bold, emphasis, inline code, and docs",
                        "(https://example.test/docs).",
                        "• first",
                        "  2. second",
                        "│ quoted",
                        "code · java",
                        "  int value = 1;")
                .doesNotContain("# Heading", "**", "```", "\u001B");
    }

    @Test
    void consumesOnlyTheAppendedSuffixAcrossStreamingUpdates() {
        String first = "# Result\n```java\nint";
        renderer.render("assistant-1", first, 80);

        assertThat(renderer.metrics("assistant-1"))
                .isEqualTo(new IncrementalTerminalMarkdownRenderer.ParseMetrics(1, first.length(), first.length()));

        String delta = " value = 1;\n```\nDone with **tests";
        String complete = first + delta;
        String rendered = renderer.render("assistant-1", complete, 80);

        assertThat(renderer.metrics("assistant-1"))
                .isEqualTo(new IncrementalTerminalMarkdownRenderer.ParseMetrics(1, delta.length(), complete.length()));
        assertThat(rendered).contains("int value = 1;", "Done with tests").doesNotContain("```", "**");

        renderer.render("assistant-1", complete, 80);
        assertThat(renderer.metrics("assistant-1").lastDeltaCharacters()).isZero();
        assertThat(renderer.metrics("assistant-1").totalParsedCharacters()).isEqualTo(complete.length());
    }

    @Test
    void safelyRebuildsOnlyWhenTheAuthoritativeBodyIsReplaced() {
        renderer.render("assistant-1", "old body", 80);

        String rendered = renderer.render("assistant-1", "replacement", 80);

        assertThat(rendered).isEqualTo("replacement");
        assertThat(renderer.metrics("assistant-1").fullParseCount()).isEqualTo(2);
        assertThat(renderer.metrics("assistant-1").lastDeltaCharacters()).isEqualTo("replacement".length());
    }

    @Test
    void stripsControlCharactersAndWrapsCjkByTerminalCells() {
        String rendered = renderer.render("assistant-1", "# safe\u001B[31m\rhidden\n" + "中文".repeat(24), 20);

        assertThat(rendered).doesNotContain("\u001B", "\r", "# safe");
        assertThat(rendered.lines()).allMatch(line -> TextWidth.measureCellWidth(line) <= 20);
    }
}
