package io.haifa.agent.store.sqlite.payload;

import io.haifa.agent.context.compression.ConversationSummary;
import java.util.List;
import java.util.Set;

public record ConversationSummaryPayload(
        List<String> sourceMessageIds,
        List<String> facts,
        List<String> decisions,
        List<String> openItems,
        List<String> toolOutcomeReferences,
        Set<String> securityLabels) {
    public static ConversationSummaryPayload from(ConversationSummary value) {
        return new ConversationSummaryPayload(
                value.sourceMessageIds().stream().map(id -> id.value()).toList(),
                value.facts(),
                value.decisions(),
                value.openItems(),
                value.toolOutcomeReferences().stream().map(id -> id.value()).toList(),
                value.securityLabels());
    }
}
