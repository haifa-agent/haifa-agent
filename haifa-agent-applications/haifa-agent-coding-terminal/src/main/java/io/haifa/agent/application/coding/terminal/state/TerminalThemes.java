package io.haifa.agent.application.coding.terminal.state;

import java.util.List;
import java.util.Locale;

/** Supported built-in terminal theme identifiers. */
public final class TerminalThemes {
    public static final String DEFAULT_THEME = "dark";
    public static final List<String> AVAILABLE = List.of("dark", "light", "high-contrast", "nord");

    private TerminalThemes() {}

    public static boolean isValid(String name) {
        return name != null && AVAILABLE.contains(name.strip().toLowerCase(Locale.ROOT));
    }

    public static String canonicalName(String name) {
        if (name == null || name.isBlank()) return DEFAULT_THEME;
        String clean = name.strip().toLowerCase(Locale.ROOT);
        return AVAILABLE.contains(clean) ? clean : DEFAULT_THEME;
    }
}
