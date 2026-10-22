package io.haifa.agent.runtime.core.tool;

import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.credential.api.SecretRedactor;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Extracts the optional LLM-authored {@code description} argument as a bounded, single-line,
 * display-only intent summary. It is never authorization, risk, or success evidence.
 */
public final class ToolIntentSummary {

    public static final int MAX_INTENT_LENGTH = 256;

    private static final Pattern AUTHORIZATION =
            Pattern.compile("(?i)(authorization\\s*[:=]\\s*(?:bearer\\s+)?)(\\S+)");
    private static final Pattern KEY_VALUE =
            Pattern.compile("(?i)((?:api[_-]?key|access[_-]?token|secret|password|passwd|token)\\s*[:=]\\s*)(\\S+)");
    private static final Pattern URI_USER_INFO = Pattern.compile("([a-zA-Z][a-zA-Z0-9+.-]*://)([^/@\\s]+)@");

    private ToolIntentSummary() {}

    /**
     * Returns a sanitized intent summary when the tool call carries a non-blank {@code description}
     * argument; otherwise an empty string. Pattern redaction covers labeled forms; pass the runtime
     * credential broker's redactor so known exact secret values are removed even when they appear as
     * ordinary prose.
     */
    public static String from(ToolCall call, SecretRedactor redactor) {
        Objects.requireNonNull(redactor, "redactor");
        if (call == null || call.arguments() == null || call.arguments().values() == null) {
            return "";
        }
        Map<String, Object> values = call.arguments().values();
        Object raw = values.get("description");
        if (!(raw instanceof String text)) {
            return "";
        }
        return sanitize(text, redactor);
    }

    /** Sanitizes without exact-value redaction; prefer {@link #from(ToolCall, SecretRedactor)}. */
    static String sanitize(String raw) {
        return sanitize(raw, SecretRedactor.noop());
    }

    static String sanitize(String raw, SecretRedactor redactor) {
        Objects.requireNonNull(redactor, "redactor");
        if (raw == null) {
            return "";
        }
        StringBuilder stripped = new StringBuilder(raw.length());
        raw.codePoints().forEach(codePoint -> {
            if (Character.isWhitespace(codePoint)) {
                stripped.append(' ');
            } else if (!Character.isISOControl(codePoint)) {
                stripped.appendCodePoint(codePoint);
            }
        });
        String singleLine = stripped.toString().replaceAll("\\s+", " ").strip();
        if (singleLine.isEmpty()) {
            return "";
        }
        String redacted = AUTHORIZATION.matcher(singleLine).replaceAll("$1[REDACTED]");
        redacted = KEY_VALUE.matcher(redacted).replaceAll("$1[REDACTED]");
        redacted = URI_USER_INFO.matcher(redacted).replaceAll("$1[REDACTED]@");
        redacted = redactor.redact(redacted);
        if (redacted == null || redacted.isBlank()) {
            return "";
        }
        redacted = redacted.replaceAll("\\s+", " ").strip();
        if (redacted.isEmpty()) {
            return "";
        }
        if (redacted.length() <= MAX_INTENT_LENGTH) {
            return redacted;
        }
        int end = MAX_INTENT_LENGTH - 1;
        if (end > 0
                && end < redacted.length()
                && Character.isLowSurrogate(redacted.charAt(end))
                && Character.isHighSurrogate(redacted.charAt(end - 1))) {
            end--;
        }
        return redacted.substring(0, end) + "…";
    }
}
