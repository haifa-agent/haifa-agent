package io.haifa.agent.context.compression;

import io.haifa.agent.core.message.AgentMessageId;
import io.haifa.agent.core.message.MessageCursor;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.tool.ToolCallId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Versioned, derived session context. Source messages remain authoritative. */
public record ConversationSummary(
        SummaryId id,
        SummaryVersion version,
        AgentSessionId sessionId,
        MessageCursor coveredFrom,
        MessageCursor coveredThrough,
        List<AgentMessageId> sourceMessageIds,
        String sourceHash,
        List<String> facts,
        List<String> decisions,
        List<String> openItems,
        List<ToolCallId> toolOutcomeReferences,
        int estimatedTokens,
        Instant createdAt,
        String policyVersion,
        String compressorVersion,
        Set<String> securityLabels,
        boolean valid) {
    public ConversationSummary {
        id = Objects.requireNonNull(id, "id must not be null");
        version = Objects.requireNonNull(version, "version must not be null");
        sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        coveredFrom = Objects.requireNonNull(coveredFrom, "coveredFrom must not be null");
        coveredThrough = Objects.requireNonNull(coveredThrough, "coveredThrough must not be null");
        if (coveredFrom.value() < 1 || coveredFrom.compareTo(coveredThrough) > 0) {
            throw new IllegalArgumentException("summary cursor range is invalid");
        }
        sourceMessageIds = List.copyOf(Objects.requireNonNull(sourceMessageIds, "sourceMessageIds must not be null"));
        if (sourceMessageIds.isEmpty()) throw new IllegalArgumentException("summary sources must not be empty");
        sourceHash = requireText(sourceHash, "sourceHash");
        facts = immutable(facts, "facts");
        decisions = immutable(decisions, "decisions");
        openItems = immutable(openItems, "openItems");
        toolOutcomeReferences =
                List.copyOf(Objects.requireNonNull(toolOutcomeReferences, "toolOutcomeReferences must not be null"));
        if (facts.isEmpty() && decisions.isEmpty() && openItems.isEmpty() && toolOutcomeReferences.isEmpty()) {
            throw new IllegalArgumentException("summary must contain structured content");
        }
        if (estimatedTokens < 1) throw new IllegalArgumentException("estimatedTokens must be positive");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        policyVersion = requireText(policyVersion, "policyVersion");
        compressorVersion = requireText(compressorVersion, "compressorVersion");
        securityLabels = Set.copyOf(Objects.requireNonNull(securityLabels, "securityLabels must not be null"));
    }

    public ConversationSummary invalidate() {
        return new ConversationSummary(
                id,
                version,
                sessionId,
                coveredFrom,
                coveredThrough,
                sourceMessageIds,
                sourceHash,
                facts,
                decisions,
                openItems,
                toolOutcomeReferences,
                estimatedTokens,
                createdAt,
                policyVersion,
                compressorVersion,
                securityLabels,
                false);
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
