package io.haifa.agent.application.coding.terminal.tui4j;

import com.williamcallahan.tui4j.compat.lipgloss.Renderer;
import com.williamcallahan.tui4j.compat.lipgloss.Style;
import com.williamcallahan.tui4j.compat.lipgloss.color.AdaptiveColor;
import com.williamcallahan.tui4j.compat.lipgloss.color.ColorProfile;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Semantic terminal styles and in-memory theme palettes that degrade gracefully
 * when color is unavailable.
 */
final class Tui4jTerminalTheme {
    public static final String DEFAULT_THEME = "dark";

    record Palette(
            String name,
            AdaptiveColor accent,
            AdaptiveColor muted,
            AdaptiveColor surfacePanel,
            AdaptiveColor surfaceElement,
            AdaptiveColor borderSubtle,
            AdaptiveColor text,
            AdaptiveColor userForeground,
            AdaptiveColor userBackground,
            AdaptiveColor successForeground,
            AdaptiveColor successBackground,
            AdaptiveColor pendingForeground,
            AdaptiveColor pendingBackground,
            AdaptiveColor errorAccent,
            AdaptiveColor errorForeground,
            AdaptiveColor errorBackground,
            AdaptiveColor queuedForeground,
            AdaptiveColor queuedBackground,
            AdaptiveColor editorBackground,
            AdaptiveColor focus,
            AdaptiveColor selectionForeground,
            AdaptiveColor selectionBackground,
            AdaptiveColor syntaxKeyword,
            AdaptiveColor syntaxString,
            AdaptiveColor syntaxComment,
            AdaptiveColor syntaxNumber,
            AdaptiveColor syntaxType,
            AdaptiveColor syntaxLiteral,
            AdaptiveColor syntaxPunctuation) {}

    private static final Palette DARK_PALETTE = new Palette(
            "dark",
            new AdaptiveColor("#0F6F6E", "#79C7C5"),
            new AdaptiveColor("#71717A", "#A1A1AA"),
            new AdaptiveColor("#F4F4F5", "#27272A"),
            new AdaptiveColor("#E4E4E7", "#3F3F46"),
            new AdaptiveColor("#E4E4E7", "#3F3F46"),
            new AdaptiveColor("#09090B", "#F4F4F5"),
            new AdaptiveColor("#09090B", "#F4F4F5"),
            new AdaptiveColor("#F4F4F5", "#27272A"),
            new AdaptiveColor("#225C35", "#A8D5B0"),
            new AdaptiveColor("#EAF4EC", "#26372A"),
            new AdaptiveColor("#795A12", "#EAC35B"),
            new AdaptiveColor("#FFF4D6", "#3B3323"),
            new AdaptiveColor("#DC2626", "#EF4444"),
            new AdaptiveColor("#9A3535", "#E9A3A3"),
            new AdaptiveColor("#FEF2F2", "#450A0A"),
            new AdaptiveColor("#315C80", "#A9C7DF"),
            new AdaptiveColor("#E8F0F7", "#29343F"),
            new AdaptiveColor("#E4E4E7", "#3F3F46"),
            new AdaptiveColor("#87427F", "#B06AA6"),
            new AdaptiveColor("#FFFFFF", "#FFFFFF"),
            new AdaptiveColor("#6A2F63", "#6A2F63"),
            new AdaptiveColor("#C678DD", "#C678DD"),
            new AdaptiveColor("#98C379", "#98C379"),
            new AdaptiveColor("#71717A", "#6B7280"),
            new AdaptiveColor("#D19A66", "#D19A66"),
            new AdaptiveColor("#E5C07B", "#E5C07B"),
            new AdaptiveColor("#61AFEF", "#61AFEF"),
            new AdaptiveColor("#ABB2BF", "#D4D4D8"));

    private static final Palette LIGHT_PALETTE = new Palette(
            "light",
            new AdaptiveColor("#0F6F6E", "#0F6F6E"),
            new AdaptiveColor("#64748B", "#64748B"),
            new AdaptiveColor("#F1F5F9", "#F1F5F9"),
            new AdaptiveColor("#E2E8F0", "#E2E8F0"),
            new AdaptiveColor("#CBD5E1", "#CBD5E1"),
            new AdaptiveColor("#0F172A", "#0F172A"),
            new AdaptiveColor("#0F172A", "#0F172A"),
            new AdaptiveColor("#F1F5F9", "#F1F5F9"),
            new AdaptiveColor("#15803D", "#15803D"),
            new AdaptiveColor("#DCFCE7", "#DCFCE7"),
            new AdaptiveColor("#A16207", "#A16207"),
            new AdaptiveColor("#FEF9C3", "#FEF9C3"),
            new AdaptiveColor("#DC2626", "#DC2626"),
            new AdaptiveColor("#B91C1C", "#B91C1C"),
            new AdaptiveColor("#FEE2E2", "#FEE2E2"),
            new AdaptiveColor("#0369A1", "#0369A1"),
            new AdaptiveColor("#E0F2FE", "#E0F2FE"),
            new AdaptiveColor("#E2E8F0", "#E2E8F0"),
            new AdaptiveColor("#7E22CE", "#7E22CE"),
            new AdaptiveColor("#FFFFFF", "#FFFFFF"),
            new AdaptiveColor("#0F6F6E", "#0F6F6E"),
            new AdaptiveColor("#A626A4", "#A626A4"),
            new AdaptiveColor("#50A14F", "#50A14F"),
            new AdaptiveColor("#A0A1A7", "#A0A1A7"),
            new AdaptiveColor("#986801", "#986801"),
            new AdaptiveColor("#C18401", "#C18401"),
            new AdaptiveColor("#4078F2", "#4078F2"),
            new AdaptiveColor("#383A42", "#383A42"));

    private static final Palette HIGH_CONTRAST_PALETTE = new Palette(
            "high-contrast",
            new AdaptiveColor("#00FFFF", "#00FFFF"),
            new AdaptiveColor("#A0A0A0", "#A0A0A0"),
            new AdaptiveColor("#121212", "#121212"),
            new AdaptiveColor("#222222", "#222222"),
            new AdaptiveColor("#555555", "#555555"),
            new AdaptiveColor("#FFFFFF", "#FFFFFF"),
            new AdaptiveColor("#FFFFFF", "#FFFFFF"),
            new AdaptiveColor("#121212", "#121212"),
            new AdaptiveColor("#00FF00", "#00FF00"),
            new AdaptiveColor("#003300", "#003300"),
            new AdaptiveColor("#FFFF00", "#FFFF00"),
            new AdaptiveColor("#333300", "#333300"),
            new AdaptiveColor("#FF0000", "#FF0000"),
            new AdaptiveColor("#FF5555", "#FF5555"),
            new AdaptiveColor("#330000", "#330000"),
            new AdaptiveColor("#00FFFF", "#00FFFF"),
            new AdaptiveColor("#002233", "#002233"),
            new AdaptiveColor("#222222", "#222222"),
            new AdaptiveColor("#FF00FF", "#FF00FF"),
            new AdaptiveColor("#000000", "#000000"),
            new AdaptiveColor("#00FFFF", "#00FFFF"),
            new AdaptiveColor("#FF77FF", "#FF77FF"),
            new AdaptiveColor("#55FF55", "#55FF55"),
            new AdaptiveColor("#888888", "#888888"),
            new AdaptiveColor("#FFAA00", "#FFAA00"),
            new AdaptiveColor("#FFFF55", "#FFFF55"),
            new AdaptiveColor("#55FFFF", "#55FFFF"),
            new AdaptiveColor("#FFFFFF", "#FFFFFF"));

    private static final Palette NORD_PALETTE = new Palette(
            "nord",
            new AdaptiveColor("#5E81AC", "#88C0D0"), // accent
            new AdaptiveColor("#4C566A", "#D8DEE9"), // muted
            new AdaptiveColor("#E5E9F0", "#3B4252"), // surfacePanel
            new AdaptiveColor("#D8DEE9", "#434C5E"), // surfaceElement
            new AdaptiveColor("#D8DEE9", "#4C566A"), // borderSubtle
            new AdaptiveColor("#2E3440", "#ECEFF4"), // text
            new AdaptiveColor("#2E3440", "#ECEFF4"), // userForeground
            new AdaptiveColor("#E5E9F0", "#3B4252"), // userBackground
            new AdaptiveColor("#4E8752", "#A3BE8C"), // successForeground
            new AdaptiveColor("#EBF5EA", "#2E3830"), // successBackground
            new AdaptiveColor("#946300", "#EBCB8B"), // pendingForeground
            new AdaptiveColor("#FEF9E7", "#3B3828"), // pendingBackground
            new AdaptiveColor("#BF616A", "#BF616A"), // errorAccent
            new AdaptiveColor("#A53B44", "#D08770"), // errorForeground
            new AdaptiveColor("#FDF2F2", "#3B282A"), // errorBackground
            new AdaptiveColor("#436685", "#81A1C1"), // queuedForeground
            new AdaptiveColor("#EAF2F8", "#2E3440"), // queuedBackground
            new AdaptiveColor("#E5E9F0", "#434C5E"), // editorBackground
            new AdaptiveColor("#8F4F82", "#B48EAD"), // focus
            new AdaptiveColor("#ECEFF4", "#2E3440"), // selectionForeground
            new AdaptiveColor("#5E81AC", "#88C0D0"), // selectionBackground
            new AdaptiveColor("#5E81AC", "#81A1C1"), // syntaxKeyword
            new AdaptiveColor("#4E8752", "#A3BE8C"), // syntaxString
            new AdaptiveColor("#616E88", "#7B88A1"), // syntaxComment
            new AdaptiveColor("#995886", "#B48EAD"), // syntaxNumber
            new AdaptiveColor("#2E6F7D", "#8FBCBB"), // syntaxType
            new AdaptiveColor("#3B728C", "#88C0D0"), // syntaxLiteral
            new AdaptiveColor("#2E3440", "#ECEFF4")); // syntaxPunctuation

    private static final Map<String, Palette> PALETTES = Map.of(
            "dark", DARK_PALETTE,
            "light", LIGHT_PALETTE,
            "high-contrast", HIGH_CONTRAST_PALETTE,
            "nord", NORD_PALETTE);

    private Palette palette = DARK_PALETTE;

    Tui4jTerminalTheme() {
        this(DARK_PALETTE);
    }

    Tui4jTerminalTheme(Palette palette) {
        this.palette = java.util.Objects.requireNonNull(palette, "palette must not be null");
    }

    static List<String> availableThemeNames() {
        return List.of("dark", "light", "high-contrast", "nord");
    }

    String currentThemeName() {
        return palette.name();
    }

    boolean switchTheme(String name) {
        if (name == null || name.isBlank()) return false;
        Palette found = PALETTES.get(name.strip().toLowerCase(Locale.ROOT));
        if (found != null) {
            this.palette = found;
            return true;
        }
        return false;
    }

    String accent(String value) {
        return Style.newStyle().foreground(palette.accent()).bold(true).render(value);
    }

    String muted(String value) {
        return Style.newStyle().foreground(palette.muted()).faint(true).render(value);
    }

    String focus(String value) {
        return Style.newStyle().foreground(palette.focus()).render(value);
    }

    String editor(String value, int width, int height) {
        return Style.newStyle()
                .background(palette.editorBackground())
                .width(width)
                .height(height)
                .render(value);
    }

    String selected(String value) {
        return Style.newStyle()
                .foreground(palette.selectionForeground())
                .background(palette.selectionBackground())
                .bold(true)
                .render(value);
    }

    String unselected(String value) {
        return Style.newStyle().foreground(palette.muted()).render(value);
    }

    String heading(String value) {
        return Style.newStyle().foreground(palette.accent()).bold(true).render(value);
    }

    String strong(String value) {
        return Style.newStyle().bold(true).render(value);
    }

    String emphasis(String value) {
        return Style.newStyle().italic(true).render(value);
    }

    String inlineCode(String value) {
        return Style.newStyle()
                .foreground(palette.queuedForeground())
                .background(palette.queuedBackground())
                .inline(true)
                .render(value);
    }

    String codeBlock(String value) {
        return Style.newStyle()
                .foreground(palette.userForeground())
                .background(palette.userBackground())
                .render(value);
    }

    String quote(String value) {
        return Style.newStyle().foreground(palette.muted()).italic(true).render(value);
    }

    String link(String value) {
        return Style.newStyle().foreground(palette.accent()).underline(true).render(value);
    }

    String user(String value) {
        return block(value, palette.userForeground(), palette.userBackground());
    }

    String success(String value) {
        return block(value, palette.successForeground(), palette.successBackground());
    }

    String pending(String value) {
        return block(value, palette.pendingForeground(), palette.pendingBackground());
    }

    String error(String value) {
        return Style.newStyle().foreground(palette.errorAccent()).bold(true).render(value);
    }

    String queued(String value) {
        return block(value, palette.queuedForeground(), palette.queuedBackground());
    }

    String warning(String value) {
        return Style.newStyle()
                .foreground(palette.pendingForeground())
                .bold(true)
                .render(value);
    }

    String borderSubtle(String value) {
        return Style.newStyle().foreground(palette.borderSubtle()).render(value);
    }

    String userCardLine(String paddedContent, boolean narrow, boolean isHeader) {
        String border = narrow ? " " : "│ ";
        String styledBorder =
                Style.newStyle().foreground(palette.accent()).bold(true).render(border);
        Style contentStyle = Style.newStyle()
                .foreground(isHeader ? palette.accent() : palette.userForeground())
                .background(palette.userBackground());
        if (isHeader) {
            contentStyle = contentStyle.bold(true);
        }
        String styledContent = contentStyle.render(paddedContent);
        return styledBorder + styledContent + " ";
    }

    String errorCardLine(String content, boolean narrow, boolean isHeader) {
        String border = narrow ? " " : "│ ";
        String styledBorder =
                Style.newStyle().foreground(palette.errorAccent()).bold(true).render(border);
        Style contentStyle = Style.newStyle().foreground(isHeader ? palette.errorAccent() : palette.text());
        if (isHeader) {
            contentStyle = contentStyle.bold(true);
        }
        String styledContent = content.isEmpty() ? "" : contentStyle.render(content);
        return styledBorder + styledContent;
    }

    String syntaxKeyword(String value) {
        return Style.newStyle()
                .foreground(palette.syntaxKeyword())
                .background(palette.userBackground())
                .bold(true)
                .render(value);
    }

    String syntaxString(String value) {
        return Style.newStyle()
                .foreground(palette.syntaxString())
                .background(palette.userBackground())
                .render(value);
    }

    String syntaxComment(String value) {
        return Style.newStyle()
                .foreground(palette.syntaxComment())
                .background(palette.userBackground())
                .faint(true)
                .render(value);
    }

    String syntaxNumber(String value) {
        return Style.newStyle()
                .foreground(palette.syntaxNumber())
                .background(palette.userBackground())
                .render(value);
    }

    String syntaxType(String value) {
        return Style.newStyle()
                .foreground(palette.syntaxType())
                .background(palette.userBackground())
                .render(value);
    }

    String syntaxLiteral(String value) {
        return Style.newStyle()
                .foreground(palette.syntaxLiteral())
                .background(palette.userBackground())
                .render(value);
    }

    String syntaxPunctuation(String value) {
        return Style.newStyle()
                .foreground(palette.syntaxPunctuation())
                .background(palette.userBackground())
                .render(value);
    }

    String syntaxPlain(String value) {
        return Style.newStyle()
                .foreground(palette.userForeground())
                .background(palette.userBackground())
                .render(value);
    }

    AdaptiveColor codeBlockBackground() {
        return palette.userBackground();
    }

    AdaptiveColor codeBlockForeground() {
        return palette.userForeground();
    }

    private static boolean isColorDisabled() {
        return Renderer.defaultRenderer().colorProfile() == ColorProfile.Ascii;
    }

    String diffAdded(String value) {
        if (isColorDisabled()) {
            return value;
        }
        return Style.newStyle().foreground(palette.successForeground()).render(value);
    }

    String diffRemoved(String value) {
        if (isColorDisabled()) {
            return value;
        }
        return Style.newStyle().foreground(palette.errorForeground()).render(value);
    }

    String diffHunk(String value) {
        if (isColorDisabled()) {
            return value;
        }
        return Style.newStyle().foreground(palette.accent()).bold(true).render(value);
    }

    String diffHeader(String value) {
        if (isColorDisabled()) {
            return value;
        }
        return Style.newStyle()
                .foreground(palette.queuedForeground())
                .bold(true)
                .render(value);
    }

    String toolBadge(ToolCategory category) {
        if (isColorDisabled()) {
            return "[" + category.label().toLowerCase(Locale.ROOT) + "]";
        }
        AdaptiveColor fg =
                switch (category) {
                    case READ -> palette.queuedForeground();
                    case EDIT -> palette.focus();
                    case EXEC -> palette.pendingForeground();
                    case SEARCH -> palette.accent();
                    case TOOL -> palette.muted();
                };
        AdaptiveColor bg =
                switch (category) {
                    case READ -> palette.queuedBackground();
                    case EDIT -> palette.surfaceElement();
                    case EXEC -> palette.pendingBackground();
                    case SEARCH -> palette.surfaceElement();
                    case TOOL -> palette.surfaceElement();
                };
        return Style.newStyle()
                .foreground(fg)
                .background(bg)
                .paddingLeft(1)
                .paddingRight(1)
                .bold(true)
                .render(category.label());
    }

    private String block(String value, AdaptiveColor foreground, AdaptiveColor background) {
        return Style.newStyle()
                .foreground(foreground)
                .background(background)
                .paddingLeft(1)
                .paddingRight(1)
                .render(value);
    }
}
