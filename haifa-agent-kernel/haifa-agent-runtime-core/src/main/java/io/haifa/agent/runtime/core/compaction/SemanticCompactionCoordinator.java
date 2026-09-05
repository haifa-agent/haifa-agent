package io.haifa.agent.runtime.core.compaction;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.context.budget.HeuristicTokenEstimator;
import io.haifa.agent.context.compression.CompactionPromptRenderer;
import io.haifa.agent.context.compression.CompactionQuality;
import io.haifa.agent.context.compression.CompactionSourceProjector;
import io.haifa.agent.context.compression.CompressionPolicy;
import io.haifa.agent.context.compression.ContextCompressor;
import io.haifa.agent.context.compression.ConversationSummary;
import io.haifa.agent.context.compression.ConversationSummaryRepository;
import io.haifa.agent.context.compression.ProjectedCompactionSource;
import io.haifa.agent.context.compression.SemanticConversationSummaryV1;
import io.haifa.agent.context.compression.SemanticDecisionItem;
import io.haifa.agent.context.compression.SemanticProgress;
import io.haifa.agent.context.compression.SemanticSummaryItem;
import io.haifa.agent.context.compression.SemanticSummaryRenderer;
import io.haifa.agent.context.compression.SemanticSummaryValidationException;
import io.haifa.agent.context.compression.SemanticSummaryValidator;
import io.haifa.agent.context.compression.SummaryId;
import io.haifa.agent.context.compression.SummaryVersion;
import io.haifa.agent.core.content.ToolCallPart;
import io.haifa.agent.core.content.ToolResultPart;
import io.haifa.agent.core.message.AgentMessage;
import io.haifa.agent.core.message.AgentMessageId;
import io.haifa.agent.core.message.MessageCursor;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.message.MessageStatus;
import io.haifa.agent.core.message.MessageVisibility;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.runtime.core.model.FrozenModelBinding;
import io.haifa.agent.runtime.core.storage.OptimisticLockException;
import io.haifa.agent.runtime.core.storage.RuntimeEventAppender;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates semantic conversation summarization and compaction before context build.
 * Manages planning, safe projection, model invocation, validation gates, repair, and CAS commit.
 */
public final class SemanticCompactionCoordinator {

    private static final Logger log = LoggerFactory.getLogger(SemanticCompactionCoordinator.class);

    private final RuntimeStateRepository state;
    private final ConversationSummaryRepository summaries;
    private final SummaryModelInvoker invoker;
    private final CompactionTriggerEvaluator triggerEvaluator;
    private final CompressionPolicy policy;
    private final ContextCompressor deterministicCompressor;
    private final IdentifierGenerator ids;
    private final TimeProvider time;
    private final RuntimeEventAppender events;

    public SemanticCompactionCoordinator(
            RuntimeStateRepository state,
            ConversationSummaryRepository summaries,
            SummaryModelInvoker invoker,
            CompactionTriggerEvaluator triggerEvaluator,
            CompressionPolicy policy,
            ContextCompressor deterministicCompressor,
            IdentifierGenerator ids,
            TimeProvider time,
            RuntimeEventAppender events) {
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.summaries = Objects.requireNonNull(summaries, "summaries must not be null");
        this.invoker = Objects.requireNonNull(invoker, "invoker must not be null");
        this.triggerEvaluator = Objects.requireNonNull(triggerEvaluator, "triggerEvaluator must not be null");
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.deterministicCompressor =
                Objects.requireNonNull(deterministicCompressor, "deterministicCompressor must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.time = Objects.requireNonNull(time, "time must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
    }

    /**
     * Evaluates compaction trigger before ContextBuild and executes compaction if soft limit is reached.
     */
    public void evaluateAndCompactIfNeeded(AgentRun run, int iteration, FrozenModelBinding binding) {
        if (!policy.semanticCompactionEnabled()) {
            return;
        }

        List<AgentMessage> visible =
                state.messagesAfter(run.sessionId(), MessageCursor.BEFORE_FIRST, Integer.MAX_VALUE).stream()
                        .filter(this::visibleToContext)
                        .toList();
        if (visible.size() < 2) {
            return;
        }

        List<List<AgentMessage>> groups = atomicGroups(visible);
        if (groups.size() < 2) {
            return;
        }

        Optional<ConversationSummary> previousSummary =
                summaries.latestValid(run.sessionId()).filter(s -> summaries.coversValidSource(s, s.coveredThrough()));
        List<List<AgentMessage>> activeGroups = groupsAfterSummary(visible, previousSummary);
        long currentTokens =
                previousSummary.map(ConversationSummary::estimatedTokens).orElse(0) + estimateGroups(activeGroups);

        long contextWindow = binding.configuration().model().contextWindow();
        long outputReserve = Math.min(binding.configuration().model().maxOutputTokens(), 4096);
        HeuristicTokenEstimator estimator = new HeuristicTokenEstimator();
        long toolTokens =
                binding.tools().stream().mapToLong(estimator::estimate).sum();
        long instructionTokens =
                HeuristicTokenEstimator.tokens(binding.configuration().agentInstruction());
        long fixedPrefix = toolTokens + instructionTokens;
        long otherSources = 0;

        CompactionTriggerDecision decision = triggerEvaluator.evaluate(
                contextWindow, outputReserve, fixedPrefix, otherSources, currentTokens, activeGroups.size());
        if (!decision.shouldCompact()) {
            return;
        }

        log.info(
                "Triggering semantic compaction for session {} reason: {}",
                run.sessionId().value(),
                decision.reason());
        compactSession(run, iteration, binding, visible, groups, previousSummary, decision.reason(), false);
    }

    /**
     * Forces immediate compaction upon receiving CONTEXT_TOO_LONG error from provider.
     */
    public void forceCompactOnOverflow(AgentRun run, int iteration, FrozenModelBinding binding) {
        if (!policy.semanticCompactionEnabled()) {
            return;
        }
        List<AgentMessage> visible =
                state.messagesAfter(run.sessionId(), MessageCursor.BEFORE_FIRST, Integer.MAX_VALUE).stream()
                        .filter(this::visibleToContext)
                        .toList();
        if (visible.size() < 2) {
            return;
        }
        List<List<AgentMessage>> groups = atomicGroups(visible);
        if (groups.size() < 2) {
            return;
        }
        Optional<ConversationSummary> previousSummary =
                summaries.latestValid(run.sessionId()).filter(s -> summaries.coversValidSource(s, s.coveredThrough()));

        log.warn(
                "Forcing semantic compaction on overflow for session {}",
                run.sessionId().value());
        compactSession(
                run,
                iteration,
                binding,
                visible,
                groups,
                previousSummary,
                CompactionTriggerReason.PROVIDER_CONTEXT_TOO_LONG,
                true);
    }

    private void compactSession(
            AgentRun run,
            int iteration,
            FrozenModelBinding binding,
            List<AgentMessage> visible,
            List<List<AgentMessage>> groups,
            Optional<ConversationSummary> previousSummary,
            CompactionTriggerReason reason,
            boolean overflow) {
        List<List<AgentMessage>> activeGroups = groupsAfterSummary(visible, previousSummary);
        if (activeGroups.isEmpty()) {
            return;
        }

        long contextWindow = binding.configuration().model().contextWindow();
        long outputReserve = Math.min(binding.configuration().model().maxOutputTokens(), 4096);
        int safetyMargin = Math.min(16_384, Math.max(256, (int) (contextWindow / 20)));
        long available = Math.max(1000L, contextWindow - outputReserve - safetyMargin);

        long targetTailBudget;
        if (overflow) {
            targetTailBudget = policy.minTailTokens();
        } else {
            long calculated = (available * policy.targetTailTokenPercent()) / 100L;
            targetTailBudget = Math.clamp(calculated, (long) policy.minTailTokens(), (long) policy.maxTailTokens());
        }

        int split = tailSplit(activeGroups, targetTailBudget);
        if (split <= 0) {
            return;
        }

        List<AgentMessage> sourceToCompact =
                activeGroups.subList(0, split).stream().flatMap(List::stream).toList();
        if (sourceToCompact.isEmpty()) {
            return;
        }

        // Freeze pre-compaction snapshot version before invocation
        long expectedPreviousVersion = summaries.latestVersion(run.sessionId());

        ProjectedCompactionSource projected = CompactionSourceProjector.project(sourceToCompact);
        Optional<SemanticConversationSummaryV1> prevSemantic =
                previousSummary.flatMap(ConversationSummary::semanticSummary);
        List<SemanticSummaryItem> carryForward = prevSemantic
                .map(SemanticConversationSummaryV1::mandatoryCarryForwardItems)
                .orElse(List.of());

        String systemPrompt = CompactionPromptRenderer.systemPrompt();
        String userPrompt = CompactionPromptRenderer.userPrompt(prevSemantic, carryForward, projected);

        int physicalCalls = 0;
        SemanticConversationSummaryV1 candidate = null;
        try {
            candidate = invoker.invoke(binding, run, iteration, systemPrompt, userPrompt, physicalCalls++, false);
            try {
                SemanticSummaryValidator.validate(candidate, projected, carryForward);
            } catch (SemanticSummaryValidationException validationEx) {
                if (physicalCalls < policy.maxCompactionPhysicalCalls()) {
                    log.info("Compaction validation failed: {}. Attempting repair call.", validationEx.getMessage());
                    String repairPrompt = CompactionPromptRenderer.repairPrompt(
                            candidate, validationEx.validationErrors(), prevSemantic, carryForward, projected);
                    candidate =
                            invoker.invoke(binding, run, iteration, systemPrompt, repairPrompt, physicalCalls++, true);
                    SemanticSummaryValidator.validate(candidate, projected, carryForward);
                } else {
                    throw validationEx;
                }
            }
        } catch (Exception ex) {
            log.warn("Semantic compaction failed: {}", ex.getMessage());
            if (overflow && policy.allowDeterministicDegradedFallback()) {
                log.info("Falling back to deterministic degraded compaction on overflow");
                fallbackToDeterministic(run, sourceToCompact, expectedPreviousVersion);
                return;
            }
            throw (ex instanceof RuntimeException re) ? re : new RuntimeException(ex);
        }

        SemanticConversationSummaryV1 resolved =
                resolveAliases(assignStableIds(candidate), projected.messageAliases(), projected.toolAliases());
        commitSummary(
                run,
                previousSummary,
                sourceToCompact,
                visible,
                resolved,
                reason,
                physicalCalls,
                expectedPreviousVersion);
    }

    private void fallbackToDeterministic(
            AgentRun run, List<AgentMessage> sourceToCompact, long expectedPreviousVersion) {
        var request = new io.haifa.agent.context.compression.CompressionRequest(
                new SummaryId(ids.nextValue()),
                new SummaryVersion(expectedPreviousVersion + 1),
                run.sessionId(),
                sourceToCompact,
                policy.maxSummaryFacts(),
                time.now(),
                policy.version());
        var result = deterministicCompressor.compress(request);
        try {
            summaries.compareAndSet(result.summary(), expectedPreviousVersion);
        } catch (OptimisticLockException conflict) {
            log.info("Deterministic fallback CAS conflict: {}", conflict.getMessage());
        }
    }

    private void commitSummary(
            AgentRun run,
            Optional<ConversationSummary> previousSummary,
            List<AgentMessage> sourceToCompact,
            List<AgentMessage> visible,
            SemanticConversationSummaryV1 summary,
            CompactionTriggerReason reason,
            int physicalCalls,
            long expectedPreviousVersion) {
        MessageCursor coveredFrom = previousSummary
                .map(ConversationSummary::coveredFrom)
                .orElseGet(() -> sourceToCompact.getFirst().cursor());
        MessageCursor coveredThrough = sourceToCompact.getLast().cursor();

        List<AgentMessage> allCoveredMessages = visible.stream()
                .filter(m -> m.cursor().compareTo(coveredThrough) <= 0)
                .toList();
        String sourceHash = hashMessages(allCoveredMessages);
        List<AgentMessageId> allSourceIds =
                allCoveredMessages.stream().map(AgentMessage::id).toList();

        String markdown = SemanticSummaryRenderer.renderMarkdown(summary);
        int estimatedTokens = Math.max(1, HeuristicTokenEstimator.tokens(markdown));

        List<String> facts =
                summary.goals().stream().map(SemanticSummaryItem::text).toList();
        List<String> decisions = summary.decisions().stream()
                .map(SemanticDecisionItem::statement)
                .toList();
        List<String> openItems =
                summary.nextSteps().stream().map(SemanticSummaryItem::text).toList();
        List<ToolCallId> toolOutcomeRefs = allCoveredMessages.stream()
                .flatMap(m -> m.contents().stream())
                .filter(ToolResultPart.class::isInstance)
                .map(ToolResultPart.class::cast)
                .map(ToolResultPart::toolCallId)
                .distinct()
                .toList();

        ConversationSummary domainSummary = new ConversationSummary(
                new SummaryId(ids.nextValue()),
                new SummaryVersion(expectedPreviousVersion + 1),
                run.sessionId(),
                coveredFrom,
                coveredThrough,
                allSourceIds,
                sourceHash,
                facts,
                decisions,
                openItems,
                toolOutcomeRefs,
                estimatedTokens,
                time.now(),
                policy.version(),
                "semantic-v1",
                Set.of("internal"),
                true,
                Optional.of(summary),
                CompactionQuality.SEMANTIC_VALIDATED);

        // Fail-closed check: verify no message covered by the summary has been redacted during compaction
        if (!summaries.coversValidSource(domainSummary, coveredThrough)) {
            log.warn(
                    "Semantic compaction aborted for session {}: source messages were redacted during compaction",
                    run.sessionId().value());
            return;
        }

        try {
            summaries.compareAndSet(domainSummary, expectedPreviousVersion);
            events.append(
                    run.id(),
                    "session.compacted",
                    Map.of(
                            "summaryId", domainSummary.id().value(),
                            "version", domainSummary.version().value(),
                            "reason", reason.name(),
                            "physicalCalls", physicalCalls,
                            "estimatedTokens", estimatedTokens,
                            "coveredThrough", domainSummary.coveredThrough().serialize()),
                    time.now());
            log.info(
                    "Committed semantic conversation summary {}@{} for session {}",
                    domainSummary.id().value(),
                    domainSummary.version().value(),
                    run.sessionId().value());
        } catch (OptimisticLockException conflict) {
            log.warn("CAS conflict when committing summary: {}. Re-evaluating next iteration.", conflict.getMessage());
        }
    }

    private SemanticConversationSummaryV1 resolveAliases(
            SemanticConversationSummaryV1 summary,
            Map<String, AgentMessageId> messageAliases,
            Map<String, ToolCallId> toolAliases) {
        java.util.function.Function<List<String>, List<String>> mapper = refs -> refs.stream()
                .map(ref -> {
                    if (messageAliases.containsKey(ref)) {
                        return messageAliases.get(ref).value();
                    }
                    if (toolAliases.containsKey(ref)) {
                        return toolAliases.get(ref).value();
                    }
                    return ref;
                })
                .toList();

        List<SemanticSummaryItem> goals = summary.goals().stream()
                .map(i -> new SemanticSummaryItem(
                        i.stableItemId(), i.text(), mapper.apply(i.sourceRefs()), i.confidence()))
                .toList();
        List<SemanticSummaryItem> constraints = summary.constraints().stream()
                .map(i -> new SemanticSummaryItem(
                        i.stableItemId(), i.text(), mapper.apply(i.sourceRefs()), i.confidence()))
                .toList();
        List<SemanticSummaryItem> completed = summary.progress().completed().stream()
                .map(i -> new SemanticSummaryItem(
                        i.stableItemId(), i.text(), mapper.apply(i.sourceRefs()), i.confidence()))
                .toList();
        List<SemanticSummaryItem> active = summary.progress().active().stream()
                .map(i -> new SemanticSummaryItem(
                        i.stableItemId(), i.text(), mapper.apply(i.sourceRefs()), i.confidence()))
                .toList();
        List<SemanticSummaryItem> blocked = summary.progress().blocked().stream()
                .map(i -> new SemanticSummaryItem(
                        i.stableItemId(), i.text(), mapper.apply(i.sourceRefs()), i.confidence()))
                .toList();
        List<SemanticDecisionItem> decisions = summary.decisions().stream()
                .map(i -> new SemanticDecisionItem(
                        i.stableItemId(), i.statement(), i.rationale(), i.status(), mapper.apply(i.sourceRefs())))
                .toList();
        List<SemanticSummaryItem> nextSteps = summary.nextSteps().stream()
                .map(i -> new SemanticSummaryItem(
                        i.stableItemId(), i.text(), mapper.apply(i.sourceRefs()), i.confidence()))
                .toList();
        List<SemanticSummaryItem> criticalContext = summary.criticalContext().stream()
                .map(i -> new SemanticSummaryItem(
                        i.stableItemId(), i.text(), mapper.apply(i.sourceRefs()), i.confidence()))
                .toList();
        List<SemanticSummaryItem> questions = summary.unresolvedQuestions().stream()
                .map(i -> new SemanticSummaryItem(
                        i.stableItemId(), i.text(), mapper.apply(i.sourceRefs()), i.confidence()))
                .toList();

        return new SemanticConversationSummaryV1(
                summary.schemaVersion(),
                summary.language(),
                goals,
                constraints,
                new SemanticProgress(completed, active, blocked),
                decisions,
                nextSteps,
                criticalContext,
                questions);
    }

    private SemanticConversationSummaryV1 assignStableIds(SemanticConversationSummaryV1 original) {
        int gIdx = 1, cIdx = 1, dIdx = 1, nIdx = 1, ccIdx = 1, qIdx = 1;
        int compIdx = 1, actIdx = 1, blkIdx = 1;

        List<SemanticSummaryItem> goals = new ArrayList<>();
        for (SemanticSummaryItem item : original.goals()) {
            goals.add(withStableId(item, "G-" + (gIdx++)));
        }

        List<SemanticSummaryItem> constraints = new ArrayList<>();
        for (SemanticSummaryItem item : original.constraints()) {
            constraints.add(withStableId(item, "C-" + (cIdx++)));
        }

        List<SemanticSummaryItem> completed = new ArrayList<>();
        for (SemanticSummaryItem item : original.progress().completed()) {
            completed.add(withStableId(item, "PC-" + (compIdx++)));
        }
        List<SemanticSummaryItem> active = new ArrayList<>();
        for (SemanticSummaryItem item : original.progress().active()) {
            active.add(withStableId(item, "PA-" + (actIdx++)));
        }
        List<SemanticSummaryItem> blocked = new ArrayList<>();
        for (SemanticSummaryItem item : original.progress().blocked()) {
            blocked.add(withStableId(item, "PB-" + (blkIdx++)));
        }

        List<SemanticDecisionItem> decisions = new ArrayList<>();
        for (SemanticDecisionItem item : original.decisions()) {
            decisions.add(withStableId(item, "D-" + (dIdx++)));
        }

        List<SemanticSummaryItem> nextSteps = new ArrayList<>();
        for (SemanticSummaryItem item : original.nextSteps()) {
            nextSteps.add(withStableId(item, "N-" + (nIdx++)));
        }

        List<SemanticSummaryItem> criticalContext = new ArrayList<>();
        for (SemanticSummaryItem item : original.criticalContext()) {
            criticalContext.add(withStableId(item, "CC-" + (ccIdx++)));
        }

        List<SemanticSummaryItem> questions = new ArrayList<>();
        for (SemanticSummaryItem item : original.unresolvedQuestions()) {
            questions.add(withStableId(item, "Q-" + (qIdx++)));
        }

        return new SemanticConversationSummaryV1(
                original.schemaVersion(),
                original.language(),
                goals,
                constraints,
                new SemanticProgress(completed, active, blocked),
                decisions,
                nextSteps,
                criticalContext,
                questions);
    }

    private SemanticSummaryItem withStableId(SemanticSummaryItem item, String fallbackId) {
        String id = (item.stableItemId() == null || item.stableItemId().isBlank()) ? fallbackId : item.stableItemId();
        return new SemanticSummaryItem(id, item.text(), item.sourceRefs(), item.confidence());
    }

    private SemanticDecisionItem withStableId(SemanticDecisionItem item, String fallbackId) {
        String id = (item.stableItemId() == null || item.stableItemId().isBlank()) ? fallbackId : item.stableItemId();
        return new SemanticDecisionItem(id, item.statement(), item.rationale(), item.status(), item.sourceRefs());
    }

    private int tailSplit(List<List<AgentMessage>> groups, long retainedTailBudget) {
        long retained = 0L;
        int candidateSplit = groups.size();
        for (int index = groups.size() - 1; index >= 0; index--) {
            long groupTokens = estimateGroup(groups.get(index));
            if (candidateSplit < groups.size() && (retained + groupTokens > retainedTailBudget)) {
                break;
            }
            retained += groupTokens;
            candidateSplit = index;
        }

        if (candidateSplit <= 0 || candidateSplit >= groups.size()) {
            return candidateSplit;
        }
        if (isTurnAnchor(groups.get(candidateSplit))) {
            return candidateSplit;
        }
        // Search forward for a turn anchor
        int forward = candidateSplit + 1;
        while (forward < groups.size() && !isTurnAnchor(groups.get(forward))) {
            forward++;
        }
        if (forward < groups.size()) {
            return forward;
        }
        // Search backward for a turn anchor
        int backward = candidateSplit;
        while (backward > 0 && !isTurnAnchor(groups.get(backward))) {
            backward--;
        }
        return backward;
    }

    private boolean isTurnAnchor(List<AgentMessage> group) {
        return !group.isEmpty() && group.getFirst().role() == MessageRole.USER;
    }

    private List<List<AgentMessage>> groupsAfterSummary(
            List<AgentMessage> visible, Optional<ConversationSummary> summary) {
        return summary.map(s -> atomicGroups(visible.stream()
                        .filter(m -> m.cursor().compareTo(s.coveredThrough()) > 0)
                        .toList()))
                .orElseGet(() -> atomicGroups(visible));
    }

    private List<List<AgentMessage>> atomicGroups(List<AgentMessage> source) {
        List<List<AgentMessage>> groups = new ArrayList<>();
        int index = 0;
        while (index < source.size()) {
            AgentMessage message = source.get(index);
            Set<ToolCallId> calls = new HashSet<>();
            for (var content : message.contents()) {
                if (content instanceof ToolCallPart callPart) {
                    calls.add(callPart.toolCallId());
                }
            }
            int end = index;
            if (!calls.isEmpty()) {
                Set<ToolCallId> results = new HashSet<>();
                for (int candidate = index + 1; candidate < source.size(); candidate++) {
                    for (var content : source.get(candidate).contents()) {
                        if (content instanceof ToolResultPart resPart && calls.contains(resPart.toolCallId())) {
                            results.add(resPart.toolCallId());
                            end = candidate;
                        }
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

    private long estimateGroups(List<List<AgentMessage>> groups) {
        long total = 0;
        for (List<AgentMessage> group : groups) {
            total += estimateGroup(group);
        }
        return total;
    }

    private long estimateGroup(List<AgentMessage> group) {
        long total = 0;
        for (AgentMessage message : group) {
            for (var part : message.contents()) {
                if (part instanceof io.haifa.agent.core.content.TextPart text) {
                    total += HeuristicTokenEstimator.tokens(text.text());
                }
            }
        }
        return Math.max(1L, total);
    }

    private boolean visibleToContext(AgentMessage message) {
        return message.status() == MessageStatus.COMPLETED
                && (message.visibility() == MessageVisibility.USER_VISIBLE
                        || message.visibility() == MessageVisibility.AGENT_VISIBLE);
    }

    private String hashMessages(List<AgentMessage> messages) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (AgentMessage message : messages) {
                digest.update(message.id().value().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '@');
                digest.update(Long.toString(message.sequence()).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) ';');
            }
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
