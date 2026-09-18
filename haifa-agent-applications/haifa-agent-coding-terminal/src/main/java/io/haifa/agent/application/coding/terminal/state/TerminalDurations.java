package io.haifa.agent.application.coding.terminal.state;

/** Shared human-readable duration formatting for transcript titles and metadata lines. */
public final class TerminalDurations {
    private TerminalDurations() {}

    public static String human(long millis) {
        if (millis < 0) throw new IllegalArgumentException("duration must not be negative");
        if (millis < 1_000) return millis + " ms";
        if (millis < 10_000) {
            String value = String.format(java.util.Locale.ROOT, "%.1f", millis / 1_000.0);
            return (value.endsWith(".0") ? value.substring(0, value.length() - 2) : value) + "s";
        }
        if (millis < 60_000) return (millis / 1_000) + "s";
        long minutes = millis / 60_000;
        long seconds = (millis % 60_000) / 1_000;
        return minutes + "m " + seconds + "s";
    }
}
