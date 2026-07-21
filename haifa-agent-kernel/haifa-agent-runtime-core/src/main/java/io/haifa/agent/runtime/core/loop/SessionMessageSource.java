package io.haifa.agent.runtime.core.loop;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
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
import io.haifa.agent.core.message.MessageStatus;
import io.haifa.agent.core.message.MessageVisibility;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Loads cross-Run session facts and keeps tool protocol turns atomic during window selection. */
public final class SessionMessageSource {
    public record Selection(
            List<ContextItem> items,
            MessageCursor through,
            Optional<ConversationSummary> summary,
            String policyVersion,
            String compressorVersion) {
        public Selection {
            items = List.copyOf(items);
            summary = Objects.requireNonNull(summary);
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
        List<AgentMessage> visible =
                messages.messagesAfter(run.sessionId(), MessageCursor.BEFORE_FIRST, Integer.MAX_VALUE).stream()
                        .filter(this::visibleToContext)
                        .toList();
        if (visible.isEmpty()) {
            return new Selection(
                    List.of(), MessageCursor.BEFORE_FIRST, Optional.empty(), policy.version(), compressor.version());
        }
        List<List<AgentMessage>> groups = atomicGroups(visible);
        int recentLimit = forcedRebuildAttempt > 0 ? policy.forcedRecentMessageGroups() : policy.recentMessageGroups();
        int split = Math.max(0, groups.size() - recentLimit);
        List<ContextItem> items = new ArrayList<>();
        Optional<ConversationSummary> summary = Optional.empty();
        if (split > 0) {
            List<AgentMessage> older =
                    groups.subList(0, split).stream().flatMap(List::stream).toList();
            summary = Optional.of(summaryFor(run, older));
            items.add(summaryItem(summary.orElseThrow()));
        }
        List<List<AgentMessage>> recent = groups.subList(split, groups.size());
        for (int index = 0; index < recent.size(); index++) {
            boolean current = index == recent.size() - 1;
            items.add(groupItem(recent.get(index), current));
        }
        return new Selection(items, visible.getLast().cursor(), summary, policy.version(), compressor.version());
    }

    private ConversationSummary summaryFor(AgentRun run, List<AgentMessage> source) {
        List<io.haifa.agent.core.message.AgentMessageId> sourceIds =
                source.stream().map(AgentMessage::id).toList();
        Optional<ConversationSummary> reusable = summaries
                .latestValid(run.sessionId())
                .filter(summary -> summary.sourceMessageIds().equals(sourceIds))
                .filter(summary ->
                        summaries.coversValidSource(summary, source.getLast().cursor()));
        if (reusable.isPresent()) return reusable.orElseThrow();

        long previous = summaries.latestVersion(run.sessionId());
        var result = compressor.compress(new CompressionRequest(
                new SummaryId(ids.nextValue()),
                new SummaryVersion(previous + 1),
                run.sessionId(),
                source,
                policy.maxSummaryFacts(),
                time.now(),
                policy.version()));
        ConversationSummary summary = result.summary();
        if (!summary.sessionId().equals(run.sessionId())
                || !summary.sourceMessageIds().equals(sourceIds)
                || !summary.coveredFrom().equals(source.getFirst().cursor())
                || !summary.coveredThrough().equals(source.getLast().cursor())
                || !summary.policyVersion().equals(policy.version())
                || !summary.compressorVersion().equals(compressor.version())
                || !summary.valid()) {
            throw new IllegalStateException("compressor returned an invalid coverage or version");
        }
        return summaries.compareAndSet(summary, previous);
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

    private ContextItem groupItem(List<AgentMessage> group, boolean current) {
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
                estimate(group),
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
                for (int candidate = index + 1; candidate < source.size(); candidate++) {
                    boolean matchingResult = source.get(candidate).contents().stream()
                            .filter(ToolResultPart.class::isInstance)
                            .map(ToolResultPart.class::cast)
                            .anyMatch(result -> calls.contains(result.toolCallId()));
                    if (matchingResult) end = candidate;
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

    private int estimate(List<AgentMessage> group) {
        long characters = 0L;
        for (AgentMessage message : group) {
            for (ContentPart part : message.contents()) {
                if (part instanceof TextPart text)
                    characters = Math.addExact(characters, text.text().length());
                else if (part instanceof ToolCallPart call)
                    characters = Math.addExact(characters, call.toolName().length() + 32L);
                else if (part instanceof ToolResultPart result)
                    characters = Math.addExact(characters, result.summary().length() + 16L);
            }
        }
        return Math.toIntExact(Math.max(1L, Math.min(Integer.MAX_VALUE, (characters + 3L) / 4L + 4L)));
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
