package io.haifa.agent.application.coding.terminal.tui4j;

import com.williamcallahan.tui4j.compat.lipgloss.Style;
import com.williamcallahan.tui4j.compat.lipgloss.color.AdaptiveColor;

/** Semantic terminal styles that degrade to the same readable text when color is unavailable. */
final class Tui4jTerminalTheme {
    private static final AdaptiveColor ACCENT = new AdaptiveColor("#0F6F6E", "#79C7C5");
    private static final AdaptiveColor MUTED = new AdaptiveColor("#71717A", "#A1A1AA");
    private static final AdaptiveColor SURFACE_PANEL = new AdaptiveColor("#F4F4F5", "#27272A");
    private static final AdaptiveColor SURFACE_ELEMENT = new AdaptiveColor("#E4E4E7", "#3F3F46");
    private static final AdaptiveColor BORDER_SUBTLE = new AdaptiveColor("#E4E4E7", "#3F3F46");
    private static final AdaptiveColor TEXT = new AdaptiveColor("#09090B", "#F4F4F5");
    private static final AdaptiveColor USER_FOREGROUND = TEXT;
    private static final AdaptiveColor USER_BACKGROUND = SURFACE_PANEL;
    private static final AdaptiveColor SUCCESS_FOREGROUND = new AdaptiveColor("#225C35", "#A8D5B0");
    private static final AdaptiveColor SUCCESS_BACKGROUND = new AdaptiveColor("#EAF4EC", "#26372A");
    private static final AdaptiveColor PENDING_FOREGROUND = new AdaptiveColor("#795A12", "#EAC35B");
    private static final AdaptiveColor PENDING_BACKGROUND = new AdaptiveColor("#FFF4D6", "#3B3323");
    private static final AdaptiveColor ERROR_ACCENT = new AdaptiveColor("#DC2626", "#EF4444");
    private static final AdaptiveColor ERROR_FOREGROUND = new AdaptiveColor("#9A3535", "#E9A3A3");
    private static final AdaptiveColor ERROR_BACKGROUND = new AdaptiveColor("#FEF2F2", "#450A0A");
    private static final AdaptiveColor QUEUED_FOREGROUND = new AdaptiveColor("#315C80", "#A9C7DF");
    private static final AdaptiveColor QUEUED_BACKGROUND = new AdaptiveColor("#E8F0F7", "#29343F");
    private static final AdaptiveColor EDITOR_BACKGROUND = SURFACE_ELEMENT;
    private static final AdaptiveColor FOCUS = new AdaptiveColor("#87427F", "#B06AA6");
    private static final AdaptiveColor SELECTION_FOREGROUND = new AdaptiveColor("#FFFFFF", "#FFFFFF");
    private static final AdaptiveColor SELECTION_BACKGROUND = new AdaptiveColor("#6A2F63", "#6A2F63");

    String accent(String value) {
        return Style.newStyle().foreground(ACCENT).bold(true).render(value);
    }

    String muted(String value) {
        return Style.newStyle().foreground(MUTED).faint(true).render(value);
    }

    String focus(String value) {
        return Style.newStyle().foreground(FOCUS).render(value);
    }

    String editor(String value, int width, int height) {
        return Style.newStyle()
                .background(EDITOR_BACKGROUND)
                .width(width)
                .height(height)
                .render(value);
    }

    String selected(String value) {
        return Style.newStyle()
                .foreground(SELECTION_FOREGROUND)
                .background(SELECTION_BACKGROUND)
                .bold(true)
                .render(value);
    }

    String unselected(String value) {
        return Style.newStyle().foreground(MUTED).render(value);
    }

    String heading(String value) {
        return Style.newStyle().foreground(ACCENT).bold(true).render(value);
    }

    String strong(String value) {
        return Style.newStyle().bold(true).render(value);
    }

    String emphasis(String value) {
        return Style.newStyle().italic(true).render(value);
    }

    String inlineCode(String value) {
        return Style.newStyle()
                .foreground(QUEUED_FOREGROUND)
                .background(QUEUED_BACKGROUND)
                .inline(true)
                .render(value);
    }

    String codeBlock(String value) {
        return Style.newStyle()
                .foreground(USER_FOREGROUND)
                .background(USER_BACKGROUND)
                .render(value);
    }

    String quote(String value) {
        return Style.newStyle().foreground(MUTED).italic(true).render(value);
    }

    String link(String value) {
        return Style.newStyle().foreground(ACCENT).underline(true).render(value);
    }

    String user(String value) {
        return block(value, USER_FOREGROUND, USER_BACKGROUND);
    }

    String success(String value) {
        return block(value, SUCCESS_FOREGROUND, SUCCESS_BACKGROUND);
    }

    String pending(String value) {
        return block(value, PENDING_FOREGROUND, PENDING_BACKGROUND);
    }

    String error(String value) {
        return block(value, ERROR_FOREGROUND, ERROR_BACKGROUND);
    }

    String queued(String value) {
        return block(value, QUEUED_FOREGROUND, QUEUED_BACKGROUND);
    }

    String warning(String value) {
        return Style.newStyle().foreground(PENDING_FOREGROUND).bold(true).render(value);
    }

    String borderSubtle(String value) {
        return Style.newStyle().foreground(BORDER_SUBTLE).render(value);
    }

    String userCardLine(String paddedContent, boolean narrow, boolean isHeader) {
        String border = narrow ? " " : "│ ";
        String styledBorder = Style.newStyle().foreground(ACCENT).bold(true).render(border);
        Style contentStyle =
                Style.newStyle().foreground(isHeader ? ACCENT : USER_FOREGROUND).background(USER_BACKGROUND);
        if (isHeader) {
            contentStyle = contentStyle.bold(true);
        }
        String styledContent = contentStyle.render(paddedContent);
        return styledBorder + styledContent + " ";
    }

    String errorCardLine(String paddedContent, boolean narrow, boolean isHeader) {
        String border = narrow ? " " : "│ ";
        String styledBorder =
                Style.newStyle().foreground(ERROR_ACCENT).bold(true).render(border);
        Style contentStyle = Style.newStyle()
                .foreground(isHeader ? ERROR_ACCENT : ERROR_FOREGROUND)
                .background(ERROR_BACKGROUND);
        if (isHeader) {
            contentStyle = contentStyle.bold(true);
        }
        String styledContent = contentStyle.render(paddedContent);
        return styledBorder + styledContent + " ";
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
