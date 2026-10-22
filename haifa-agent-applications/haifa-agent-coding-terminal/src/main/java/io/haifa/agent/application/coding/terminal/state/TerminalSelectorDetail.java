package io.haifa.agent.application.coding.terminal.state;

import java.util.Objects;

/** Optional presentation detail for one terminal selector option. */
public record TerminalSelectorDetail(String description, String badge, String category) {
    public TerminalSelectorDetail {
        description = text(description, "description");
        badge = text(badge, "badge");
        category = text(category, "category");
    }

    public static TerminalSelectorDetail plain(String description) {
        return new TerminalSelectorDetail(description, "", "");
    }

    public static TerminalSelectorDetail of(String description, String badge) {
        return new TerminalSelectorDetail(description, badge, "");
    }

    private static String text(String value, String field) {
        return value == null ? "" : Objects.requireNonNull(value, field).strip();
    }
}
