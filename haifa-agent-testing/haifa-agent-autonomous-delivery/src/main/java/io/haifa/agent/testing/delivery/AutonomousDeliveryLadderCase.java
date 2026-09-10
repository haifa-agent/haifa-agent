package io.haifa.agent.testing.delivery;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable definition of one capability-ladder case. The task statement is the seed for the
 * future {@code prompt.txt}; a blank statement marks a placeholder case awaiting authoring.
 */
public record AutonomousDeliveryLadderCase(
        String caseId,
        LadderLevel level,
        String title,
        String taskStatement,
        int localizationComplexity,
        int modificationSpan,
        int acceptanceComplexity,
        List<LadderVariant> variants) {
    private static final Pattern CASE_ID = Pattern.compile("L[1-6]-0[1-9]");
    private static final Pattern TITLE_TOKEN = Pattern.compile("[\\p{L}\\p{N} ()/,:'+-]+");

    public AutonomousDeliveryLadderCase {
        requireText(caseId, "caseId");
        if (!CASE_ID.matcher(caseId).matches()) {
            throw new IllegalArgumentException("caseId must look like L1-01: " + caseId);
        }
        Objects.requireNonNull(level, "level must not be null");
        if (!caseId.startsWith(level.prefix())) {
            throw new IllegalArgumentException("caseId prefix must match level: " + caseId);
        }
        title = requireText(title, "title");
        if (!TITLE_TOKEN.matcher(title).matches()) {
            throw new IllegalArgumentException("title must stay provider-neutral plain text: " + title);
        }
        taskStatement = taskStatement == null ? "" : taskStatement.strip();
        validateComplexity(localizationComplexity, "localizationComplexity");
        validateComplexity(modificationSpan, "modificationSpan");
        validateComplexity(acceptanceComplexity, "acceptanceComplexity");
        variants = List.copyOf(Objects.requireNonNull(variants, "variants must not be null"));
        if (variants.stream().distinct().count() != variants.size()) {
            throw new IllegalArgumentException("variants must not contain duplicates: " + caseId);
        }
    }

    public boolean isPlaceholder() {
        return taskStatement.isEmpty();
    }

    private static void validateComplexity(int value, String field) {
        if (value < 1 || value > 5) {
            throw new IllegalArgumentException(field + " must be within 1..5: " + value);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }
}
