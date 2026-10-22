package io.haifa.agent.application.coding.terminal.tui4j;

import io.haifa.agent.application.coding.terminal.markdown.render.AstMarkdownRenderer;
import io.haifa.agent.application.coding.terminal.markdown.render.MarkdownRenderer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Chooses once at startup; there is no exception-driven or mid-stream renderer fallback. */
final class MarkdownRendererFactory {
    private static final AtomicBoolean WARNED = new AtomicBoolean();

    private MarkdownRendererFactory() {}

    static MarkdownRenderer create(Tui4jTerminalTheme theme) {
        return create(
                theme,
                System.getProperty("haifa.terminal.markdown.renderer"),
                System.getenv("HAIFA_TERMINAL_MARKDOWN_RENDERER"));
    }

    static MarkdownRenderer create(Tui4jTerminalTheme theme, String property, String environment) {
        String choice = property != null ? property : environment;
        if ("legacy".equals(choice)) return new LegacyMarkdownRenderer(theme);
        if (choice != null && WARNED.compareAndSet(false, true)) {
            System.getLogger(MarkdownRendererFactory.class.getName())
                    .log(
                            System.Logger.Level.WARNING,
                            "Unrecognized terminal Markdown renderer setting; using the AST renderer.");
        }
        return new AstMarkdownRenderer(theme::markdownTokens, (language, lines) -> highlight(theme, language, lines));
    }

    private static List<String> highlight(Tui4jTerminalTheme theme, String language, List<String> lines) {
        List<String> rendered = new ArrayList<>(lines.size());
        boolean comment = false;
        for (String line : lines) {
            TerminalSyntaxHighlighter.HighlightResult result =
                    TerminalSyntaxHighlighter.highlight(theme, language, line, comment);
            rendered.add(result.highlighted());
            comment = result.inBlockComment();
        }
        return rendered;
    }
}
