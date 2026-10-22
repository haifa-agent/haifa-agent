package io.haifa.agent.application.coding.terminal.tui4j;

import static org.assertj.core.api.Assertions.assertThat;

import com.williamcallahan.tui4j.ansi.TextWidth;
import com.williamcallahan.tui4j.compat.lipgloss.Renderer;
import com.williamcallahan.tui4j.compat.lipgloss.color.ColorProfile;
import com.williamcallahan.tui4j.compat.lipgloss.color.NoColor;
import com.williamcallahan.tui4j.compat.x.ansi.Strip;
import com.williamcallahan.tui4j.term.TerminalInfo;
import io.haifa.agent.application.coding.terminal.markdown.render.AstMarkdownRenderer;
import io.haifa.agent.application.coding.terminal.markdown.render.MarkdownRenderer;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AstMarkdownRendererTest {
    private final Tui4jTerminalTheme theme = new Tui4jTerminalTheme();
    private final MarkdownRenderer markdown = MarkdownRendererFactory.create(theme, null, null);

    @BeforeEach
    @AfterEach
    void noColor() {
        Renderer.defaultRenderer().setColorProfile(ColorProfile.Ascii);
        Renderer.defaultRenderer().setHasDarkBackground(false);
        TerminalInfo.provide(() -> new TerminalInfo(false, new NoColor()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bothImplementationsPreserveSafeTextAndCacheLifecycle(boolean legacy) {
        MarkdownRenderer renderer = MarkdownRendererFactory.create(theme, legacy ? "legacy" : null, null);
        String input = "# Title\n\n中文 " + "content ".repeat(10) + "\n\u001B[31mSafe\u001B[0m\n";
        String rendered = renderer.render("doc", input, 20);
        assertThat(rendered).contains("Title", "中文", "Safe").doesNotContain("\u001B", "[31m");
        assertThat(rendered.lines()).allMatch(line -> TextWidth.measureCellWidth(line) <= 20);
        var before = renderer.metrics("doc");
        renderer.render("doc", input, 40);
        assertThat(renderer.metrics("doc").fullParseCount()).isEqualTo(before.fullParseCount());
        assertThat(renderer.metrics("doc").totalParsedCharacters()).isEqualTo(before.totalParsedCharacters());
        assertThat(renderer.render("blank", "\n\u0007", 20)).isEmpty();
        renderer.retain(Set.of("blank"));
        assertThat(renderer.metrics("doc")).isEqualTo(new MarkdownRenderer.ParseMetrics(0, 0, 0));
        renderer.resetAll();
        assertThat(renderer.metrics("blank")).isEqualTo(new MarkdownRenderer.ParseMetrics(0, 0, 0));
    }

    @Test
    void factoryHonorsPropertyPrecedenceAndOnlyExplicitLegacy() {
        assertThat(markdown).isInstanceOf(AstMarkdownRenderer.class);
        assertThat(MarkdownRendererFactory.create(theme, null, "legacy")).isInstanceOf(LegacyMarkdownRenderer.class);
        assertThat(MarkdownRendererFactory.create(theme, "legacy", "unknown"))
                .isInstanceOf(LegacyMarkdownRenderer.class);
        assertThat(MarkdownRendererFactory.create(theme, "unknown", "legacy")).isInstanceOf(AstMarkdownRenderer.class);
    }

    @Test
    void spacesBlocksAndPreservesInternalCodeBlanks() {
        String rendered = markdown.render("blocks", "# Title\na\nb\n- one\n- two\n```text\nx\n\ny\n```\nend", 30);
        assertThat(rendered).contains("Title\n\na\nb\n\n• one\n• two\n\n▎ code · text");
        assertThat(rendered.lines().filter(line -> line.startsWith("▎")).map(String::stripTrailing))
                .containsExactly("▎ code · text", "▎ x", "▎", "▎ y");
        assertThat(rendered).endsWith("\n\nend");
    }

    @Test
    void rendersNestedBlocksAndThreeListLevels() {
        String input =
                "- first\n  - second\n    - third\n      - fourth\n\n  ```java\n  int x = 1;\n  ```\n\n> - quoted\n";
        String rendered = markdown.render("nested", input, 60);
        assertThat(rendered).contains("• first\n  • second\n    • third\n      • fourth", "▎ int x = 1;", "│ ");
        assertThat(rendered.lines()).allMatch(line -> TextWidth.measureCellWidth(line) <= 60);
    }

    @Test
    void streamingPartitionDoesNotChangeFinalTreeOrRendering() {
        String input =
                "# Title\n\n- first\n\n  paragraph\n  ```java\n  /* comment\n   */ int x;\n  ```\n  - nested\n\n> - quote\n>   next\n\n| 中 | Code |\n| --- | --- |\n| 文本 | `a|b` |\n\nend";
        for (int step : new int[] {1, 2, 7, 19, input.length()}) {
            MarkdownRenderer renderer = MarkdownRendererFactory.create(theme, null, null);
            for (int end = step; end < input.length(); end += step) renderer.render("doc", input.substring(0, end), 32);
            assertThat(renderer.render("doc", input, 32)).isEqualTo(markdown.render("expected", input, 32));
        }
    }

    @Test
    void reusesFrozenPrefixAndRebuildsAfterReplacementAndRollover() {
        String first = "# Title\n\nparagraph\n\n- one\n";
        markdown.render("doc", first, 40);
        var before = markdown.metrics("doc");
        String next = first + "- two\n";
        markdown.render("doc", next, 40);
        assertThat(markdown.metrics("doc").fullParseCount()).isEqualTo(before.fullParseCount());
        assertThat(markdown.metrics("doc").totalParsedCharacters() - before.totalParsedCharacters())
                .isLessThan(next.length());
        assertThat(markdown.render("doc", "tail only", 40)).isEqualTo("tail only");
        assertThat(markdown.metrics("doc").fullParseCount()).isEqualTo(2);
        assertThat(markdown.render("doc", "only", 40)).isEqualTo("only");
        assertThat(markdown.metrics("doc").fullParseCount()).isEqualTo(3);
    }

    @Test
    void tablesWrapCjkAndInlineCellsAndStackWhenTooNarrow() {
        String input =
                "| 名称 | Description |\n| --- | --- |\n| 中文 | **bold** and `code` words |\n| 第二 | longer content |";
        for (int width : new int[] {12, 20, 40}) {
            String rendered = markdown.render("table", input, width);
            assertThat(rendered.lines()).allMatch(line -> TextWidth.measureCellWidth(line) <= width);
            assertThat(rendered).contains("中", "文").doesNotContain("**", "`");
        }
        String stacked = markdown.render("stack", "| A | B | C |\n| --- | --- | --- |\n| one | two | three |", 12);
        assertThat(stacked).contains("A: one", "B: two", "C: three");
    }

    @Test
    void coloredCodeAndNestedInlineStylesStayWithinWidthAndThemeResetRepaints() {
        Renderer.defaultRenderer().setColorProfile(ColorProfile.TrueColor);
        Renderer.defaultRenderer().setHasDarkBackground(true);
        String input = "# **Title**\n```java\n/* comment\n */ int x = 1;\n```\n**bold and *soft***";
        String first = markdown.render("color", input, 25);
        assertThat(first).contains("\u001B[");
        assertThat(Strip.strip(first)).contains("bold and soft");
        assertThat(first.lines()).allMatch(line -> TextWidth.measureCellWidth(line) <= 25);
        theme.switchTheme("nord");
        markdown.resetAll();
        assertThat(markdown.render("color", input, 25)).isNotEqualTo(first);
    }

    @Test
    void reusesCodeRenderingUntilWidthOrThemeChanges() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        MarkdownRenderer renderer = new AstMarkdownRenderer(theme::markdownTokens, (language, lines) -> {
            calls.incrementAndGet();
            return lines;
        });
        String source = "```java\nint x;\n```\n\nparagraph";
        renderer.render("doc", source, 40);
        renderer.render("doc", source + " more", 40);
        assertThat(calls.get()).isEqualTo(1);
        var parsed = renderer.metrics("doc");
        renderer.render("doc", source + " more", 20);
        assertThat(calls.get()).isEqualTo(2);
        assertThat(renderer.metrics("doc").totalParsedCharacters()).isEqualTo(parsed.totalParsedCharacters());
        theme.switchTheme("nord");
        renderer.render("doc", source + " more", 20);
        assertThat(calls.get()).isEqualTo(3);
    }
}
