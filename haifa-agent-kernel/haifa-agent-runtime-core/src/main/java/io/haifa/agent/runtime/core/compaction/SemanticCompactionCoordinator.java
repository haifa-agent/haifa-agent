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
import io.haifa.agent.context.compression.SummarySnapshot;
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
import java.util.concurrent.atomic.AtomicInteger;
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

        SummarySnapshot snapshot = summaries.latestSnapshot(run.sessionId());
        Optional<ConversationSummary> previousSummary = snapshot.latestValid();
        long expectedPreviousVersion = snapshot.latestVersion();
        List<List<AgentMessage>> activeGroups = groupsAfterSummary(visible, previousSummary);
        long currentTokens =
                previousSummary.map(ConversationSummary::estimatedTokens).orElse(0) + estimateGroups(activeGroups);

        long contextWindow = binding.configuration().model().contextWindow();
        long outputReserve = binding.configuration().model().maxOutputTokens();
        HeuristicTokenEstimator estimator = new HeuristicTokenEstimator();
        long toolTokens =
                binding.tools().stream().mapToLong(estimator::estimate).sum();
        long instructionTokens =
                HeuristicTokenEstimator.tokens(binding.configuration().agentInstruction());
        long fixedPrefix = toolTokens + instructionTokens;
        long otherSources = 0;
        try {
            otherSources = state.memorySelection(run.id())
                    .map(sel -> (long) sel.memories().size() * 32)
                    .orElse(0L);
        } catch (Exception ignored) {
        }

        CompactionTriggerDecision decision = triggerEvaluator.evaluate(
                contextWindow, outputReserve, fixedPrefix, otherSources, currentTokens, activeGroups.size());
        if (!decision.shouldCompact()) {
            return;
        }

        log.info(
                "Triggering semantic compaction for session {} reason: {}",
                run.sessionId().value(),
                decision.reason());
        compactSession(
                run,
                iteration,
                binding,
                visible,
                groups,
                previousSummary,
                expectedPreviousVersion,
                decision.reason(),
                false);
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
        SummarySnapshot snapshot = summaries.latestSnapshot(run.sessionId());
        Optional<ConversationSummary> previousSummary = snapshot.latestValid();
        long expectedPreviousVersion = snapshot.latestVersion();

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
                expectedPreviousVersion,
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
            long expectedPreviousVersion,
            CompactionTriggerReason reason,
            boolean overflow) {
        List<List<AgentMessage>> activeGroups = groupsAfterSummary(visible, previousSummary);
        if (activeGroups.isEmpty()) {
            return;
        }

        long contextWindow = binding.configuration().model().contextWindow();
        long outputReserve = binding.configuration().model().maxOutputTokens();
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

        int boundedSplit = boundBatchSplit(activeGroups, split, available);
        if (boundedSplit <= 0) {
            return;
        }

        List<AgentMessage> sourceToCompact = activeGroups.subList(0, boundedSplit).stream()
                .flatMap(List::stream)
                .toList();
        if (sourceToCompact.isEmpty()) {
            return;
        }

        ProjectedCompactionSource projected = CompactionSourceProjector.project(sourceToCompact);
        Optional<SemanticConversationSummaryV1> prevSemantic =
                previousSummary.flatMap(ConversationSummary::semanticSummary);
        List<SemanticSummaryItem> carryForward = prevSemantic
                .map(SemanticConversationSummaryV1::mandatoryCarryForwardItems)
                .orElse(List.of());

        Set<String> historicalDurableRefs = new HashSet<>();
        previousSummary.ifPresent(prev -> {
            prev.sourceMessageIds().forEach(id -> historicalDurableRefs.add(id.value()));
            prev.toolOutcomeReferences().forEach(id -> historicalDurableRefs.add(id.value()));
        });

        String systemPrompt = CompactionPromptRenderer.systemPrompt();
        String userPrompt =
                CompactionPromptRenderer.userPromptFromConversationSummary(previousSummary, carryForward, projected);

        int physicalCalls = 0;
        SemanticConversationSummaryV1 candidate = null;
        try {
            candidate = invoker.invoke(binding, run, iteration, systemPrompt, userPrompt, physicalCalls++, false);
            try {
                SemanticSummaryValidator.validate(
                        candidate, projected, carryForward, historicalDurableRefs, prevSemantic);
            } catch (SemanticSummaryValidationException validationEx) {
                if (physicalCalls < policy.maxCompactionPhysicalCalls()) {
                    log.info("Compaction validation failed: {}. Attempting repair call.", validationEx.getMessage());
                    String repairPrompt = CompactionPromptRenderer.repairPromptFromConversationSummary(
                            candidate, validationEx.validationErrors(), previousSummary, carryForward, projected);
                    candidate =
                            invoker.invoke(binding, run, iteration, systemPrompt, repairPrompt, physicalCalls++, true);
                    SemanticSummaryValidator.validate(
                            candidate, projected, carryForward, historicalDurableRefs, prevSemantic);
                } else {
                    throw validationEx;
                }
            }
        } catch (Exception ex) {
            log.warn("Semantic compaction failed: {}", ex.getMessage());
            if (overflow && policy.allowDeterministicDegradedFallback()) {
                log.info("Falling back to deterministic degraded compaction on overflow");
                fallbackToDeterministic(run, previousSummary, sourceToCompact, expectedPreviousVersion);
                return;
            }
            throw (ex instanceof RuntimeException re) ? re : new RuntimeException(ex);
        }

        SemanticConversationSummaryV1 resolved = resolveAliases(
                assignStableIds(candidate, prevSemantic), projected.messageAliases(), projected.toolAliases());
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
            AgentRun run,
            Optional<ConversationSummary> previousSummary,
            List<AgentMessage> sourceToCompact,
            long expectedPreviousVersion) {
        var request = new io.haifa.agent.context.compression.CompressionRequest(
                new SummaryId(ids.nextValue()),
                new SummaryVersion(expectedPreviousVersion + 1),
                run.sessionId(),
                sourceToCompact,
                policy.maxSummaryFacts(),
                time.now(),
                policy.version());
        var result = deterministicCompressor.compress(request);
        ConversationSummary baseSummary = result.summary();

        ConversationSummary mergedSummary;
        if (previousSummary.isPresent()) {
            ConversationSummary prev = previousSummary.get();
            List<AgentMessageId> allSourceIds = new ArrayList<>(prev.sourceMessageIds());
            allSourceIds.addAll(sourceToCompact.stream().map(AgentMessage::id).toList());

            List<String> facts = new ArrayList<>(prev.facts());
            prev.semanticSummary().ifPresent(sem -> {
                sem.goals().forEach(g -> facts.add("Goal: " + g.text()));
                sem.constraints().forEach(c -> facts.add("Constraint: " + c.text()));
                sem.progress().completed().forEach(c -> facts.add("Completed: " + c.text()));
                sem.criticalContext().forEach(c -> facts.add("Context: " + c.text()));
            });
            facts.addAll(baseSummary.facts());

            List<String> decisions = new ArrayList<>(prev.decisions());
            prev.semanticSummary().ifPresent(sem -> {
                sem.decisions().forEach(d -> decisions.add("Decision: " + d.statement()));
            });
            decisions.addAll(baseSummary.decisions());

            List<String> openItems = new ArrayList<>(prev.openItems());
            prev.semanticSummary().ifPresent(sem -> {
                sem.unresolvedQuestions().forEach(q -> openItems.add("Question: " + q.text()));
                sem.progress().active().forEach(a -> openItems.add("Active: " + a.text()));
                sem.progress().blocked().forEach(b -> openItems.add("Blocked: " + b.text()));
            });
            openItems.addAll(baseSummary.openItems());

            List<ToolCallId> toolRefs = new ArrayList<>(prev.toolOutcomeReferences());
            toolRefs.addAll(baseSummary.toolOutcomeReferences());

            mergedSummary = new ConversationSummary(
                    baseSummary.id(),
                    baseSummary.version(),
                    baseSummary.sessionId(),
                    prev.coveredFrom(),
                    baseSummary.coveredThrough(),
                    allSourceIds,
                    baseSummary.sourceHash(),
                    facts,
                    decisions,
                    openItems,
                    toolRefs,
                    baseSummary.estimatedTokens(),
                    baseSummary.createdAt(),
                    baseSummary.policyVersion(),
                    baseSummary.compressorVersion(),
                    baseSummary.securityLabels(),
                    baseSummary.valid(),
                    Optional.empty(),
                    CompactionQuality.DETERMINISTIC_DEGRADED);
        } else {
            mergedSummary = baseSummary;
        }

        try {
            summaries.compareAndSetValid(mergedSummary, expectedPreviousVersion);
        } catch (Exception conflict) {
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
            summaries.compareAndSetValid(domainSummary, expectedPreviousVersion);
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

    private SemanticConversationSummaryV1 assignStableIds(
            SemanticConversationSummaryV1 original, Optional<SemanticConversationSummaryV1> prevSemantic) {
        Set<String> usedIds = new HashSet<>();
        AtomicInteger gIdx = new AtomicInteger(1);
        AtomicInteger cIdx = new AtomicInteger(1);
        AtomicInteger pcIdx = new AtomicInteger(1);
        AtomicInteger paIdx = new AtomicInteger(1);
        AtomicInteger pbIdx = new AtomicInteger(1);
        AtomicInteger dIdx = new AtomicInteger(1);
        AtomicInteger nIdx = new AtomicInteger(1);
        AtomicInteger ccIdx = new AtomicInteger(1);
        AtomicInteger qIdx = new AtomicInteger(1);

        List<SemanticSummaryItem> goals = new ArrayList<>();
        for (SemanticSummaryItem item : original.goals()) {
            goals.add(canonicalizeItem(item, "G-", Set.of("G-"), gIdx, usedIds));
        }

        List<SemanticSummaryItem> constraints = new ArrayList<>();
        for (SemanticSummaryItem item : original.constraints()) {
            constraints.add(canonicalizeItem(item, "C-", Set.of("C-"), cIdx, usedIds));
        }

        List<SemanticSummaryItem> completed = new ArrayList<>();
        for (SemanticSummaryItem item : original.progress().completed()) {
            completed.add(canonicalizeItem(item, "PC-", Set.of("PC-", "PA-"), pcIdx, usedIds));
        }

        List<SemanticSummaryItem> active = new ArrayList<>();
        for (SemanticSummaryItem item : original.progress().active()) {
            active.add(canonicalizeItem(item, "PA-", Set.of("PA-"), paIdx, usedIds));
        }

        List<SemanticSummaryItem> blocked = new ArrayList<>();
        for (SemanticSummaryItem item : original.progress().blocked()) {
            blocked.add(canonicalizeItem(item, "PB-", Set.of("PB-", "PA-"), pbIdx, usedIds));
        }

        List<SemanticDecisionItem> decisions = new ArrayList<>();
        for (SemanticDecisionItem item : original.decisions()) {
            decisions.add(canonicalizeDecision(item, "D-", Set.of("D-"), dIdx, usedIds));
        }

        List<SemanticSummaryItem> nextSteps = new ArrayList<>();
        for (SemanticSummaryItem item : original.nextSteps()) {
            nextSteps.add(canonicalizeItem(item, "N-", Set.of("N-"), nIdx, usedIds));
        }

        List<SemanticSummaryItem> criticalContext = new ArrayList<>();
        for (SemanticSummaryItem item : original.criticalContext()) {
            criticalContext.add(canonicalizeItem(item, "CC-", Set.of("CC-"), ccIdx, usedIds));
        }

        List<SemanticSummaryItem> questions = new ArrayList<>();
        for (SemanticSummaryItem item : original.unresolvedQuestions()) {
            questions.add(canonicalizeItem(item, "Q-", Set.of("Q-"), qIdx, usedIds));
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

    private SemanticSummaryItem canonicalizeItem(
            SemanticSummaryItem item,
            String defaultPrefix,
            Set<String> allowedPrefixes,
            AtomicInteger counter,
            Set<String> usedIds) {
        String id = item.stableItemId();
        boolean valid = id != null
                && !id.isBlank()
                && allowedPrefixes.stream().anyMatch(id::startsWith)
                && !usedIds.contains(id);
        if (!valid) {
            do {
                id = defaultPrefix + (counter.getAndIncrement());
            } while (usedIds.contains(id));
        }
        usedIds.add(id);
        return new SemanticSummaryItem(id, item.text(), item.sourceRefs(), item.confidence());
    }

    private SemanticDecisionItem canonicalizeDecision(
            SemanticDecisionItem item,
            String defaultPrefix,
            Set<String> allowedPrefixes,
            AtomicInteger counter,
            Set<String> usedIds) {
        String id = item.stableItemId();
        boolean valid = id != null
                && !id.isBlank()
                && allowedPrefixes.stream().anyMatch(id::startsWith)
                && !usedIds.contains(id);
        if (!valid) {
            do {
                id = defaultPrefix + (counter.getAndIncrement());
            } while (usedIds.contains(id));
        }
        usedIds.add(id);
        return new SemanticDecisionItem(id, item.statement(), item.rationale(), item.status(), item.sourceRefs());
    }

    private int boundBatchSplit(List<List<AgentMessage>> activeGroups, int split, long availableTokens) {
        if (split <= 0) {
            return 0;
        }
        int maxBatchGroups = 40;
        long maxBatchTokens = Math.max(8000L, (availableTokens * 3) / 4);

        long accumulatedTokens = 0L;
        int boundedIndex = 0;
        for (int i = 0; i < split; i++) {
            long gTokens = estimateGroup(activeGroups.get(i));
            if (i > 0 && (i >= maxBatchGroups || (accumulatedTokens + gTokens > maxBatchTokens))) {
                break;
            }
            accumulatedTokens += gTokens;
            boundedIndex = i + 1;
        }

        if (boundedIndex >= split) {
            return split;
        }

        int anchor = boundedIndex;
        while (anchor > 0 && !isTurnAnchor(activeGroups.get(anchor))) {
            anchor--;
        }
        if (anchor > 0) {
            return anchor;
        }
        return split;
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
                } else if (part instanceof ToolCallPart call) {
                    total += HeuristicTokenEstimator.tokens(call.toolName())
                            + HeuristicTokenEstimator.tokens(
                                    call.providerCorrelationId().value())
                            + 16;
                } else if (part instanceof ToolResultPart res) {
                    total += HeuristicTokenEstimator.tokens(res.summary())
                            + HeuristicTokenEstimator.tokens(
                                    res.providerCorrelationId().value())
                            + 16;
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
