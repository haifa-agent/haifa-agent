package io.haifa.agent.context.compression;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministically renders a validated {@link SemanticConversationSummaryV1}
 * into stable Markdown for context injection.
 */
public final class SemanticSummaryRenderer {

    private static final String PREFACE =
            "> Historical derived conversation summary; authoritative domain state and recent raw turns take precedence.";

    private SemanticSummaryRenderer() {}

    public static String renderMarkdown(SemanticConversationSummaryV1 summary) {
        return renderMarkdown(summary, Set.of());
    }

    static String renderMarkdown(SemanticConversationSummaryV1 summary, Set<SemanticSummaryItem> labeledItems) {
        Objects.requireNonNull(summary, "summary must not be null");
        StringBuilder sb = new StringBuilder();
        sb.append(PREFACE).append("\n\n");

        if (!summary.goals().isEmpty()) {
            sb.append("## Goals\n");
            renderItems(sb, summary.goals(), labeledItems);
            sb.append("\n");
        }

        if (!summary.constraints().isEmpty()) {
            sb.append("## Constraints and Preferences\n");
            renderItems(sb, summary.constraints(), labeledItems);
            sb.append("\n");
        }

        SemanticProgress progress = summary.progress();
        boolean hasProgress = !progress.completed().isEmpty()
                || !progress.active().isEmpty()
                || !progress.blocked().isEmpty();
        if (hasProgress) {
            sb.append("## Progress\n");
            if (!progress.completed().isEmpty()) {
                sb.append("### Completed\n");
                renderItems(sb, progress.completed(), labeledItems);
            }
            if (!progress.active().isEmpty()) {
                sb.append("### Active\n");
                renderItems(sb, progress.active(), labeledItems);
            }
            if (!progress.blocked().isEmpty()) {
                sb.append("### Blocked\n");
                renderItems(sb, progress.blocked(), labeledItems);
            }
            sb.append("\n");
        }

        if (!summary.decisions().isEmpty()) {
            sb.append("## Decisions\n");
            for (SemanticDecisionItem decision : summary.decisions()) {
                sb.append("- [").append(decision.status().name()).append("] ").append(decision.statement());
                if (!decision.rationale().isBlank()) {
                    sb.append(" — ").append(decision.rationale());
                }
                if (!decision.sourceRefs().isEmpty()) {
                    sb.append(" [")
                            .append(String.join(", ", decision.sourceRefs()))
                            .append("]");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        if (!summary.criticalContext().isEmpty()) {
            sb.append("## Critical Context\n");
            renderItems(sb, summary.criticalContext(), labeledItems);
            sb.append("\n");
        }

        if (!summary.nextSteps().isEmpty()) {
            sb.append("## Next Steps\n");
            renderItems(sb, summary.nextSteps(), labeledItems);
            sb.append("\n");
        }

        if (!summary.unresolvedQuestions().isEmpty()) {
            sb.append("## Unresolved Questions\n");
            renderItems(sb, summary.unresolvedQuestions(), labeledItems);
            sb.append("\n");
        }

        return sb.toString().trim();
    }

    private static void renderItems(
            StringBuilder sb, List<SemanticSummaryItem> items, Set<SemanticSummaryItem> labeledItems) {
        for (SemanticSummaryItem item : items) {
            sb.append("- ");
            if (labeledItems.contains(item))
                sb.append("[").append(item.stableItemId()).append("] ");
            sb.append(item.text());
            if (!item.sourceRefs().isEmpty()) {
                sb.append(" [").append(String.join(", ", item.sourceRefs())).append("]");
            }
            sb.append("\n");
        }
    }
}
