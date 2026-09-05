package io.haifa.agent.context.compression;

import java.util.List;
import java.util.Objects;

/**
 * Deterministically renders a validated {@link SemanticConversationSummaryV1}
 * into stable Markdown for context injection.
 */
public final class SemanticSummaryRenderer {

    private static final String PREFACE =
            "> Historical derived conversation summary; authoritative domain state and recent raw turns take precedence.";

    private SemanticSummaryRenderer() {}

    public static String renderMarkdown(SemanticConversationSummaryV1 summary) {
        Objects.requireNonNull(summary, "summary must not be null");
        StringBuilder sb = new StringBuilder();
        sb.append(PREFACE).append("\n\n");

        if (!summary.goals().isEmpty()) {
            sb.append("## Goals\n");
            renderItems(sb, summary.goals());
            sb.append("\n");
        }

        if (!summary.constraints().isEmpty()) {
            sb.append("## Constraints and Preferences\n");
            renderItems(sb, summary.constraints());
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
                renderItems(sb, progress.completed());
            }
            if (!progress.active().isEmpty()) {
                sb.append("### Active\n");
                renderItems(sb, progress.active());
            }
            if (!progress.blocked().isEmpty()) {
                sb.append("### Blocked\n");
                renderItems(sb, progress.blocked());
            }
            sb.append("\n");
        }

        if (!summary.decisions().isEmpty()) {
            sb.append("## Decisions\n");
            for (SemanticDecisionItem decision : summary.decisions()) {
                sb.append("- [")
                        .append(decision.status().name())
                        .append("] ")
                        .append(decision.statement());
                if (!decision.rationale().isBlank()) {
                    sb.append(" — ").append(decision.rationale());
                }
                if (!decision.sourceRefs().isEmpty()) {
                    sb.append(" [").append(String.join(", ", decision.sourceRefs())).append("]");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        if (!summary.criticalContext().isEmpty()) {
            sb.append("## Critical Context\n");
            renderItems(sb, summary.criticalContext());
            sb.append("\n");
        }

        if (!summary.nextSteps().isEmpty()) {
            sb.append("## Next Steps\n");
            renderItems(sb, summary.nextSteps());
            sb.append("\n");
        }

        if (!summary.unresolvedQuestions().isEmpty()) {
            sb.append("## Unresolved Questions\n");
            renderItems(sb, summary.unresolvedQuestions());
            sb.append("\n");
        }

        return sb.toString().trim();
    }

    private static void renderItems(StringBuilder sb, List<SemanticSummaryItem> items) {
        for (SemanticSummaryItem item : items) {
            sb.append("- ").append(item.text());
            if (!item.sourceRefs().isEmpty()) {
                sb.append(" [").append(String.join(", ", item.sourceRefs())).append("]");
            }
            sb.append("\n");
        }
    }
}
