package io.haifa.agent.runtime.api.display;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

/**
 * A bounded, display-only copy of text. The authoritative value stays in the Tool Result; this value only
 * carries a UTF-8-safe head/tail sample plus the original size facts, so a reader never mistakes the sample
 * for the whole result. It is capability-neutral and performs no redaction.
 */
public record BoundedText(
        String text, boolean truncated, long byteCount, long lineCount, Optional<TruncationReason> truncationReason) {

    private static final String TRUNCATION_MARKER = " ... [truncated] ... ";

    public BoundedText {
        text = Objects.requireNonNull(text, "text must not be null");
        if (byteCount < 0) {
            throw new IllegalArgumentException("byteCount must not be negative");
        }
        if (lineCount < 0) {
            throw new IllegalArgumentException("lineCount must not be negative");
        }
        truncationReason = Objects.requireNonNull(truncationReason, "truncationReason must not be null");
    }

    public static BoundedText of(String value, ToolDisplayBudget budget) {
        Objects.requireNonNull(budget, "budget must not be null");
        return of(value, budget.maxBytes(), budget.maxLines());
    }

    /**
     * Builds a bounded copy. When either budget is exceeded, the result keeps a UTF-8-safe head and tail sample
     * with an explicit truncation marker; {@code byteCount}/{@code lineCount} always describe the original input.
     */
    public static BoundedText of(String value, int maxBytes, int maxLines) {
        Objects.requireNonNull(value, "value must not be null");
        if (maxBytes < 1) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        if (maxLines < 1) {
            throw new IllegalArgumentException("maxLines must be positive");
        }
        long byteCount = utf8Length(value);
        long lineCount = lineCount(value);
        boolean overBytes = byteCount > maxBytes;
        boolean overLines = lineCount > maxLines;
        if (!overBytes && !overLines) {
            return new BoundedText(value, false, byteCount, lineCount, Optional.empty());
        }
        TruncationReason reason = overBytes ? TruncationReason.OUTPUT_BYTES : TruncationReason.OUTPUT_LINES;
        int markerBytes = (int) utf8Length(TRUNCATION_MARKER);
        if (maxBytes <= markerBytes) {
            return new BoundedText(prefixByBytes(value, maxBytes), true, byteCount, lineCount, Optional.of(reason));
        }
        int contentBytes = maxBytes - markerBytes;
        int headBytes = contentBytes / 2;
        int tailBytes = contentBytes - headBytes;
        String headSource = value;
        String tailSource = value;
        if (overLines) {
            String[] lines = value.split("\n", -1);
            int headLines = maxLines / 2;
            int tailLines = maxLines - headLines;
            headSource = firstLines(lines, headLines);
            tailSource = lastLines(lines, tailLines);
        }
        String bounded =
                prefixByBytes(headSource, headBytes) + TRUNCATION_MARKER + suffixByBytes(tailSource, tailBytes);
        return new BoundedText(bounded, true, byteCount, lineCount, Optional.of(reason));
    }

    public static BoundedText empty() {
        return new BoundedText("", false, 0, 0, Optional.empty());
    }

    private static long utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static long lineCount(String value) {
        if (value.isEmpty()) {
            return 0;
        }
        long breaks = value.chars().filter(character -> character == '\n').count();
        return breaks + 1;
    }

    private static String prefixByBytes(String value, int maxBytes) {
        if (maxBytes <= 0) {
            return "";
        }
        StringBuilder bounded = new StringBuilder();
        int bytes = 0;
        int index = 0;
        while (index < value.length()) {
            int codePoint = value.codePointAt(index);
            int size = codePointBytes(codePoint);
            if (bytes + size > maxBytes) {
                break;
            }
            bounded.appendCodePoint(codePoint);
            bytes += size;
            index += Character.charCount(codePoint);
        }
        return bounded.toString();
    }

    private static String suffixByBytes(String value, int maxBytes) {
        if (maxBytes <= 0) {
            return "";
        }
        int[] codePoints = value.codePoints().toArray();
        StringBuilder reversed = new StringBuilder();
        int bytes = 0;
        for (int index = codePoints.length - 1; index >= 0; index--) {
            int size = codePointBytes(codePoints[index]);
            if (bytes + size > maxBytes) {
                break;
            }
            reversed.appendCodePoint(codePoints[index]);
            bytes += size;
        }
        return reversed.reverse().toString();
    }

    private static int codePointBytes(int codePoint) {
        if (codePoint < 0x80) {
            return 1;
        }
        if (codePoint < 0x800) {
            return 2;
        }
        if (codePoint < 0x10000) {
            return 3;
        }
        return 4;
    }

    private static String firstLines(String[] lines, int count) {
        if (count <= 0 || lines.length == 0) {
            return "";
        }
        StringBuilder bounded = new StringBuilder();
        for (int index = 0; index < count && index < lines.length; index++) {
            if (index > 0) {
                bounded.append('\n');
            }
            bounded.append(lines[index]);
        }
        return bounded.toString();
    }

    private static String lastLines(String[] lines, int count) {
        if (count <= 0 || lines.length == 0) {
            return "";
        }
        int start = Math.max(0, lines.length - count);
        StringBuilder bounded = new StringBuilder();
        for (int index = start; index < lines.length; index++) {
            if (index > start) {
                bounded.append('\n');
            }
            bounded.append(lines[index]);
        }
        return bounded.toString();
    }

    /** Why a bounded display copy was shortened. Authoritative results are never modified. */
    public enum TruncationReason {
        OUTPUT_BYTES,
        OUTPUT_LINES
    }
}
