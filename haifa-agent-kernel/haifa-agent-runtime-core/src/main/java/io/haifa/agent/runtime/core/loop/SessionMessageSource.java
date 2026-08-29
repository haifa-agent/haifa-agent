package io.haifa.agent.runtime.core.loop;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.context.budget.HeuristicTokenEstimator;
import io.haifa.agent.context.compression.CompressionPolicy;
import io.haifa.agent.context.compression.CompressionRequest;
import io.haifa.agent.context.compression.ContextCompressor;
import io.haifa.agent.context.compression.ConversationSummary;
import io.haifa.agent.context.compression.ConversationSummaryRepository;
import io.haifa.agent.context.compression.SummaryId;
import io.haifa.agent.context.compression.SummaryVersion;
import io.haifa.agent.context.item.ContextItem;
import io.haifa.agent.context.item.ContextItemId;
import io.haifa.agent.context.item.ContextItemType;
import io.haifa.agent.context.item.ContextPriority;
import io.haifa.agent.context.item.ContextProvenance;
import io.haifa.agent.context.item.ContextRetention;
import io.haifa.agent.context.item.ContextSecurity;
import io.haifa.agent.context.item.ConversationSummaryContent;
import io.haifa.agent.context.item.MessageGroupContextContent;
import io.haifa.agent.core.content.ContentPart;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.content.ToolCallPart;
import io.haifa.agent.core.content.ToolResultPart;
import io.haifa.agent.core.message.AgentMessage;
import io.haifa.agent.core.message.MessageCursor;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.message.MessageStatus;
import io.haifa.agent.core.message.MessageVisibility;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.runtime.core.storage.OptimisticLockException;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Loads cross-Run session facts and keeps tool protocol turns atomic during window selection. */
public final class SessionMessageSource {
    public enum CompactionReason {
        NONE,
        TOKEN_THRESHOLD,
        FORCED_REBUILD,
        MANUAL
    }

    public record Selection(
            List<ContextItem> items,
            MessageCursor through,
            Optional<ConversationSummary> summary,
            String policyVersion,
            String compressorVersion,
            long windowGeneration,
            long compactionCount,
            boolean compacted,
            CompactionReason compactionReason,
            long compactionElapsedMillis,
            long estimatedSessionTokens,
            long sessionTokenBudget) {
        public Selection {
            items = List.copyOf(items);
            summary = Objects.requireNonNull(summary);
            compactionReason = Objects.requireNonNull(compactionReason);
        }
    }

    private final RuntimeStateRepository messages;
    private final ConversationSummaryRepository summaries;
    private final ContextCompressor compressor;
    private final CompressionPolicy policy;
    private final IdentifierGenerator ids;
    private final TimeProvider time;

    public SessionMessageSource(
            RuntimeStateRepository messages,
            ConversationSummaryRepository summaries,
            ContextCompressor compressor,
            CompressionPolicy policy,
            IdentifierGenerator ids,
            TimeProvider time) {
        this.messages = Objects.requireNonNull(messages);
        this.summaries = Objects.requireNonNull(summaries);
        this.compressor = Objects.requireNonNull(compressor);
        this.policy = Objects.requireNonNull(policy);
        this.ids = Objects.requireNonNull(ids);
        this.time = Objects.requireNonNull(time);
    }

    public Selection select(AgentRun run, int forcedRebuildAttempt) {
        return select(run, forcedRebuildAttempt, Long.MAX_VALUE);
    }

    public Selection select(AgentRun run, int forcedRebuildAttempt, long sessionTokenBudget) {
        Objects.requireNonNull(run, "run must not be null");
        return select(run.sessionId(), forcedRebuildAttempt, sessionTokenBudget, false);
    }

    /** Explicit deterministic compaction for the single linear Session path. */
    public Selection compact(AgentSessionId sessionId) {
        return select(sessionId, 1, Long.MAX_VALUE, true);
    }

    private Selection select(
            AgentSessionId sessionId, int forcedRebuildAttempt, long requestedSessionTokenBudget, boolean manual) {
        if (requestedSessionTokenBudget < 1) {
            throw new IllegalArgumentException("sessionTokenBudget must be positive");
        }
        List<AgentMessage> visible =
                messages.messagesAfter(sessionId, MessageCursor.BEFORE_FIRST, Integer.MAX_VALUE).stream()
                        .filter(this::visibleToContext)
                        .toList();
        if (visible.isEmpty()) {
            return new Selection(
                    List.of(),
                    MessageCursor.BEFORE_FIRST,
                    Optional.empty(),
                    policy.version(),
                    compressor.version(),
                    0,
                    summaries.latestVersion(sessionId),
                    false,
                    CompactionReason.NONE,
                    0,
                    0,
                    requestedSessionTokenBudget);
        }
        List<List<AgentMessage>> groups = atomicGroups(visible);
        Map<AgentRunId, Map<ToolCallId, ToolCall>> toolCallsByRun = new HashMap<>();
        Optional<ConversationSummary> checkpoint = compatibleCheckpoint(sessionId, visible);
        List<List<AgentMessage>> activeGroups = groupsAfterCheckpoint(visible, checkpoint);
        long activeTokens = checkpoint.map(ConversationSummary::estimatedTokens).orElse(0)
                + estimateGroups(activeGroups, toolCallsByRun);
        long sessionTokenBudget = requestedSessionTokenBudget == Long.MAX_VALUE
                ? Math.max(1L, activeTokens)
                : requestedSessionTokenBudget;
        boolean thresholdReached = requestedSessionTokenBudget != Long.MAX_VALUE && activeTokens >= sessionTokenBudget;
        boolean shouldCompact = manual || forcedRebuildAttempt > 0 || thresholdReached;
        CompactionReason reason = manual
                ? CompactionReason.MANUAL
                : forcedRebuildAttempt > 0
                        ? CompactionReason.FORCED_REBUILD
                        : thresholdReached ? CompactionReason.TOKEN_THRESHOLD : CompactionReason.NONE;
        if (!shouldCompact || groups.size() < 2) {
            return selection(
                    sessionId,
                    checkpoint,
                    activeGroups,
                    visible.getLast().cursor(),
                    toolCallsByRun,
                    false,
                    CompactionReason.NONE,
                    0,
                    activeTokens,
                    sessionTokenBudget);
        }

        long totalRawTokens = estimateGroups(groups, toolCallsByRun);
        long effectiveBudget = requestedSessionTokenBudget == Long.MAX_VALUE ? totalRawTokens : sessionTokenBudget;
        int retainedPercent =
                forcedRebuildAttempt > 0 ? policy.forcedRetainedTailTokenPercent() : policy.retainedTailTokenPercent();
        long retainedTailBudget = Math.max(1L, effectiveBudget * retainedPercent / 100L);
        int groupLimit = forcedRebuildAttempt > 0 ? policy.forcedRecentMessageGroups() : policy.recentMessageGroups();
        int split = tailSplit(groups, retainedTailBudget, groupLimit, toolCallsByRun);
        if (split == 0) {
            return selection(
                    sessionId,
                    checkpoint,
                    activeGroups,
                    visible.getLast().cursor(),
                    toolCallsByRun,
                    false,
                    CompactionReason.NONE,
                    0,
                    activeTokens,
                    sessionTokenBudget);
        }

        List<AgentMessage> older =
                groups.subList(0, split).stream().flatMap(List::stream).toList();
        long compactionStarted = System.nanoTime();
        ConversationSummary summary = summaryFor(sessionId, older, visible);
        long compactionElapsedMillis =
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - compactionStarted));
        List<List<AgentMessage>> tail = groupsAfterCheckpoint(visible, Optional.of(summary));
        long compactedTokens = summary.estimatedTokens() + estimateGroups(tail, toolCallsByRun);
        MessageCursor selectedThrough =
                summary.coveredThrough().compareTo(visible.getLast().cursor()) > 0
                        ? summary.coveredThrough()
                        : visible.getLast().cursor();
        return selection(
                sessionId,
                Optional.of(summary),
                tail,
                selectedThrough,
                toolCallsByRun,
                checkpoint.isEmpty() || !checkpoint.orElseThrow().equals(summary),
                reason,
                compactionElapsedMillis,
                compactedTokens,
                sessionTokenBudget);
    }

    private Selection selection(
            AgentSessionId sessionId,
            Optional<ConversationSummary> summary,
            List<List<AgentMessage>> groups,
            MessageCursor through,
            Map<AgentRunId, Map<ToolCallId, ToolCall>> toolCallsByRun,
            boolean compacted,
            CompactionReason reason,
            long compactionElapsedMillis,
            long estimatedTokens,
            long sessionTokenBudget) {
        List<ContextItem> items = new ArrayList<>();
        summary.ifPresent(value -> items.add(summaryItem(value)));
        for (int index = 0; index < groups.size(); index++) {
            items.add(groupItem(groups.get(index), index == groups.size() - 1, toolCallsByRun));
        }
        return new Selection(
                items,
                through,
                summary,
                policy.version(),
                compressor.version(),
                summary.map(value -> value.version().value()).orElse(0L),
                summaries.latestVersion(sessionId),
                compacted,
                reason,
                compactionElapsedMillis,
                estimatedTokens,
                sessionTokenBudget);
    }

    private Optional<ConversationSummary> compatibleCheckpoint(AgentSessionId sessionId, List<AgentMessage> visible) {
        return summaries
                .latestValid(sessionId)
                .filter(summary -> summary.policyVersion().equals(policy.version()))
                .filter(summary -> summary.compressorVersion().equals(compressor.version()))
                .filter(summary -> summaries.coversValidSource(summary, summary.coveredThrough()))
                .filter(summary -> {
                    List<List<AgentMessage>> after = groupsAfterCheckpoint(visible, Optional.of(summary));
                    return after.isEmpty() || isTurnAnchor(after.getFirst());
                });
    }

    private List<List<AgentMessage>> groupsAfterCheckpoint(
            List<AgentMessage> visible, Optional<ConversationSummary> checkpoint) {
        return checkpoint
                .map(summary -> atomicGroups(visible.stream()
                        .filter(message -> message.cursor().compareTo(summary.coveredThrough()) > 0)
                        .toList()))
                .orElseGet(() -> atomicGroups(visible));
    }

    private int tailSplit(
            List<List<AgentMessage>> groups,
            long retainedTailBudget,
            int groupLimit,
            Map<AgentRunId, Map<ToolCallId, ToolCall>> toolCallsByRun) {
        long retained = 0L;
        int retainedGroups = 0;
        int candidateSplit = groups.size();
        for (int index = groups.size() - 1; index >= 0; index--) {
            long groupTokens = estimate(groups.get(index), toolCallsByRun);
            if (retainedGroups > 0 && (retainedGroups >= groupLimit || retained + groupTokens > retainedTailBudget))
                break;
            retained = saturatedAdd(retained, groupTokens);
            retainedGroups++;
            candidateSplit = index;
        }
        if (candidateSplit == 0 || candidateSplit >= groups.size()) {
            return candidateSplit;
        }
        if (isTurnAnchor(groups.get(candidateSplit))) {
            return candidateSplit;
        }
        int forwardAnchor = candidateSplit + 1;
        while (forwardAnchor < groups.size() && !isTurnAnchor(groups.get(forwardAnchor))) {
            forwardAnchor++;
        }
        if (forwardAnchor < groups.size()) {
            return forwardAnchor;
        }
        int backwardAnchor = candidateSplit;
        while (backwardAnchor > 0 && !isTurnAnchor(groups.get(backwardAnchor))) {
            backwardAnchor--;
        }
        if (backwardAnchor > 0 && isTurnAnchor(groups.get(backwardAnchor))) {
            return backwardAnchor;
        }
        return 0;
    }

    private boolean isTurnAnchor(List<AgentMessage> group) {
        if (group.isEmpty()) {
            return false;
        }
        AgentMessage first = group.getFirst();
        return first.role() == MessageRole.USER;
    }

    private ConversationSummary summaryFor(
            AgentSessionId sessionId, List<AgentMessage> source, List<AgentMessage> visible) {
        List<io.haifa.agent.core.message.AgentMessageId> sourceIds =
                source.stream().map(AgentMessage::id).toList();
        Optional<ConversationSummary> reusable = compatibleCheckpoint(sessionId, visible)
                .filter(summary -> summary.sourceMessageIds().equals(sourceIds))
                .filter(summary ->
                        summaries.coversValidSource(summary, source.getLast().cursor()));
        if (reusable.isPresent()) return reusable.orElseThrow();

        long previous = summaries.latestVersion(sessionId);
        var result = compressor.compress(new CompressionRequest(
                new SummaryId(ids.nextValue()),
                new SummaryVersion(previous + 1),
                sessionId,
                source,
                policy.maxSummaryFacts(),
                time.now(),
                policy.version()));
        ConversationSummary summary = result.summary();
        if (!summary.sessionId().equals(sessionId)
                || !summary.sourceMessageIds().equals(sourceIds)
                || !summary.coveredFrom().equals(source.getFirst().cursor())
                || !summary.coveredThrough().equals(source.getLast().cursor())
                || !summary.policyVersion().equals(policy.version())
                || !summary.compressorVersion().equals(compressor.version())
                || !summary.valid()) {
            throw new IllegalStateException("compressor returned an invalid coverage or version");
        }
        try {
            return summaries.compareAndSet(summary, previous);
        } catch (OptimisticLockException conflict) {
            return compatibleCheckpoint(sessionId, visible)
                    .filter(winner -> winner.sourceMessageIds().size() >= sourceIds.size())
                    .filter(winner -> winner.sourceMessageIds()
                            .subList(0, sourceIds.size())
                            .equals(sourceIds))
                    .filter(winner ->
                            winner.coveredThrough().compareTo(source.getLast().cursor()) >= 0)
                    .orElseThrow(() -> conflict);
        }
    }

    private ContextItem summaryItem(ConversationSummary summary) {
        return new ContextItem(
                new ContextItemId("summary-" + summary.id().value() + "-"
                        + summary.version().value()),
                ContextItemType.CONVERSATION_SUMMARY,
                new ConversationSummaryContent(
                        summary.id().value(),
                        summary.version().value(),
                        summary.facts(),
                        summary.decisions(),
                        summary.openItems(),
                        summary.toolOutcomeReferences().stream()
                                .map(ToolCallId::value)
                                .toList()),
                summary.estimatedTokens(),
                ContextPriority.HIGH,
                ContextRetention.COMPRESSIBLE,
                new ContextSecurity(summary.securityLabels(), true),
                new ContextProvenance(
                        "conversation-summary",
                        summary.id().value(),
                        Long.toString(summary.version().value()),
                        summary.sourceHash()),
                Map.of(
                        "coveredFrom", summary.coveredFrom().serialize(),
                        "coveredThrough", summary.coveredThrough().serialize()));
    }

    private ContextItem groupItem(
            List<AgentMessage> group, boolean current, Map<AgentRunId, Map<ToolCallId, ToolCall>> toolCallsByRun) {
        AgentMessage first = group.getFirst();
        AgentMessage last = group.getLast();
        String groupHash = hash(group.stream()
                .map(message -> message.id().value() + "@" + message.sequence())
                .toList()
                .toString());
        return new ContextItem(
                new ContextItemId("message-group-" + first.sequence() + "-" + last.sequence()),
                ContextItemType.MESSAGE,
                new MessageGroupContextContent(group),
                estimate(group, toolCallsByRun),
                current ? ContextPriority.CRITICAL : ContextPriority.NORMAL,
                current ? ContextRetention.MUST_KEEP : ContextRetention.COMPRESSIBLE,
                new ContextSecurity(Set.of("session-visible"), true),
                new ContextProvenance(
                        "session-message-group",
                        first.id().value(),
                        first.cursor().serialize() + ".." + last.cursor().serialize(),
                        groupHash),
                Map.of(
                        "fromCursor", first.cursor().serialize(),
                        "throughCursor", last.cursor().serialize(),
                        "messageCount", Integer.toString(group.size())));
    }

    private List<List<AgentMessage>> atomicGroups(List<AgentMessage> source) {
        List<List<AgentMessage>> groups = new ArrayList<>();
        int index = 0;
        while (index < source.size()) {
            AgentMessage message = source.get(index);
            Set<ToolCallId> calls = message.contents().stream()
                    .filter(ToolCallPart.class::isInstance)
                    .map(ToolCallPart.class::cast)
                    .map(ToolCallPart::toolCallId)
                    .collect(java.util.stream.Collectors.toCollection(HashSet::new));
            int end = index;
            if (!calls.isEmpty()) {
                Set<ToolCallId> results = new HashSet<>();
                for (int candidate = index + 1; candidate < source.size(); candidate++) {
                    Set<ToolCallId> matchingResults = source.get(candidate).contents().stream()
                            .filter(ToolResultPart.class::isInstance)
                            .map(ToolResultPart.class::cast)
                            .map(ToolResultPart::toolCallId)
                            .filter(calls::contains)
                            .collect(java.util.stream.Collectors.toSet());
                    if (!matchingResults.isEmpty()) {
                        results.addAll(matchingResults);
                        end = candidate;
                    }
                }
                if (!results.containsAll(calls)) {
                    index = end + 1;
                    continue;
                }
            }
            groups.add(List.copyOf(source.subList(index, end + 1)));
            index = end + 1;
        }
        return List.copyOf(groups);
    }

    private boolean visibleToContext(AgentMessage message) {
        return message.status() == MessageStatus.COMPLETED
                && (message.visibility() == MessageVisibility.USER_VISIBLE
                        || message.visibility() == MessageVisibility.AGENT_VISIBLE);
    }

    private int estimate(List<AgentMessage> group, Map<AgentRunId, Map<ToolCallId, ToolCall>> toolCallsByRun) {
        long estimatedTokens = 4L;
        for (AgentMessage message : group) {
            estimatedTokens = saturatedAdd(estimatedTokens, 6L);
            Map<ToolCallId, ToolCall> authoritativeCalls = message.runId()
                    .map(runId -> toolCallsByRun.computeIfAbsent(runId, this::toolCallsById))
                    .orElseGet(Map::of);
            for (ContentPart part : message.contents()) {
                if (part instanceof TextPart text) {
                    estimatedTokens = saturatedAdd(estimatedTokens, HeuristicTokenEstimator.tokens(text.text()));
                } else if (part instanceof ToolCallPart call) {
                    estimatedTokens = saturatedAdd(
                            estimatedTokens,
                            sumTokens(
                                    HeuristicTokenEstimator.tokens(call.toolName()),
                                    HeuristicTokenEstimator.tokens(
                                            call.providerCorrelationId().value()),
                                    authoritativeCalls.containsKey(call.toolCallId())
                                            ? HeuristicTokenEstimator.tokens(authoritativeCalls
                                                    .get(call.toolCallId())
                                                    .arguments()
                                                    .values())
                                            : 0,
                                    12));
                } else if (part instanceof ToolResultPart result) {
                    ToolCall authoritative = authoritativeCalls.get(result.toolCallId());
                    estimatedTokens = saturatedAdd(
                            estimatedTokens,
                            sumTokens(
                                    HeuristicTokenEstimator.tokens(result.summary()),
                                    HeuristicTokenEstimator.tokens(
                                            result.providerCorrelationId().value()),
                                    authoritative == null
                                            ? 0
                                            : authoritative
                                                    .result()
                                                    .map(value ->
                                                            HeuristicTokenEstimator.tokens(value.structuredData()))
                                                    .orElse(0),
                                    12));
                }
            }
        }
        return Math.toIntExact(Math.max(1L, Math.min(Integer.MAX_VALUE, estimatedTokens)));
    }

    private long estimateGroups(
            List<List<AgentMessage>> groups, Map<AgentRunId, Map<ToolCallId, ToolCall>> toolCallsByRun) {
        long estimated = 0L;
        for (List<AgentMessage> group : groups) {
            estimated = saturatedAdd(estimated, estimate(group, toolCallsByRun));
        }
        return estimated;
    }

    private Map<ToolCallId, ToolCall> toolCallsById(AgentRunId runId) {
        return messages.toolCalls(runId).stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(ToolCall::id, call -> call));
    }

    private long saturatedAdd(long left, long right) {
        return Math.min(Integer.MAX_VALUE, left + right);
    }

    private int sumTokens(int... values) {
        long estimated = 0L;
        for (int value : values) estimated = saturatedAdd(estimated, value);
        return (int) estimated;
    }

    private String hash(String value) {
        try {
            return "sha256:"
                    + HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
