package io.haifa.agent.context.compression;

import io.haifa.agent.core.message.AgentMessageId;
import io.haifa.agent.core.tool.ToolCallId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Result of projecting authoritative messages into a safe, bounded text representation
 * with local opaque aliases for model summarization.
 */
public record ProjectedCompactionSource(
        String safeConversationText,
        Map<String, AgentMessageId> messageAliases,
        Map<String, ToolCallId> toolAliases,
        List<AgentMessageId> sourceMessageIds,
        List<ToolCallId> toolOutcomeReferences,
        Set<String> securityLabels) {

    public ProjectedCompactionSource {
        safeConversationText = Objects.requireNonNull(safeConversationText, "safeConversationText must not be null");
        messageAliases = Map.copyOf(Objects.requireNonNull(messageAliases, "messageAliases must not be null"));
        toolAliases = Map.copyOf(Objects.requireNonNull(toolAliases, "toolAliases must not be null"));
        sourceMessageIds = List.copyOf(Objects.requireNonNull(sourceMessageIds, "sourceMessageIds must not be null"));
        toolOutcomeReferences =
                List.copyOf(Objects.requireNonNull(toolOutcomeReferences, "toolOutcomeReferences must not be null"));
        securityLabels = Set.copyOf(Objects.requireNonNull(securityLabels, "securityLabels must not be null"));
    }
}
