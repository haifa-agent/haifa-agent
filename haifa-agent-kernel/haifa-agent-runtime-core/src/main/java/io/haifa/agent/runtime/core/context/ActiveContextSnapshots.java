package io.haifa.agent.runtime.core.context;

import io.haifa.agent.context.compression.CompressionPolicy;
import io.haifa.agent.context.compression.ContextCompressor;
import io.haifa.agent.context.compression.ConversationSummary;
import io.haifa.agent.context.compression.ConversationSummaryRepository;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.content.ToolCallPart;
import io.haifa.agent.core.content.ToolResultPart;
import io.haifa.agent.core.message.AgentMessage;
import io.haifa.agent.core.message.AgentMessageId;
import io.haifa.agent.core.message.MessageCursor;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.message.MessageStatus;
import io.haifa.agent.core.message.MessageVisibility;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.runtime.core.loop.TokenBudget;
import io.haifa.agent.runtime.core.model.continuation.ModelContinuationRecord;
import io.haifa.agent.runtime.core.storage.MessageRedactionListener;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Owns a small LRU of rebuildable active-context projections and incrementally follows committed Session cursors. */
public final class ActiveContextSnapshots implements MessageRedactionListener {
    static final int MAX_ACTIVE_MESSAGES = 4096;
    static final int MAX_PROTOCOL_LOOKAHEAD = 256;
    static final int MAX_ACTIVE_GROUPS = 4096;
    static final long MAX_ACTIVE_ESTIMATED_TOKENS = 2_000_000L;
    static final long MAX_ACTIVE_CHARACTERS = 8_000_000L;
    static final long MAX_ACTIVE_PAYLOAD_BYTES = 32_000_000L;
    static final long MAX_SINGLE_TOOL_RESULT_PAYLOAD_BYTES = 8_000_000L;
    static final int MAX_SNAPSHOTS = 32;
    private static final int MAX_INVALIDATIONS = 64;
    private static final int SESSION_LOCK_STRIPES = 64;

    public enum RebuildReason {
        HIT,
        INITIAL,
        DELTA,
        SUMMARY_CHANGED,
        REDACTION,
        RELEASED
    }

    public record AccessMetrics(
            boolean snapshotHit,
            RebuildReason rebuildReason,
            long historyRowsRead,
            long activeRowsSelected,
            long atomicGroupCandidateScans,
            long atomicGroupsBuilt,
            long toolCallBatchCount,
            long continuationBatchCount,
            long continuationRecordCount,
            long snapshotDeltaRows,
            long snapshotEstimatedTokens,
            long snapshotCharacters,
            long snapshotPayloadBytes) {
        public AccessMetrics {
            Objects.requireNonNull(rebuildReason, "rebuildReason must not be null");
        }
    }

    public record Access(ActiveContextSnapshot snapshot, AccessMetrics metrics) {
        public Access {
            Objects.requireNonNull(snapshot, "snapshot must not be null");
            Objects.requireNonNull(metrics, "metrics must not be null");
        }
    }

    private final RuntimeStateRepository state;
    private final ConversationSummaryRepository summaries;
    private final CompressionPolicy policy;
    private final ContextCompressor compressor;
    private final Map<AgentSessionId, ActiveContextSnapshot> snapshots = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<AgentSessionId, RebuildReason> invalidations = new LinkedHashMap<>(16, 0.75f, true);
    private final Object[] sessionLocks = createSessionLocks();

    public ActiveContextSnapshots(
            RuntimeStateRepository state,
            ConversationSummaryRepository summaries,
            CompressionPolicy policy,
            ContextCompressor compressor) {
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.summaries = Objects.requireNonNull(summaries, "summaries must not be null");
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.compressor = Objects.requireNonNull(compressor, "compressor must not be null");
    }

    public Access current(AgentSessionId sessionId) {
        return current(sessionId, false);
    }

    /** Allows the compaction coordinator to consume one bounded page when unsummarized history is oversized. */
    public Access currentForCompaction(AgentSessionId sessionId) {
        return current(sessionId, true);
    }

    private Access current(AgentSessionId sessionId, boolean allowPartial) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        synchronized (sessionLock(sessionId)) {
            return currentLocked(sessionId, allowPartial);
        }
    }

    private Access currentLocked(AgentSessionId sessionId, boolean allowPartial) {
        MessageCursor latest = state.latestMessageCursor(sessionId).orElse(MessageCursor.BEFORE_FIRST);
        long latestSummaryVersion = summaries.latestVersion(sessionId);
        ActiveContextSnapshot cached;
        RebuildReason invalidation;
        synchronized (snapshots) {
            cached = snapshots.get(sessionId);
            invalidation = invalidations.remove(sessionId);
        }

        if (invalidation == null
                && cached != null
                && cached.summaryVersion() == latestSummaryVersion
                && cached.storeThrough().equals(latest)) {
            return access(cached, true, RebuildReason.HIT, 0, 0);
        }

        if (invalidation == null
                && cached != null
                && cached.summaryVersion() == latestSummaryVersion
                && cached.storeThrough().compareTo(latest) < 0) {
            int remaining = MAX_ACTIVE_MESSAGES - cached.activeMessages().size();
            if (remaining > 0) {
                List<AgentMessage> delta = state.messagesAfter(sessionId, cached.storeThrough(), remaining + 1);
                if (delta.size() <= remaining) {
                    List<AgentMessage> combined = new ArrayList<>(cached.activeMessages());
                    combined.addAll(
                            delta.stream().filter(this::visibleToContext).toList());
                    ActiveContextSnapshot refreshed = build(
                            sessionId,
                            latest,
                            false,
                            cached.summary(),
                            latestSummaryVersion,
                            combined,
                            cached.summary().isPresent()
                                    ? cached.summary().orElseThrow().coveredThrough()
                                    : null);
                    put(refreshed);
                    return access(refreshed, false, RebuildReason.DELTA, delta.size(), delta.size());
                }
            }
        }

        RebuildReason reason = invalidation != null
                ? invalidation
                : cached == null ? RebuildReason.INITIAL : RebuildReason.SUMMARY_CHANGED;
        Optional<ConversationSummary> summary = compatibleSummary(sessionId);
        MessageCursor boundary =
                summary.map(ConversationSummary::coveredThrough).orElse(MessageCursor.BEFORE_FIRST);
        int loadLimit = allowPartial ? MAX_ACTIVE_MESSAGES + MAX_PROTOCOL_LOOKAHEAD + 1 : MAX_ACTIVE_MESSAGES + 1;
        List<AgentMessage> rows = state.messagesAfter(sessionId, boundary, loadLimit);
        boolean hasMoreHistory = rows.size() > MAX_ACTIVE_MESSAGES;
        if (hasMoreHistory && !allowPartial) {
            throw new ActiveContextWindowLimitException("messages", MAX_ACTIVE_MESSAGES, rows.size());
        }
        int rowsRead = rows.size();
        if (hasMoreHistory) rows = boundedProtocolPage(rows);
        List<AgentMessage> visible =
                rows.stream().filter(this::visibleToContext).toList();
        MessageCursor loadedThrough =
                hasMoreHistory && !rows.isEmpty() ? rows.getLast().cursor() : latest;
        ActiveContextSnapshot rebuilt = build(
                sessionId,
                loadedThrough,
                hasMoreHistory,
                summary,
                latestSummaryVersion,
                visible,
                summary.isPresent() ? boundary : null);
        if (summary.isPresent()
                && !rebuilt.atomicGroups().isEmpty()
                && rebuilt.atomicGroups().getFirst().getFirst().role() != MessageRole.USER) {
            rows = state.messagesAfter(sessionId, MessageCursor.BEFORE_FIRST, loadLimit);
            hasMoreHistory = rows.size() > MAX_ACTIVE_MESSAGES;
            if (hasMoreHistory && !allowPartial) {
                throw new ActiveContextWindowLimitException("messages", MAX_ACTIVE_MESSAGES, rows.size());
            }
            rowsRead = rows.size();
            if (hasMoreHistory) rows = boundedProtocolPage(rows);
            visible = rows.stream().filter(this::visibleToContext).toList();
            loadedThrough = hasMoreHistory && !rows.isEmpty() ? rows.getLast().cursor() : latest;
            rebuilt = build(
                    sessionId, loadedThrough, hasMoreHistory, Optional.empty(), latestSummaryVersion, visible, null);
            reason = RebuildReason.SUMMARY_CHANGED;
        }
        put(rebuilt);
        return access(rebuilt, false, reason, rowsRead, 0);
    }

    public Optional<ActiveContextSnapshot> cached(AgentSessionId sessionId) {
        synchronized (snapshots) {
            return Optional.ofNullable(snapshots.get(sessionId));
        }
    }

    public void invalidate(AgentSessionId sessionId, RebuildReason reason) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        synchronized (sessionLock(sessionId)) {
            synchronized (snapshots) {
                snapshots.remove(sessionId);
                invalidations.put(sessionId, reason);
                while (invalidations.size() > MAX_INVALIDATIONS) {
                    invalidations.remove(invalidations.keySet().iterator().next());
                }
            }
        }
    }

    public void release(AgentSessionId sessionId) {
        invalidate(sessionId, RebuildReason.RELEASED);
    }

    @Override
    public void onRedacted(AgentMessage source) {
        invalidate(source.sessionId(), RebuildReason.REDACTION);
    }

    private Optional<ConversationSummary> compatibleSummary(AgentSessionId sessionId) {
        return summaries
                .latestValid(sessionId)
                .filter(summary -> summary.policyVersion().equals(policy.version()))
                .filter(summary -> isCompatibleCompressor(summary.compressorVersion()))
                .filter(summary -> summaries.coversValidSource(summary, summary.coveredThrough()));
    }

    private ActiveContextSnapshot build(
            AgentSessionId sessionId,
            MessageCursor storeThrough,
            boolean hasMoreHistory,
            Optional<ConversationSummary> summary,
            long summaryVersion,
            List<AgentMessage> visible,
            MessageCursor boundary) {
        AtomicMessageGroups.Result grouped = AtomicMessageGroups.group(visible);
        enforceLimit("groups", MAX_ACTIVE_GROUPS, grouped.groups().size());
        List<AgentMessage> selected =
                grouped.groups().stream().flatMap(List::stream).toList();
        Map<AgentRunId, Set<ToolCallId>> toolIdsByRun = new LinkedHashMap<>();
        Map<AgentRunId, Set<AgentMessageId>> continuationMessagesByRun = new LinkedHashMap<>();
        for (AgentMessage message : visible) {
            message.runId().ifPresent(runId -> {
                for (var part : message.contents()) {
                    if (part instanceof ToolCallPart call) {
                        toolIdsByRun
                                .computeIfAbsent(runId, ignored -> new LinkedHashSet<>())
                                .add(call.toolCallId());
                        continuationMessagesByRun
                                .computeIfAbsent(runId, ignored -> new LinkedHashSet<>())
                                .add(message.id());
                    } else if (part instanceof ToolResultPart result) {
                        toolIdsByRun
                                .computeIfAbsent(runId, ignored -> new LinkedHashSet<>())
                                .add(result.toolCallId());
                    }
                }
            });
        }

        Map<ToolCallId, ToolCall> toolCalls = state.toolCallsByIds(toolIdsByRun).stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        ToolCall::id, call -> call, (left, right) -> right));
        List<ModelContinuationRecord> continuations = state.continuationsForMessages(continuationMessagesByRun);
        Map<AgentMessageId, ModelContinuationRecord> continuationsByMessage = continuations.stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        ModelContinuationRecord::assistantMessageId, record -> record));
        Map<AgentRunId, List<ModelContinuationRecord>> continuationsByRun = continuations.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        ModelContinuationRecord::runId, LinkedHashMap::new, java.util.stream.Collectors.toList()));

        long tokens = saturatedAdd(
                summary.map(ConversationSummary::estimatedTokens).orElse(0),
                grouped.groups().size() * 4L);
        String encodedSummary = summary.map(Object::toString).orElse("");
        long characters = encodedSummary.length();
        long payloadBytes = utf8Bytes(encodedSummary);
        for (AgentMessage message : selected) {
            tokens = saturatedAdd(tokens, 6L);
            for (var part : message.contents()) {
                if (part instanceof TextPart text) {
                    tokens = saturatedAdd(tokens, TokenBudget.tokens(text.text()));
                    characters = saturatedAdd(characters, text.text().length());
                    payloadBytes = saturatedAdd(payloadBytes, utf8Bytes(text.text()));
                } else if (part instanceof ToolCallPart callPart) {
                    ToolCall call = toolCalls.get(callPart.toolCallId());
                    String arguments =
                            call == null ? "" : call.arguments().values().toString();
                    String correlation = callPart.providerCorrelationId().value();
                    tokens = saturatedAdd(tokens, TokenBudget.tokens(callPart.toolName()));
                    tokens = saturatedAdd(tokens, TokenBudget.tokens(correlation));
                    tokens = saturatedAdd(
                            tokens,
                            call == null
                                    ? 0L
                                    : TokenBudget.tokens(call.arguments().values()));
                    tokens = saturatedAdd(tokens, 12L);
                    characters = saturatedAdd(
                            characters, callPart.toolName().length() + correlation.length() + arguments.length());
                    payloadBytes = saturatedAdd(
                            payloadBytes,
                            utf8Bytes(callPart.toolName()) + utf8Bytes(correlation) + utf8Bytes(arguments));
                } else if (part instanceof ToolResultPart resultPart) {
                    ToolCall call = toolCalls.get(resultPart.toolCallId());
                    String structured = call == null
                            ? ""
                            : call.result()
                                    .map(result -> result.structuredData().toString())
                                    .orElse("");
                    long resultPayloadBytes = saturatedAdd(utf8Bytes(resultPart.summary()), utf8Bytes(structured));
                    enforceLimit(
                            "singleToolResultPayloadBytes", MAX_SINGLE_TOOL_RESULT_PAYLOAD_BYTES, resultPayloadBytes);
                    tokens = saturatedAdd(
                            tokens,
                            TokenBudget.tokens(resultPart.summary())
                                    + TokenBudget.tokens(
                                            resultPart.providerCorrelationId().value())
                                    + (call == null
                                            ? 0L
                                            : call.result()
                                                    .map(result -> (long) TokenBudget.tokens(result.structuredData()))
                                                    .orElse(0L))
                                    + 12L);
                    characters = saturatedAdd(
                            characters,
                            resultPart.summary().length()
                                    + resultPart.providerCorrelationId().value().length()
                                    + structured.length());
                    payloadBytes = saturatedAdd(
                            payloadBytes,
                            resultPayloadBytes
                                    + utf8Bytes(
                                            resultPart.providerCorrelationId().value()));
                } else {
                    String encoded = part.toString();
                    characters = saturatedAdd(characters, encoded.length());
                    payloadBytes = saturatedAdd(payloadBytes, utf8Bytes(encoded));
                }
            }
        }
        Set<AgentMessageId> selectedMessageIds =
                selected.stream().map(AgentMessage::id).collect(java.util.stream.Collectors.toUnmodifiableSet());
        for (AgentMessage message : visible) {
            if (selectedMessageIds.contains(message.id())) continue;
            for (var part : message.contents()) {
                if (part instanceof TextPart text) {
                    characters = saturatedAdd(characters, text.text().length());
                    payloadBytes = saturatedAdd(payloadBytes, utf8Bytes(text.text()));
                } else if (part instanceof ToolCallPart callPart) {
                    ToolCall call = toolCalls.get(callPart.toolCallId());
                    String arguments =
                            call == null ? "" : call.arguments().values().toString();
                    String correlation = callPart.providerCorrelationId().value();
                    characters = saturatedAdd(
                            characters, callPart.toolName().length() + correlation.length() + arguments.length());
                    payloadBytes = saturatedAdd(
                            payloadBytes,
                            utf8Bytes(callPart.toolName()) + utf8Bytes(correlation) + utf8Bytes(arguments));
                } else if (part instanceof ToolResultPart resultPart) {
                    ToolCall call = toolCalls.get(resultPart.toolCallId());
                    String structured = call == null
                            ? ""
                            : call.result()
                                    .map(result -> result.structuredData().toString())
                                    .orElse("");
                    long resultPayloadBytes = saturatedAdd(utf8Bytes(resultPart.summary()), utf8Bytes(structured));
                    enforceLimit(
                            "singleToolResultPayloadBytes", MAX_SINGLE_TOOL_RESULT_PAYLOAD_BYTES, resultPayloadBytes);
                    characters = saturatedAdd(
                            characters,
                            resultPart.summary().length()
                                    + resultPart.providerCorrelationId().value().length()
                                    + structured.length());
                    payloadBytes = saturatedAdd(
                            payloadBytes,
                            resultPayloadBytes
                                    + utf8Bytes(
                                            resultPart.providerCorrelationId().value()));
                } else {
                    String encoded = part.toString();
                    characters = saturatedAdd(characters, encoded.length());
                    payloadBytes = saturatedAdd(payloadBytes, utf8Bytes(encoded));
                }
            }
        }
        enforceLimit("estimatedTokens", MAX_ACTIVE_ESTIMATED_TOKENS, tokens);
        enforceLimit("characters", MAX_ACTIVE_CHARACTERS, characters);
        enforceLimit("payloadBytes", MAX_ACTIVE_PAYLOAD_BYTES, payloadBytes);

        MessageCursor selectedThrough = visible.isEmpty()
                ? summary.map(ConversationSummary::coveredThrough).orElse(MessageCursor.BEFORE_FIRST)
                : visible.getLast().cursor();
        if (boundary != null && selectedThrough.compareTo(boundary) < 0) selectedThrough = boundary;
        return new ActiveContextSnapshot(
                sessionId,
                storeThrough,
                selectedThrough,
                hasMoreHistory,
                summary,
                summaryVersion,
                policy.version(),
                compressor.version(),
                visible,
                grouped.groups(),
                toolCalls,
                continuationsByMessage,
                continuationsByRun,
                grouped.candidateScans(),
                tokens,
                characters,
                payloadBytes);
    }

    private Access access(
            ActiveContextSnapshot snapshot, boolean hit, RebuildReason reason, long rowsRead, long deltaRows) {
        return new Access(
                snapshot,
                new AccessMetrics(
                        hit,
                        reason,
                        rowsRead,
                        snapshot.atomicGroups().stream().mapToLong(List::size).sum(),
                        hit ? 0L : snapshot.atomicGroupCandidateScans(),
                        snapshot.atomicGroups().size(),
                        snapshot.toolCalls().isEmpty() ? 0 : 1,
                        snapshot.continuationsByMessage().isEmpty() ? 0 : 1,
                        snapshot.continuationsByMessage().size(),
                        deltaRows,
                        snapshot.estimatedTokens(),
                        snapshot.characterCount(),
                        snapshot.payloadBytes()));
    }

    private void put(ActiveContextSnapshot snapshot) {
        synchronized (snapshots) {
            snapshots.put(snapshot.sessionId(), snapshot);
            while (snapshots.size() > MAX_SNAPSHOTS) {
                AgentSessionId eldest = snapshots.keySet().iterator().next();
                snapshots.remove(eldest);
                invalidations.remove(eldest);
            }
        }
    }

    private Object sessionLock(AgentSessionId sessionId) {
        return sessionLocks[Math.floorMod(sessionId.hashCode(), sessionLocks.length)];
    }

    private static Object[] createSessionLocks() {
        Object[] locks = new Object[SESSION_LOCK_STRIPES];
        java.util.Arrays.setAll(locks, ignored -> new Object());
        return locks;
    }

    private static List<AgentMessage> boundedProtocolPage(List<AgentMessage> loaded) {
        int candidateEnd = Math.min(MAX_ACTIVE_MESSAGES, loaded.size());
        Map<ToolCallId, Integer> callStarts = new LinkedHashMap<>();
        Set<ToolCallId> candidateResults = new LinkedHashSet<>();
        Set<ToolCallId> lookaheadResults = new LinkedHashSet<>();
        for (int index = 0; index < loaded.size(); index++) {
            AgentMessage message = loaded.get(index);
            for (var part : message.contents()) {
                if (part instanceof ToolCallPart call && index < candidateEnd) {
                    callStarts.putIfAbsent(call.toolCallId(), index);
                } else if (part instanceof ToolResultPart result) {
                    lookaheadResults.add(result.toolCallId());
                    if (index < candidateEnd) candidateResults.add(result.toolCallId());
                }
            }
        }
        int safeEnd = candidateEnd;
        Map<Integer, Set<ToolCallId>> callsByStart = new LinkedHashMap<>();
        callStarts.forEach((callId, start) -> callsByStart
                .computeIfAbsent(start, ignored -> new LinkedHashSet<>())
                .add(callId));
        for (var entry : callsByStart.entrySet()) {
            Set<ToolCallId> calls = entry.getValue();
            if (!candidateResults.containsAll(calls) && lookaheadResults.containsAll(calls)) {
                safeEnd = Math.min(safeEnd, entry.getKey());
            }
        }
        if (safeEnd == 0) {
            throw new ActiveContextWindowLimitException("atomicToolGroupMessages", MAX_ACTIVE_MESSAGES, loaded.size());
        }
        return List.copyOf(loaded.subList(0, safeEnd));
    }

    private boolean isCompatibleCompressor(String version) {
        return version != null && (version.equals(compressor.version()) || version.startsWith("semantic-"));
    }

    private boolean visibleToContext(AgentMessage message) {
        return message.status() == MessageStatus.COMPLETED
                && (message.visibility() == MessageVisibility.USER_VISIBLE
                        || message.visibility() == MessageVisibility.AGENT_VISIBLE);
    }

    private static long utf8Bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }

    private static void enforceLimit(String kind, long limit, long observed) {
        if (observed > limit) throw new ActiveContextWindowLimitException(kind, limit, observed);
    }
}
