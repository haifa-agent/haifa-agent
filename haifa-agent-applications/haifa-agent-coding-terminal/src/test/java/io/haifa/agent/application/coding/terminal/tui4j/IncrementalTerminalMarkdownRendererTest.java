package io.haifa.agent.application.coding.terminal.tui4j;

import static org.assertj.core.api.Assertions.assertThat;

import com.williamcallahan.tui4j.ansi.TextWidth;
import com.williamcallahan.tui4j.compat.lipgloss.Renderer;
import com.williamcallahan.tui4j.compat.lipgloss.color.ColorProfile;
import com.williamcallahan.tui4j.compat.lipgloss.color.NoColor;
import com.williamcallahan.tui4j.compat.x.ansi.Strip;
import com.williamcallahan.tui4j.term.TerminalInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IncrementalTerminalMarkdownRendererTest {
    private final Renderer renderer = Renderer.defaultRenderer();
    private final Tui4jTerminalTheme theme = new Tui4jTerminalTheme();
    private final IncrementalTerminalMarkdownRenderer markdownRenderer = new IncrementalTerminalMarkdownRenderer(theme);

    @BeforeEach
    void setUp() {
        renderer.setColorProfile(ColorProfile.Ascii);
        renderer.setHasDarkBackground(false);
        TerminalInfo.provide(() -> new TerminalInfo(false, new NoColor()));
    }

    @AfterEach
    void tearDown() {
        renderer.setColorProfile(ColorProfile.Ascii);
        renderer.setHasDarkBackground(false);
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

        String rendered = markdownRenderer.render("assistant-1", markdown, 80);

        assertThat(rendered)
                .containsSubsequence(
                        "Heading",
                        "Paragraph with bold, emphasis, inline code, and docs",
                        "(https://example.test/docs).",
                        "• first",
                        "  2. second",
                        "│ quoted",
                        "code · java",
                        "▎ int value = 1;")
                .doesNotContain("# Heading", "**", "```", "\u001B");
    }

    @Test
    void consumesOnlyTheAppendedSuffixAcrossStreamingUpdates() {
        String first = "# Result\n```java\nint";
        markdownRenderer.render("assistant-1", first, 80);

        assertThat(markdownRenderer.metrics("assistant-1"))
                .isEqualTo(new IncrementalTerminalMarkdownRenderer.ParseMetrics(1, first.length(), first.length()));

        String delta = " value = 1;\n```\nDone with **tests";
        String complete = first + delta;
        String rendered = markdownRenderer.render("assistant-1", complete, 80);

        assertThat(markdownRenderer.metrics("assistant-1"))
                .isEqualTo(new IncrementalTerminalMarkdownRenderer.ParseMetrics(1, delta.length(), complete.length()));
        assertThat(rendered).contains("int value = 1;", "Done with tests").doesNotContain("```", "**");

        markdownRenderer.render("assistant-1", complete, 80);
        assertThat(markdownRenderer.metrics("assistant-1").lastDeltaCharacters())
                .isZero();
        assertThat(markdownRenderer.metrics("assistant-1").totalParsedCharacters())
                .isEqualTo(complete.length());
    }

    @Test
    void safelyRebuildsOnlyWhenTheAuthoritativeBodyIsReplaced() {
        markdownRenderer.render("assistant-1", "old body", 80);

        String rendered = markdownRenderer.render("assistant-1", "replacement", 80);

        assertThat(rendered).isEqualTo("replacement");
        assertThat(markdownRenderer.metrics("assistant-1").fullParseCount()).isEqualTo(2);
        assertThat(markdownRenderer.metrics("assistant-1").lastDeltaCharacters())
                .isEqualTo("replacement".length());
    }

    @Test
    void stripsControlCharactersAndWrapsCjkByTerminalCells() {
        String rendered = markdownRenderer.render("assistant-1", "# safe\u001B[31m\rhidden\n" + "中文".repeat(24), 20);

        assertThat(rendered).doesNotContain("\u001B", "\r", "# safe");
        assertThat(rendered.lines()).allMatch(line -> TextWidth.measureCellWidth(line) <= 20);
    }

    @Test
    void rendersSyntaxHighlightedCodeBlocksUnderTrueColor() {
        renderer.setColorProfile(ColorProfile.TrueColor);
        renderer.setHasDarkBackground(true);

        String markdown =
                """
                ```python
                def greet(name: str):
                    return f"Hello, {name}!"
                ```
                """;

        String rendered = markdownRenderer.render("py-test", markdown, 80);

        assertThat(rendered).contains("\u001B[");
        assertThat(Strip.strip(rendered))
                .contains("code · python")
                .contains("def greet(name: str):")
                .contains("return f\"Hello, {name}!\"");
    }

    @Test
    void handlesStreamingUnclosedCodeFenceGracefully() {
        renderer.setColorProfile(ColorProfile.TrueColor);
        renderer.setHasDarkBackground(true);

        String unclosedStreamingMarkdown =
                """
                ```java
                public class App {
                """;

        String rendered = markdownRenderer.render("stream-1", unclosedStreamingMarkdown, 80);

        assertThat(Strip.strip(rendered)).contains("code · java").contains("public class App {");
    }

    @Test
    void resizeReflowsWithoutRelexingCompletedDocument() {
        String markdown =
                """
                ```java
                public static void main(String[] args) {
                    System.out.println("Line 1");
                }
                ```
                """;

        markdownRenderer.render("resize-test", markdown, 80);
        int initialParseCount = markdownRenderer.metrics("resize-test").fullParseCount();
        long initialChars = markdownRenderer.metrics("resize-test").totalParsedCharacters();

        // Resize from 80 columns to 40 columns
        String resized = markdownRenderer.render("resize-test", markdown, 40);

        assertThat(markdownRenderer.metrics("resize-test").fullParseCount()).isEqualTo(initialParseCount);
        assertThat(markdownRenderer.metrics("resize-test").totalParsedCharacters())
                .isEqualTo(initialChars);
        assertThat(resized).contains("public static void main");
    }

    @Test
    void resetAllClearsDocumentStateForThemeSwitching() {
        markdownRenderer.render("doc-1", "```java\nint x = 1;\n```", 80);
        assertThat(markdownRenderer.metrics("doc-1").totalParsedCharacters()).isGreaterThan(0);

        theme.switchTheme("nord");
        markdownRenderer.resetAll();

        assertThat(markdownRenderer.metrics("doc-1").totalParsedCharacters()).isZero();
    }

    @Test
    void handlesMultilineBlockCommentsInStreamingCodeFence() {
        renderer.setColorProfile(ColorProfile.TrueColor);
        renderer.setHasDarkBackground(true);

        String doc = "```java\n/*\n * Comment\n */\nint a = 1;\n```";
        String rendered = markdownRenderer.render("doc-comment", doc, 80);

        assertThat(rendered).contains("\u001B[");
        assertThat(Strip.strip(rendered)).contains("/*", "* Comment", "*/", "int a = 1;");
    }

    @Test
    void resizeWithPendingUncompletedLineReusesCachedLine() {
        String chunk = "```java\nint x = 10;\nString s = \"pending";
        String initial = markdownRenderer.render("doc-pending", chunk, 80);
        long charsBeforeResize = markdownRenderer.metrics("doc-pending").totalParsedCharacters();

        String resized = markdownRenderer.render("doc-pending", chunk, 100);
        long charsAfterResize = markdownRenderer.metrics("doc-pending").totalParsedCharacters();

        assertThat(charsAfterResize).isEqualTo(charsBeforeResize);
        assertThat(Strip.strip(resized)).contains("String s = \"pending");
    }

    @Test
    void spacesBlocksWithoutSplittingParagraphsListsOrCode() {
        String input = "# Title\nfirst\nsecond\n- one\n- two\n```text\na\n\nb\n```\nlast";
        assertThat(markdownRenderer.render("spacing", input, 60)).isEqualTo(
                "Title\n\nfirst\nsecond\n\n• one\n• two\n\n▎ code · text\n▎ a\n▎ \n▎ b\n\nlast");
    }

    @Test
    void collapsesOnlyStructuralBlankLinesAndRecognizesRules() {
        assertThat(markdownRenderer.render("blank", "\n\na\n\n\nb\n- - -\nc\n\n", 20))
                .isEqualTo("a\n\nb\n\n" + "─".repeat(20) + "\n\nc");
    }

    @Test
    void retainsThreeLevelsAndProtectsNarrowListContent() {
        String value = "  - a\n    - b\n      - c\n        - d";
        assertThat(markdownRenderer.render("list", value, 60))
                .isEqualTo("  • a\n    • b\n      • c\n      • d");
        assertThat(markdownRenderer.render("list", value, 12))
                .isEqualTo("• a\n• b\n• c\n• d");
    }

    @Test
    void reusesCompletedRenderingAndInvalidatesOnReplacementOrResize() {
        markdownRenderer.render("cache", "one\ntwo", 30);
        markdownRenderer.render("cache", "one\ntwo more", 30);
        assertThat(markdownRenderer.lastRenderedLines()).isEqualTo(1);
        markdownRenderer.render("cache", "one\ntwo more", 30);
        assertThat(markdownRenderer.lastRenderedLines()).isZero();
        markdownRenderer.render("cache", "one\ntwo more", 20);
        assertThat(markdownRenderer.lastRenderedLines()).isEqualTo(2);
        assertThat(markdownRenderer.render("cache", "replacement", 20)).isEqualTo("replacement");
    }
}
