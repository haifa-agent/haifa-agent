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
        validate(summary, projectedSource, mandatoryCarryForward, Set.of(), java.util.Optional.empty());
    }

    public static void validate(
            SemanticConversationSummaryV1 summary,
            ProjectedCompactionSource projectedSource,
            List<SemanticSummaryItem> mandatoryCarryForward,
            Set<String> historicalDurableRefs,
            java.util.Optional<SemanticConversationSummaryV1> previousSummary) {
        Objects.requireNonNull(summary, "summary must not be null");
        Objects.requireNonNull(projectedSource, "projectedSource must not be null");
        Objects.requireNonNull(mandatoryCarryForward, "mandatoryCarryForward must not be null");
        Objects.requireNonNull(historicalDurableRefs, "historicalDurableRefs must not be null");
        Objects.requireNonNull(previousSummary, "previousSummary must not be null");

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
        validAliases.addAll(historicalDurableRefs);
        previousSummary.ifPresent(prev -> collectAllSourceRefs(prev, validAliases));

        // 1. Validate all sourceRefs exist in projected input aliases or historical durable refs
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

        // 3. Mandatory carry-forward verification with category and identity continuity
        validateCarryForwardContinuity(summary, mandatoryCarryForward, previousSummary, projectedSource);
    }

    private static void collectAllSourceRefs(SemanticConversationSummaryV1 summary, Set<String> target) {
        summary.goals().forEach(i -> target.addAll(i.sourceRefs()));
        summary.constraints().forEach(i -> target.addAll(i.sourceRefs()));
        summary.progress().completed().forEach(i -> target.addAll(i.sourceRefs()));
        summary.progress().active().forEach(i -> target.addAll(i.sourceRefs()));
        summary.progress().blocked().forEach(i -> target.addAll(i.sourceRefs()));
        summary.decisions().forEach(d -> target.addAll(d.sourceRefs()));
        summary.nextSteps().forEach(i -> target.addAll(i.sourceRefs()));
        summary.criticalContext().forEach(i -> target.addAll(i.sourceRefs()));
        summary.unresolvedQuestions().forEach(i -> target.addAll(i.sourceRefs()));
    }

    private static void validateCarryForwardContinuity(
            SemanticConversationSummaryV1 summary,
            List<SemanticSummaryItem> mandatoryCarryForward,
            java.util.Optional<SemanticConversationSummaryV1> previousSummary,
            ProjectedCompactionSource projectedSource) {
        Set<String> goalIds = collectIds(summary.goals());
        Set<String> constraintIds = collectIds(summary.constraints());
        Set<String> completedIds = collectIds(summary.progress().completed());
        Set<String> activeIds = collectIds(summary.progress().active());
        Set<String> blockedIds = collectIds(summary.progress().blocked());
        Set<String> nextStepIds = collectIds(summary.nextSteps());
        Set<String> criticalIds = collectIds(summary.criticalContext());
        Set<String> questionIds = collectIds(summary.unresolvedQuestions());

        Set<String> allIds = new HashSet<>();
        allIds.addAll(goalIds);
        allIds.addAll(constraintIds);
        allIds.addAll(completedIds);
        allIds.addAll(activeIds);
        allIds.addAll(blockedIds);
        allIds.addAll(nextStepIds);
        allIds.addAll(criticalIds);
        allIds.addAll(questionIds);
        summary.decisions().forEach(d -> allIds.add(d.stableItemId()));

        // Check each mandatory carry-forward item
        for (SemanticSummaryItem carry : mandatoryCarryForward) {
            String carryId = carry.stableItemId();
            if (carryId == null || carryId.isBlank()) {
                continue;
            }

            if (!allIds.contains(carryId)) {
                // Check if resolved by a decision citing it
                boolean resolvedInDecisions = summary.decisions().stream()
                        .anyMatch(d -> d.sourceRefs().contains(carryId)
                                || d.statement().contains(carryId)
                                || d.rationale().contains(carryId));
                if (!resolvedInDecisions) {
                    throw new SemanticSummaryValidationException(
                            "mandatory carry-forward item '" + carryId + "' was dropped without resolution");
                }
            } else {
                // Category continuity: ensure item did not jump to an incompatible category
                if (carryId.startsWith("C-") && !constraintIds.contains(carryId)) {
                    throw new SemanticSummaryValidationException(
                            "constraint item '" + carryId + "' cannot transition to another category");
                }
                if (carryId.startsWith("Q-") && !questionIds.contains(carryId)) {
                    throw new SemanticSummaryValidationException(
                            "unresolved question '" + carryId + "' cannot transition to another category");
                }
                if (carryId.startsWith("PA-")
                        && !activeIds.contains(carryId)
                        && !completedIds.contains(carryId)
                        && !blockedIds.contains(carryId)) {
                    throw new SemanticSummaryValidationException(
                            "active progress item '" + carryId + "' cannot transition to another category");
                }
                if (carryId.startsWith("PB-")
                        && !blockedIds.contains(carryId)
                        && !activeIds.contains(carryId)
                        && !completedIds.contains(carryId)) {
                    throw new SemanticSummaryValidationException(
                            "blocked progress item '" + carryId + "' cannot transition to another category");
                }
            }
        }
    }

    private static Set<String> collectIds(List<SemanticSummaryItem> items) {
        Set<String> ids = new HashSet<>();
        for (SemanticSummaryItem item : items) {
            if (item.stableItemId() != null) {
                ids.add(item.stableItemId());
            }
        }
        return ids;
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
}
