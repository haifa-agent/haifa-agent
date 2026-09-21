package io.haifa.agent.store.sqlite.payload;

import io.haifa.agent.context.compression.ConversationSummary;
import io.haifa.agent.context.compression.SemanticConversationSummaryV1;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Bounded provenance payload: direct fold inputs stay in the payload while full coverage is represented by count. */
public record ConversationSummaryPayloadV3(
        List<String> directSourceMessageIds,
        long coveredSourceCount,
        List<String> facts,
        List<String> decisions,
        List<String> openItems,
        List<String> toolOutcomeReferences,
        Set<String> securityLabels,
        String quality,
        Optional<SemanticConversationSummaryV1> semanticSummary) {

    public static ConversationSummaryPayloadV3 from(ConversationSummary value) {
        return new ConversationSummaryPayloadV3(
                value.sourceMessageIds().stream().map(id -> id.value()).toList(),
                value.coveredSourceCount(),
                value.facts(),
                value.decisions(),
                value.openItems(),
                value.toolOutcomeReferences().stream().map(id -> id.value()).toList(),
                value.securityLabels(),
                value.quality().name(),
                value.semanticSummary());
    }
}
