package io.haifa.agent.context.compression;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates a {@link SemanticConversationSummaryV1} against structural, provenance,
 * carry-forward, and security quality gates.
 */
public final class SemanticSummaryValidator {

    private static final Pattern FORBIDDEN_CONTENT = Pattern.compile(
            "(?i)(<think>|</think>|<thinking>|</thinking>|PROTECTED_CONTINUATION|sk-[A-Za-z0-9_-]{16,}|Bearer\\s+[A-Za-z0-9._~+/-]{16,})");

    private SemanticSummaryValidator() {}

    public static void validate(
            SemanticConversationSummaryV1 summary,
            ProjectedCompactionSource projectedSource,
            List<SemanticSummaryItem> mandatoryCarryForward) {
        Objects.requireNonNull(summary, "summary must not be null");
        Objects.requireNonNull(projectedSource, "projectedSource must not be null");
        Objects.requireNonNull(mandatoryCarryForward, "mandatoryCarryForward must not be null");

        if (!SemanticConversationSummaryV1.CURRENT_SCHEMA_VERSION.equals(summary.schemaVersion())) {
            throw new SemanticSummaryValidationException("unsupported schemaVersion: " + summary.schemaVersion()
                    + ", expected " + SemanticConversationSummaryV1.CURRENT_SCHEMA_VERSION);
        }

        if (summary.language().isBlank()) {
            throw new SemanticSummaryValidationException("language must not be blank");
        }

        if (!summary.hasContent()) {
            throw new SemanticSummaryValidationException("summary must not be completely empty");
        }

        Set<String> validAliases =
                new HashSet<>(projectedSource.messageAliases().keySet());
        validAliases.addAll(projectedSource.toolAliases().keySet());

        // 1. Validate all sourceRefs exist in projected input aliases
        validateSourceRefs(summary.goals(), validAliases, "goals");
        validateSourceRefs(summary.constraints(), validAliases, "constraints");
        validateSourceRefs(summary.progress().completed(), validAliases, "progress.completed");
        validateSourceRefs(summary.progress().active(), validAliases, "progress.active");
        validateSourceRefs(summary.progress().blocked(), validAliases, "progress.blocked");
        validateSourceRefs(summary.nextSteps(), validAliases, "nextSteps");
        validateSourceRefs(summary.criticalContext(), validAliases, "criticalContext");
        validateSourceRefs(summary.unresolvedQuestions(), validAliases, "unresolvedQuestions");

        for (SemanticDecisionItem decision : summary.decisions()) {
            for (String ref : decision.sourceRefs()) {
                if (!validAliases.contains(ref)) {
                    throw new SemanticSummaryValidationException("unknown sourceRef '" + ref + "' in decisions");
                }
            }
            scanText(decision.statement(), "decision.statement");
            scanText(decision.rationale(), "decision.rationale");
        }

        // 2. Completed items must have OBSERVED confidence and at least one valid sourceRef
        for (SemanticSummaryItem completed : summary.progress().completed()) {
            if (completed.confidence() == SemanticConfidence.INFERRED) {
                throw new SemanticSummaryValidationException(
                        "completed item '" + completed.stableItemId() + "' cannot have INFERRED confidence");
            }
            if (completed.sourceRefs().isEmpty()) {
                throw new SemanticSummaryValidationException(
                        "completed item '" + completed.stableItemId() + "' must cite supporting sourceRefs");
            }
        }

        // 3. Mandatory carry-forward verification
        Set<String> presentIds = collectItemIds(summary);
        for (SemanticSummaryItem carry : mandatoryCarryForward) {
            if (!presentIds.contains(carry.stableItemId())) {
                throw new SemanticSummaryValidationException(
                        "mandatory carry-forward item '" + carry.stableItemId() + "' was dropped without resolution");
            }
        }
    }

    private static void validateSourceRefs(List<SemanticSummaryItem> items, Set<String> validAliases, String section) {
        for (SemanticSummaryItem item : items) {
            for (String ref : item.sourceRefs()) {
                if (!validAliases.contains(ref)) {
                    throw new SemanticSummaryValidationException(
                            "unknown sourceRef '" + ref + "' in section '" + section + "'");
                }
            }
            scanText(item.text(), section);
        }
    }

    private static void scanText(String text, String field) {
        if (text != null && FORBIDDEN_CONTENT.matcher(text).find()) {
            throw new SemanticSummaryValidationException("forbidden or sensitive pattern detected in " + field);
        }
    }

    private static Set<String> collectItemIds(SemanticConversationSummaryV1 summary) {
        Set<String> ids = new HashSet<>();
        summary.goals().forEach(item -> ids.add(item.stableItemId()));
        summary.constraints().forEach(item -> ids.add(item.stableItemId()));
        summary.progress().completed().forEach(item -> ids.add(item.stableItemId()));
        summary.progress().active().forEach(item -> ids.add(item.stableItemId()));
        summary.progress().blocked().forEach(item -> ids.add(item.stableItemId()));
        summary.decisions().forEach(item -> ids.add(item.stableItemId()));
        summary.nextSteps().forEach(item -> ids.add(item.stableItemId()));
        summary.criticalContext().forEach(item -> ids.add(item.stableItemId()));
        summary.unresolvedQuestions().forEach(item -> ids.add(item.stableItemId()));
        return ids;
    }
}
