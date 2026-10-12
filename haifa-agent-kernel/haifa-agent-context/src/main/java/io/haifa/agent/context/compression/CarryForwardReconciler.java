package io.haifa.agent.context.compression;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Reconciles candidate semantic conversation summaries produced by LLM summarization
 * against mandatory carry-forward items from preceding summaries.
 *
 * <p>Invariant enforcement (text verbatim continuity, non-loss of sourceRefs, category
 * continuity, restoration of unresolved carry items) is guaranteed by construction on
 * the server side, preventing brittle string matching failures from failing agent runs.
 */
public final class CarryForwardReconciler {

    private enum ItemCategory {
        GOALS,
        CONSTRAINTS,
        COMPLETED,
        ACTIVE,
        BLOCKED,
        NEXT_STEPS,
        CRITICAL_CONTEXT,
        QUESTIONS
    }

    private CarryForwardReconciler() {}

    /**
     * Reconciles the given candidate summary with mandatory carry-forward items.
     *
     * @param candidate       the candidate summary produced by LLM compaction
     * @param carryForward    the mandatory carry-forward items from previous state
     * @param projectedSource the projected compaction source providing evidence references
     * @return a reconciled {@link SemanticConversationSummaryV1} satisfying carry-forward invariants
     */
    public static SemanticConversationSummaryV1 reconcile(
            SemanticConversationSummaryV1 candidate,
            List<SemanticSummaryItem> carryForward,
            ProjectedCompactionSource projectedSource) {
        Objects.requireNonNull(candidate, "candidate must not be null");
        if (carryForward == null || carryForward.isEmpty()) {
            return candidate;
        }

        Set<String> currentEvidenceRefs = new HashSet<>();
        if (projectedSource != null) {
            if (projectedSource.messageAliases() != null) {
                currentEvidenceRefs.addAll(projectedSource.messageAliases().keySet());
            }
            if (projectedSource.toolAliases() != null) {
                currentEvidenceRefs.addAll(projectedSource.toolAliases().keySet());
            }
        }

        List<SemanticSummaryItem> goals = new ArrayList<>(candidate.goals());
        List<SemanticSummaryItem> constraints = new ArrayList<>(candidate.constraints());
        List<SemanticSummaryItem> completed =
                new ArrayList<>(candidate.progress().completed());
        List<SemanticSummaryItem> active = new ArrayList<>(candidate.progress().active());
        List<SemanticSummaryItem> blocked = new ArrayList<>(candidate.progress().blocked());
        List<SemanticDecisionItem> decisions = new ArrayList<>(candidate.decisions());
        List<SemanticSummaryItem> nextSteps = new ArrayList<>(candidate.nextSteps());
        List<SemanticSummaryItem> criticalContext = new ArrayList<>(candidate.criticalContext());
        List<SemanticSummaryItem> questions = new ArrayList<>(candidate.unresolvedQuestions());

        for (SemanticSummaryItem carry : carryForward) {
            String carryId = carry.stableItemId();
            if (carryId == null || carryId.isBlank()) {
                continue;
            }

            List<SemanticSummaryItem> matchedItems = new ArrayList<>();
            ItemCategory foundCategory = null;

            // Search and remove across all lists, tracking original category and gathering all duplicates
            if (collectAndRemoveMatching(constraints, carryId, matchedItems) && foundCategory == null) {
                foundCategory = ItemCategory.CONSTRAINTS;
            }
            if (collectAndRemoveMatching(active, carryId, matchedItems) && foundCategory == null) {
                foundCategory = ItemCategory.ACTIVE;
            }
            if (collectAndRemoveMatching(blocked, carryId, matchedItems) && foundCategory == null) {
                foundCategory = ItemCategory.BLOCKED;
            }
            if (collectAndRemoveMatching(completed, carryId, matchedItems) && foundCategory == null) {
                foundCategory = ItemCategory.COMPLETED;
            }
            if (collectAndRemoveMatching(questions, carryId, matchedItems) && foundCategory == null) {
                foundCategory = ItemCategory.QUESTIONS;
            }
            if (collectAndRemoveMatching(goals, carryId, matchedItems) && foundCategory == null) {
                foundCategory = ItemCategory.GOALS;
            }
            if (collectAndRemoveMatching(criticalContext, carryId, matchedItems) && foundCategory == null) {
                foundCategory = ItemCategory.CRITICAL_CONTEXT;
            }
            if (collectAndRemoveMatching(nextSteps, carryId, matchedItems) && foundCategory == null) {
                foundCategory = ItemCategory.NEXT_STEPS;
            }

            if (!matchedItems.isEmpty()) {
                // Item was retained by candidate: reconcile text, sourceRefs, category, confidence
                String canonicalId = carry.stableItemId();
                String text = carry.text();

                LinkedHashSet<String> refs = new LinkedHashSet<>(carry.sourceRefs());
                for (SemanticSummaryItem item : matchedItems) {
                    refs.addAll(item.sourceRefs());
                }
                List<String> mergedRefs = List.copyOf(refs);

                ItemCategory targetCategory;
                if (canonicalId.startsWith("C-")) {
                    targetCategory = ItemCategory.CONSTRAINTS;
                } else if (canonicalId.startsWith("Q-")) {
                    targetCategory = ItemCategory.QUESTIONS;
                } else if (canonicalId.startsWith("PA-") || canonicalId.startsWith("PB-")) {
                    if (foundCategory == ItemCategory.COMPLETED) {
                        targetCategory = ItemCategory.COMPLETED;
                    } else if (foundCategory == ItemCategory.ACTIVE) {
                        targetCategory = ItemCategory.ACTIVE;
                    } else if (foundCategory == ItemCategory.BLOCKED) {
                        targetCategory = ItemCategory.BLOCKED;
                    } else {
                        targetCategory = canonicalId.startsWith("PA-") ? ItemCategory.ACTIVE : ItemCategory.BLOCKED;
                    }
                } else {
                    targetCategory = foundCategory;
                }

                SemanticConfidence confidence;
                if (targetCategory == ItemCategory.COMPLETED) {
                    confidence = SemanticConfidence.OBSERVED;
                } else {
                    confidence = matchedItems.stream()
                            .map(SemanticSummaryItem::confidence)
                            .filter(Objects::nonNull)
                            .findFirst()
                            .orElse(carry.confidence());
                }

                SemanticSummaryItem reconciledItem = new SemanticSummaryItem(canonicalId, text, mergedRefs, confidence);

                switch (targetCategory) {
                    case CONSTRAINTS -> constraints.add(reconciledItem);
                    case QUESTIONS -> questions.add(reconciledItem);
                    case ACTIVE -> active.add(reconciledItem);
                    case BLOCKED -> blocked.add(reconciledItem);
                    case COMPLETED -> completed.add(reconciledItem);
                    case GOALS -> goals.add(reconciledItem);
                    case CRITICAL_CONTEXT -> criticalContext.add(reconciledItem);
                    case NEXT_STEPS -> nextSteps.add(reconciledItem);
                }
            } else {
                // Item was missing in candidate: check if resolved by terminal decision
                boolean resolvedInDecisions = decisions.stream()
                        .filter(d -> d.status() != SemanticDecisionStatus.PROPOSED)
                        .filter(d -> (d.statement() != null && d.statement().contains(carryId))
                                || (d.rationale() != null && d.rationale().contains(carryId)))
                        .anyMatch(d -> d.sourceRefs() != null
                                && d.sourceRefs().stream().anyMatch(currentEvidenceRefs::contains));

                if (!resolvedInDecisions) {
                    // Not resolved: restore item into its original category
                    if (carryId.startsWith("C-")) {
                        constraints.add(carry);
                    } else if (carryId.startsWith("Q-")) {
                        questions.add(carry);
                    } else if (carryId.startsWith("PA-")) {
                        active.add(carry);
                    } else if (carryId.startsWith("PB-")) {
                        blocked.add(carry);
                    } else {
                        constraints.add(carry);
                    }
                }
            }
        }

        return new SemanticConversationSummaryV1(
                candidate.schemaVersion(),
                candidate.language(),
                goals,
                constraints,
                new SemanticProgress(completed, active, blocked),
                decisions,
                nextSteps,
                criticalContext,
                questions);
    }

    private static boolean collectAndRemoveMatching(
            List<SemanticSummaryItem> list, String id, List<SemanticSummaryItem> target) {
        boolean found = false;
        Iterator<SemanticSummaryItem> it = list.iterator();
        while (it.hasNext()) {
            SemanticSummaryItem item = it.next();
            if (item.stableItemId() != null && item.stableItemId().equalsIgnoreCase(id)) {
                target.add(item);
                it.remove();
                found = true;
            }
        }
        return found;
    }
}
