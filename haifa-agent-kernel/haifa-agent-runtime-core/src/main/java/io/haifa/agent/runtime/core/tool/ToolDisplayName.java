package io.haifa.agent.runtime.core.tool;

import java.util.regex.Pattern;

/** Bounded, control-free display title for tool lifecycle facts. */
final class ToolDisplayName {
    static final int MAXIMUM_LENGTH = 128;

    private static final Pattern ANSI_CSI = Pattern.compile("\u001B\\[[0-?]*[ -/]*[@-~]");

    private ToolDisplayName() {}

    static String resolve(String title, String fallback) {
        String value = sanitize(title);
        if (value.isEmpty()) value = sanitize(fallback);
        if (value.isEmpty()) value = "Tool";
        return truncate(value, MAXIMUM_LENGTH);
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) return "";
        String withoutAnsi = ANSI_CSI.matcher(value).replaceAll("");
        StringBuilder builder = new StringBuilder(withoutAnsi.length());
        boolean pendingSpace = false;
        for (int index = 0; index < withoutAnsi.length(); ) {
            int codePoint = withoutAnsi.codePointAt(index);
            index += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) {
                pendingSpace = builder.length() > 0;
                continue;
            }
            if (Character.isISOControl(codePoint)) continue;
            if (pendingSpace) {
                builder.append(' ');
                pendingSpace = false;
            }
            builder.appendCodePoint(codePoint);
        }
        return builder.toString().strip();
    }

    private static String truncate(String value, int maximum) {
        if (value.length() <= maximum) return value;
        int end = maximum - 1;
        if (end > 0
                && end < value.length()
                && Character.isLowSurrogate(value.charAt(end))
                && Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end) + "…";
    }
}
