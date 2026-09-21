package io.haifa.agent.runtime.core.context;

import io.haifa.agent.context.compression.ConversationSummary;
import io.haifa.agent.core.message.AgentMessage;
import io.haifa.agent.core.message.AgentMessageId;
import io.haifa.agent.core.message.MessageCursor;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.runtime.core.model.continuation.ModelContinuationRecord;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Bounded, rebuildable projection of the active Session history. It is never authoritative. */
public record ActiveContextSnapshot(
        AgentSessionId sessionId,
        MessageCursor storeThrough,
        MessageCursor selectedThrough,
        boolean hasMoreHistory,
        Optional<ConversationSummary> summary,
        long summaryVersion,
        String policyVersion,
        String compressorVersion,
        List<AgentMessage> activeMessages,
        List<List<AgentMessage>> atomicGroups,
        Map<ToolCallId, ToolCall> toolCalls,
        Map<AgentMessageId, ModelContinuationRecord> continuationsByMessage,
        Map<AgentRunId, List<ModelContinuationRecord>> continuationsByRun,
        long atomicGroupCandidateScans,
        long estimatedTokens,
        long characterCount,
        long payloadBytes) {

    public ActiveContextSnapshot {
        sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        storeThrough = Objects.requireNonNull(storeThrough, "storeThrough must not be null");
        selectedThrough = Objects.requireNonNull(selectedThrough, "selectedThrough must not be null");
        summary = Objects.requireNonNull(summary, "summary must not be null");
        policyVersion = requireText(policyVersion, "policyVersion");
        compressorVersion = requireText(compressorVersion, "compressorVersion");
        activeMessages = List.copyOf(Objects.requireNonNull(activeMessages, "activeMessages must not be null"));
        atomicGroups = Objects.requireNonNull(atomicGroups, "atomicGroups must not be null").stream()
                .map(List::copyOf)
                .toList();
        toolCalls = Map.copyOf(Objects.requireNonNull(toolCalls, "toolCalls must not be null"));
        continuationsByMessage =
                Map.copyOf(Objects.requireNonNull(continuationsByMessage, "continuationsByMessage must not be null"));
        Map<AgentRunId, List<ModelContinuationRecord>> immutableByRun = new LinkedHashMap<>();
        Objects.requireNonNull(continuationsByRun, "continuationsByRun must not be null")
                .forEach((runId, records) -> immutableByRun.put(runId, List.copyOf(records)));
        continuationsByRun = Map.copyOf(immutableByRun);
        if (summaryVersion < 0
                || atomicGroupCandidateScans < 0
                || estimatedTokens < 0
                || characterCount < 0
                || payloadBytes < 0) {
            throw new IllegalArgumentException("snapshot counters must not be negative");
        }
    }

    public List<AgentMessage> selectedMessages() {
        return atomicGroups.stream().flatMap(List::stream).toList();
    }

    public boolean containsAll(List<AgentMessage> messages) {
        if (messages.size() > activeMessages.size()) return false;
        var ids = activeMessages.stream().map(AgentMessage::id).collect(java.util.stream.Collectors.toSet());
        return messages.stream().map(AgentMessage::id).allMatch(ids::contains);
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
