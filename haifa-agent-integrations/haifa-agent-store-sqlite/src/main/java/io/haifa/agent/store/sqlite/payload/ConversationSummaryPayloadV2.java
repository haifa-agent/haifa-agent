package io.haifa.agent.store.sqlite.payload;

import io.haifa.agent.context.compression.ConversationSummary;
import io.haifa.agent.context.compression.SemanticConversationSummaryV1;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Normalized SQLite storage payload V2 for {@link ConversationSummary}.
 * Stores the structured semantic summary without duplicated rendered markdown.
 */
public record ConversationSummaryPayloadV2(
        List<String> sourceMessageIds,
        List<String> facts,
        List<String> decisions,
        List<String> openItems,
        List<String> toolOutcomeReferences,
        Set<String> securityLabels,
        String quality,
        Optional<SemanticConversationSummaryV1> semanticSummary) {

    public static ConversationSummaryPayloadV2 from(ConversationSummary value) {
        return new ConversationSummaryPayloadV2(
                value.sourceMessageIds().stream().map(id -> id.value()).toList(),
                value.facts(),
                value.decisions(),
                value.openItems(),
                value.toolOutcomeReferences().stream().map(id -> id.value()).toList(),
                value.securityLabels(),
                value.quality().name(),
                value.semanticSummary());
    }
}
