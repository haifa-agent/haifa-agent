package io.haifa.agent.cli;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Recognizes the exact, whole-value placeholders that older Runtime versions generated when they rewrote historical
 * Tool Call arguments before sending them to the model.
 *
 * <p>The match is deliberately narrow. Only a value that is exactly one known single-line placeholder is rejected, so
 * ordinary documents, code and patches that merely quote a marker remain writable. This is a bounded fallback that
 * cannot prove provenance and does not cover arbitrary model hallucination.
 */
final class HistoricalTruncationPlaceholder {

    private static final List<Pattern> KNOWN_PLACEHOLDERS = List.of(
            Pattern.compile("^\\[Code content truncated \\(\\d+ chars\\); file written to .+\\]$"),
            Pattern.compile("^\\[Code content truncated \\(\\d+ chars\\); target: .+\\]$"),
            Pattern.compile("^\\[Content truncated \\(\\d+ chars\\)\\]$"),
            Pattern.compile(
                    "^\\[Historical large pure-read payload pruned; rerun to inspect current state\\.\\]$"),
            Pattern.compile("^\\[Historical large pure-read payload pruned; rerun the tool to inspect current state, "
                    + "which may differ from the historical bytes\\.\\]$"));

    private HistoricalTruncationPlaceholder() {}

    static boolean isEntireValue(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.strip();
        if (trimmed.isEmpty() || trimmed.indexOf('\n') >= 0 || trimmed.indexOf('\r') >= 0) {
            return false;
        }
        for (Pattern placeholder : KNOWN_PLACEHOLDERS) {
            if (placeholder.matcher(trimmed).matches()) {
                return true;
            }
        }
        return false;
    }
}
