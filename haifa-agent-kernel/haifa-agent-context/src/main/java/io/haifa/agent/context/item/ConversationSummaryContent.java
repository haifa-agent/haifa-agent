package io.haifa.agent.context.item;

import java.util.List;
import java.util.Objects;

/** Structured derived conversation data; it is not a fact source or long-term memory. */
public record ConversationSummaryContent(
        String summaryId,
        long version,
        List<String> facts,
        List<String> decisions,
        List<String> openItems,
        List<String> toolOutcomeReferences)
        implements ContextContent {
    public ConversationSummaryContent {
        summaryId = requireText(summaryId, "summaryId");
        if (version < 1) throw new IllegalArgumentException("summary version must be positive");
        facts = immutable(facts, "facts");
        decisions = immutable(decisions, "decisions");
        openItems = immutable(openItems, "openItems");
        toolOutcomeReferences = immutable(toolOutcomeReferences, "toolOutcomeReferences");
        if (facts.isEmpty() && decisions.isEmpty() && openItems.isEmpty() && toolOutcomeReferences.isEmpty()) {
            throw new IllegalArgumentException("conversation summary must not be empty");
        }
    }

    private static List<String> immutable(List<String> values, String field) {
        return List.copyOf(Objects.requireNonNull(values, field + " must not be null")).stream()
                .map(value -> requireText(value, field))
                .toList();
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
