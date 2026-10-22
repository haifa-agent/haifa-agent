package io.haifa.agent.application.coding.terminal.markdown.render;

import com.williamcallahan.tui4j.compat.lipgloss.Style;
import com.williamcallahan.tui4j.compat.lipgloss.color.AdaptiveColor;
import io.haifa.agent.application.coding.terminal.markdown.style.MarkdownTokens;
import io.haifa.agent.application.coding.terminal.markdown.style.MarkdownTokens.Color;

/** Converts the product's value tokens to terminal styles at the rendering boundary. */
public final class MarkdownStyles {
    private MarkdownStyles() {}

    public static AdaptiveColor color(Color color) {
        return new AdaptiveColor(color.light(), color.dark());
    }

    public static String heading(MarkdownTokens tokens, int level, String value) {
        return Style.newStyle()
                .foreground(color(level >= 4 ? tokens.muted() : tokens.heading()))
                .bold(level != 3)
                .render(value);
    }

    public static String strong(MarkdownTokens tokens, String value) {
        return Style.newStyle().foreground(color(tokens.strong())).bold(true).render(value);
    }

    public static String emphasis(MarkdownTokens tokens, String value) {
        return Style.newStyle()
                .foreground(color(tokens.emphasis()))
                .italic(true)
                .render(value);
    }

    public static String inlineCode(MarkdownTokens tokens, String value) {
        return Style.newStyle()
                .foreground(color(tokens.codeForeground()))
                .background(color(tokens.codeBackground()))
                .inline(true)
                .render(value);
    }
}
