package io.haifa.agent.runtime.core.compaction;

import static io.haifa.agent.runtime.core.context.ContextProjectionMetadata.COMPLETE_SOURCE;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.context.api.ContextBuildException;
import io.haifa.agent.context.api.ContextBuildFailure;
import io.haifa.agent.context.budget.HeuristicTokenEstimator;
import io.haifa.agent.context.compression.CarryForwardReconciler;
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
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.runtime.core.context.ActiveContextSnapshot;
import io.haifa.agent.runtime.core.context.ActiveContextSnapshots;
import io.haifa.agent.runtime.core.control.CancellationObservedException;
import io.haifa.agent.runtime.core.guard.RuntimeLimitExceededException;
import io.haifa.agent.runtime.core.guard.RuntimeQuotaExceededException;
import io.haifa.agent.runtime.core.model.FrozenModelBinding;
import io.haifa.agent.runtime.core.model.ModelMessageProjectionPlan;
import io.haifa.agent.runtime.core.model.ModelMessageProjectionPlanner;
import io.haifa.agent.runtime.core.storage.OptimisticLockException;
import io.haifa.agent.runtime.core.storage.RuntimeEventAppender;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import io.haifa.agent.runtime.core.storage.StorageException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates semantic conversation summarization and compaction before context build.
 * Manages planning, safe projection, model invocation, validation gates, repair, and CAS commit.
 */
public final class SemanticCompactionCoordinator {
    private io.haifa.agent.runtime.core.loop.RunWorkingSetSource workingSets;

    public SemanticCompactionCoordinator workingSets(io.haifa.agent.runtime.core.loop.RunWorkingSetSource value) {
        workingSets = value;
        return this;
    }

    private static final Logger log = LoggerFactory.getLogger(SemanticCompactionCoordinator.class);
    private static final int MAX_SUMMARY_TOOL_REFERENCES = 256;

    private final RuntimeStateRepository state;
    private final ConversationSummaryRepository summaries;
    private final SummaryModelInvoker invoker;
    private final CompactionTriggerEvaluator triggerEvaluator;
    private final CompressionPolicy policy;
    private final ContextCompressor deterministicCompressor;
    private final IdentifierGenerator ids;
    private final TimeProvider time;
    private final RuntimeEventAppender events;
    private final ModelMessageProjectionPlanner projectionPlanner;
    private final ActiveContextSnapshots activeContexts;
    private final CompactionFailureBackoff failureBackoff;
    private final io.haifa.agent.runtime.core.history.HistoryToolProvider history;

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
        this(
                state,
                summaries,
                invoker,
                triggerEvaluator,
                policy,
                deterministicCompressor,
                ids,
                time,
                events,
                new ModelMessageProjectionPlanner(state),
                null);
    }

    public SemanticCompactionCoordinator(
            RuntimeStateRepository state,
            ConversationSummaryRepository summaries,
            SummaryModelInvoker invoker,
            CompactionTriggerEvaluator triggerEvaluator,
            CompressionPolicy policy,
            ContextCompressor deterministicCompressor,
            IdentifierGenerator ids,
            TimeProvider time,
            RuntimeEventAppender events,
            ModelMessageProjectionPlanner projectionPlanner) {
        this(
                state,
                summaries,
                invoker,
                triggerEvaluator,
                policy,
                deterministicCompressor,
                ids,
                time,
                events,
                projectionPlanner,
                null);
    }

    public SemanticCompactionCoordinator(
            RuntimeStateRepository state,
            ConversationSummaryRepository summaries,
            SummaryModelInvoker invoker,
            CompactionTriggerEvaluator triggerEvaluator,
            CompressionPolicy policy,
            ContextCompressor deterministicCompressor,
            IdentifierGenerator ids,
            TimeProvider time,
            RuntimeEventAppender events,
            ModelMessageProjectionPlanner projectionPlanner,
            ActiveContextSnapshots activeContexts) {
        this(
                state,
                summaries,
                invoker,
                triggerEvaluator,
                policy,
                deterministicCompressor,
                ids,
                time,
                events,
                projectionPlanner,
                activeContexts,
                null);
    }

    public SemanticCompactionCoordinator(
            RuntimeStateRepository state,
            ConversationSummaryRepository summaries,
            SummaryModelInvoker invoker,
            CompactionTriggerEvaluator triggerEvaluator,
            CompressionPolicy policy,
            ContextCompressor deterministicCompressor,
            IdentifierGenerator ids,
            TimeProvider time,
            RuntimeEventAppender events,
            ModelMessageProjectionPlanner projectionPlanner,
            ActiveContextSnapshots activeContexts,
            io.haifa.agent.runtime.core.history.HistoryToolProvider history) {
        this.history = history;
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
        this.projectionPlanner = Objects.requireNonNull(projectionPlanner, "projectionPlanner must not be null");
        this.activeContexts = activeContexts;
        this.failureBackoff = new CompactionFailureBackoff(time, 60_000L, 1024);
    }

    /**
     * Evaluates compaction trigger before ContextBuild and executes compaction if soft limit is reached.
     */
    public CompactionEvaluationOutcome evaluateAndCompactIfNeeded(
            AgentRun run, int iteration, FrozenModelBinding binding) {
        return evaluateAndCompactIfNeeded(run, iteration, binding, 0);
    }

    public boolean plansAfterSelection(AgentRun run) {
        return workingSets != null && workingSets.unified(run);
    }

    public CompactionEvaluationOutcome evaluateSelected(
            AgentRun run, int iteration, FrozenModelBinding binding, CompactionTriggerDecision selected) {
        return evaluateAndCompactIfNeeded(run, iteration, binding, 0, selected);
    }

    public void requireSelectedRetention(long tokens, CompactionTriggerDecision decision, long declaredRetention) {
        if (decision.reason() == CompactionTriggerReason.ACTIVE_HISTORY_BUDGET) {
            var budget = decision.budgetBreakdown();
            long allowance =
                    Math.max(retentionLimit(budget), Math.min(budget.availableSessionTokens(), declaredRetention));
            if (tokens > allowance)
                throw new ContextBuildException(
                        ContextBuildFailure.REQUIRED_CONTEXT_TOO_LARGE,
                        "Selected history exceeds its frozen retention allowance");
        }
    }

    private CompactionEvaluationOutcome evaluateAndCompactIfNeeded(
            AgentRun run, int iteration, FrozenModelBinding binding, int boundedPageCount) {
        return evaluateAndCompactIfNeeded(run, iteration, binding, boundedPageCount, null);
    }

    private CompactionEvaluationOutcome evaluateAndCompactIfNeeded(
            AgentRun run,
            int iteration,
            FrozenModelBinding binding,
            int boundedPageCount,
            CompactionTriggerDecision selected) {
        if (history != null) history.requireCurrentScope(run);
        long startNanos = System.nanoTime();
        if (!policy.semanticCompactionEnabled()) {
            return CompactionEvaluationOutcome.NONE;
        }

        if (selected != null && !selected.shouldCompact())
            return CompactionEvaluationOutcome.untriggered(
                    selected.budgetBreakdown().currentSessionTokens(), elapsedMillis(startNanos));
        if (selected == null && workingSets != null && workingSets.enabled(run)) {
            var selectedEstimate = workingSets.selectedEstimate(
                    run,
                    Math.max(
                            1L,
                            binding.configuration().model().contextWindow()
                                    - binding.configuration().model().maxOutputTokens()));
            if (selectedEstimate.isPresent()) {
                var estimator = new HeuristicTokenEstimator();
                long fixed =
                        HeuristicTokenEstimator.tokens(binding.configuration().agentInstruction())
                                + binding.tools().stream()
                                        .mapToLong(estimator::estimate)
                                        .sum();
                var selectedDecision = triggerEvaluator.evaluate(
                        binding.configuration().model().contextWindow(),
                        binding.configuration().model().maxOutputTokens(),
                        fixed,
                        0,
                        selectedEstimate.getAsLong(),
                        2);
                if (!selectedDecision.shouldCompact())
                    return CompactionEvaluationOutcome.untriggered(
                            selectedEstimate.getAsLong(), elapsedMillis(startNanos));
            }
            // Excess required evidence follows the existing bounded contiguous recovery path.
        }
        ActiveContextSnapshot activeSnapshot = activeContexts == null
                ? null
                : activeContexts.currentForCompaction(run.sessionId()).snapshot();
        if (activeSnapshot != null && activeSnapshot.hasMoreHistory()) {
            if (boundedPageCount >= 128) {
                throw new IllegalStateException("active context cold-start compaction exceeded 128 bounded pages");
            }
            CompactionEvaluationOutcome pageOutcome =
                    compactTruncatedWindow(run, iteration, binding, activeSnapshot, startNanos);
            CompactionEvaluationOutcome finalOutcome =
                    evaluateAndCompactIfNeeded(run, iteration, binding, boundedPageCount + 1, selected);
            return finalOutcome.triggerReason() == null ? pageOutcome : finalOutcome;
        }
        List<AgentMessage> visible = activeSnapshot == null
                ? state.messagesAfter(run.sessionId(), MessageCursor.BEFORE_FIRST, Integer.MAX_VALUE).stream()
                        .filter(this::visibleToContext)
                        .toList()
                : activeSnapshot.activeMessages();
        List<List<AgentMessage>> groups =
                activeSnapshot == null ? atomicGroups(visible) : activeSnapshot.atomicGroups();
        SummarySnapshot summarySnapshot = activeSnapshot == null ? summaries.latestSnapshot(run.sessionId()) : null;
        Optional<ConversationSummary> previousSummary =
                activeSnapshot == null ? summarySnapshot.latestValid() : activeSnapshot.summary();
        long expectedPreviousVersion =
                activeSnapshot == null ? summarySnapshot.latestVersion() : activeSnapshot.summaryVersion();
        List<List<AgentMessage>> activeGroups =
                activeSnapshot == null ? groupsAfterSummary(visible, previousSummary) : groups;

        Function<ToolCallId, ToolCall> resolver = toolCallResolver(run, activeSnapshot, visible);

        long currentTokens =
                previousSummary.map(ConversationSummary::estimatedTokens).orElse(0)
                        + estimateGroups(activeGroups, resolver);

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

        CompactionTriggerDecision decision = selected != null
                ? selected
                : triggerEvaluator.evaluate(
                        contextWindow, outputReserve, fixedPrefix, otherSources, currentTokens, activeGroups.size());
        if (!decision.shouldCompact()) {
            ContextBudgetBreakdown budget = decision.budgetBreakdown();
            boolean hasActiveTarget =
                    policy.activeHistoryBudgetTokens().isPresent() || policy.activeHistoryBudgetPercent() > 0;
            if (activeGroups.size() < 2
                    && hasActiveTarget
                    && budget.softLimitTokens()
                            < Math.max(0L, budget.availableSessionTokens() - budget.triggerHeadroomTokens())) {
                // Too few source turns cannot authorize retaining an oversized prior summary in a new Run.
                invoker.checkControl(run);
                List<AgentMessage> active =
                        activeGroups.stream().flatMap(List::stream).toList();
                long projected = previousSummary
                                .map(ConversationSummary::estimatedTokens)
                                .orElse(0)
                        + projectionPlanner.plan(active, resolver).projectedActiveTokens();
                requireWithinRetention(projected, budget);
            }
            return CompactionEvaluationOutcome.untriggered(currentTokens, elapsedMillis(startNanos));
        }

        long softLimit = decision.budgetBreakdown().softLimitTokens();
        long prevTokens =
                previousSummary.map(ConversationSummary::estimatedTokens).orElse(0);
        List<AgentMessage> activeMessages =
                activeGroups.stream().flatMap(List::stream).toList();
        ModelMessageProjectionPlan projectionPlan = selected == null
                ? projectionPlanner.plan(activeMessages, resolver)
                : projectionPlanner.plan(
                        activeMessages,
                        resolver,
                        activeMessages.stream()
                                .flatMap(message -> message.contents().stream())
                                .filter(io.haifa.agent.core.content.ToolCallPart.class::isInstance)
                                .map(io.haifa.agent.core.content.ToolCallPart.class::cast)
                                .map(io.haifa.agent.core.content.ToolCallPart::toolCallId)
                                .collect(java.util.stream.Collectors.toSet()));
        long beforeTokens = prevTokens + projectionPlan.rawActiveTokens();
        long afterTokens = prevTokens + projectionPlan.projectedActiveTokens();
        if (selected == null && bypassCompactionRecommended(softLimit, projectionPlan, afterTokens)) {
            long elapsed = elapsedMillis(startNanos);
            log.info(
                    "Bypassing semantic compaction for session {} due to projection pruning: activeBudget={}, saved {} tokens, projected {} < softLimit {}",
                    run.sessionId().value(),
                    softLimit,
                    projectionPlan.tokensSavedByPruning(),
                    afterTokens,
                    softLimit);
            Map<String, Object> eventData = new LinkedHashMap<>();
            eventData.put("semanticCompactionReason", decision.reason().name());
            eventData.put("tier1PruningBypassedSummary", true);
            eventData.put("projectedActiveHistoryTokensBefore", beforeTokens);
            eventData.put("projectedActiveHistoryTokensAfter", afterTokens);
            eventData.put("omittedToolPayloadTokens", projectionPlan.tokensSavedByPruning());
            eventData.put(
                    "omittedToolResultCount", projectionPlan.prunedToolResults().size());
            eventData.put("compactionSummaryCacheHitRate", 0.0);
            eventData.put("previousSummaryInputShare", 0.0);
            eventData.put("compactionEvaluationElapsedMillis", elapsed);
            eventData.put("rawActiveTokens", beforeTokens);
            eventData.put("projectedActiveTokens", afterTokens);
            eventData.put("tokensSaved", projectionPlan.tokensSavedByPruning());
            eventData.put(
                    "prunedToolResultsCount", projectionPlan.prunedToolResults().size());
            events.append(run.id(), "session.compaction-bypassed", eventData, time.now());
            return CompactionEvaluationOutcome.bypassed(
                    decision.reason(),
                    beforeTokens,
                    afterTokens,
                    projectionPlan.tokensSavedByPruning(),
                    projectionPlan.prunedToolResults().size(),
                    elapsed);
        }

        boolean targetPressure = decision.reason() == CompactionTriggerReason.ACTIVE_HISTORY_BUDGET;
        if (targetPressure
                && failureBackoff.contains(
                        CompactionFailureBackoff.Scope.of(run, binding, policy, expectedPreviousVersion),
                        activeMessages)) {
            invoker.checkControl(run);
            requireWithinRetention(
                    selected == null ? afterTokens : selected.budgetBreakdown().currentSessionTokens(),
                    decision.budgetBreakdown());
            return CompactionEvaluationOutcome.untriggered(afterTokens, elapsedMillis(startNanos));
        }

        log.info(
                "Triggering semantic compaction for session {} reason: {}, activeBudget={}",
                run.sessionId().value(),
                decision.reason(),
                softLimit);
        CompactionEvaluationOutcome outcome = compactSession(
                run,
                iteration,
                binding,
                visible,
                groups,
                resolver,
                previousSummary,
                expectedPreviousVersion,
                decision.reason(),
                false,
                projectionPlan,
                currentTokens,
                decision.budgetBreakdown().resolvedRetainedTailTokens(),
                startNanos,
                selected != null || plansAfterSelection(run));
        if (targetPressure && selected == null) {
            Optional<ConversationSummary> latestSelected = summaries.latestValid(run.sessionId());
            List<AgentMessage> remaining = groupsAfterSummary(visible, latestSelected).stream()
                    .flatMap(List::stream)
                    .toList();
            long selectedTokens =
                    latestSelected.map(ConversationSummary::estimatedTokens).orElse(0)
                            + projectionPlanner.plan(remaining, resolver).projectedActiveTokens();
            requireWithinRetention(selectedTokens, decision.budgetBreakdown());
        }
        return outcome;
    }

    static long retentionLimit(ContextBudgetBreakdown budget) {
        long target = budget.softLimitTokens();
        long extra = target / 5L;
        long limit = target > Long.MAX_VALUE - extra ? Long.MAX_VALUE : target + extra;
        return Math.min(budget.availableSessionTokens(), limit);
    }

    static void requireWithinRetention(long historyTokens, ContextBudgetBreakdown budget) {
        if (historyTokens > retentionLimit(budget)) {
            throw new ContextBuildException(
                    ContextBuildFailure.REQUIRED_CONTEXT_TOO_LARGE,
                    "Active history exceeds its bounded recovery allowance");
        }
    }

    private CompactionEvaluationOutcome compactTruncatedWindow(
            AgentRun run, int iteration, FrozenModelBinding binding, ActiveContextSnapshot snapshot, long startNanos) {
        List<AgentMessage> visible = snapshot.activeMessages();
        List<List<AgentMessage>> groups = snapshot.atomicGroups();
        if (groups.size() < 2) {
            throw new IllegalStateException("oversized active history has no safe compaction boundary");
        }
        Optional<ConversationSummary> previousSummary = snapshot.summary();
        long expectedPreviousVersion = snapshot.summaryVersion();
        Function<ToolCallId, ToolCall> resolver = toolCallResolver(run, snapshot, visible);
        long currentTokens =
                previousSummary.map(ConversationSummary::estimatedTokens).orElse(0) + estimateGroups(groups, resolver);
        ModelMessageProjectionPlan projectionPlan = projectionPlanner.plan(snapshot.selectedMessages(), resolver);
        CompactionEvaluationOutcome outcome = compactSession(
                run,
                iteration,
                binding,
                visible,
                groups,
                resolver,
                previousSummary,
                expectedPreviousVersion,
                CompactionTriggerReason.ACTIVE_WINDOW_LIMIT,
                true,
                projectionPlan,
                currentTokens,
                policy.minTailTokens(),
                startNanos,
                plansAfterSelection(run));
        if (summaries.latestVersion(run.sessionId()) <= expectedPreviousVersion) {
            throw new IllegalStateException("bounded cold-start compaction made no summary progress");
        }
        return outcome;
    }

    /**
     * Forces immediate compaction upon receiving CONTEXT_TOO_LONG error from provider.
     */
    public CompactionEvaluationOutcome forceCompactOnOverflow(AgentRun run, int iteration, FrozenModelBinding binding) {
        return forceCompactOnOverflow(run, iteration, binding, 0);
    }

    private CompactionEvaluationOutcome forceCompactOnOverflow(
            AgentRun run, int iteration, FrozenModelBinding binding, int boundedPageCount) {
        if (history != null) history.requireCurrentScope(run);
        long startNanos = System.nanoTime();
        if (!policy.semanticCompactionEnabled()) {
            return CompactionEvaluationOutcome.NONE;
        }
        ActiveContextSnapshot activeSnapshot = activeContexts == null
                ? null
                : activeContexts.currentForCompaction(run.sessionId()).snapshot();
        if (activeSnapshot != null && activeSnapshot.hasMoreHistory()) {
            if (boundedPageCount >= 128) {
                throw new IllegalStateException("forced active-context compaction exceeded 128 bounded pages");
            }
            CompactionEvaluationOutcome pageOutcome =
                    compactTruncatedWindow(run, iteration, binding, activeSnapshot, startNanos);
            CompactionEvaluationOutcome finalOutcome =
                    forceCompactOnOverflow(run, iteration, binding, boundedPageCount + 1);
            return finalOutcome.triggerReason() == null ? pageOutcome : finalOutcome;
        }
        List<AgentMessage> visible = activeSnapshot == null
                ? state.messagesAfter(run.sessionId(), MessageCursor.BEFORE_FIRST, Integer.MAX_VALUE).stream()
                        .filter(this::visibleToContext)
                        .toList()
                : activeSnapshot.activeMessages();
        if (visible.size() < 2) {
            return CompactionEvaluationOutcome.untriggered(0L, elapsedMillis(startNanos));
        }
        List<List<AgentMessage>> groups =
                activeSnapshot == null ? atomicGroups(visible) : activeSnapshot.atomicGroups();
        if (groups.size() < 2) {
            return CompactionEvaluationOutcome.untriggered(0L, elapsedMillis(startNanos));
        }
        SummarySnapshot summarySnapshot = activeSnapshot == null ? summaries.latestSnapshot(run.sessionId()) : null;
        Optional<ConversationSummary> previousSummary =
                activeSnapshot == null ? summarySnapshot.latestValid() : activeSnapshot.summary();
        long expectedPreviousVersion =
                activeSnapshot == null ? summarySnapshot.latestVersion() : activeSnapshot.summaryVersion();

        List<List<AgentMessage>> activeGroups =
                activeSnapshot == null ? groupsAfterSummary(visible, previousSummary) : groups;
        if (activeGroups.size() < 2) {
            return CompactionEvaluationOutcome.untriggered(0L, elapsedMillis(startNanos));
        }

        Function<ToolCallId, ToolCall> resolver = toolCallResolver(run, activeSnapshot, visible);

        long currentTokens =
                previousSummary.map(ConversationSummary::estimatedTokens).orElse(0)
                        + estimateGroups(activeGroups, resolver);

        // Tier 1 projection pruning is executed before Tier 2 compaction
        List<AgentMessage> activeMessages =
                activeGroups.stream().flatMap(List::stream).toList();
        ModelMessageProjectionPlan projectionPlan = projectionPlanner.plan(activeMessages, resolver);

        log.info(
                "Forcing semantic compaction due to context overflow for session {}",
                run.sessionId().value());
        return compactSession(
                run,
                iteration,
                binding,
                visible,
                groups,
                resolver,
                previousSummary,
                expectedPreviousVersion,
                CompactionTriggerReason.PROVIDER_CONTEXT_TOO_LONG,
                true,
                projectionPlan,
                currentTokens,
                policy.minTailTokens(),
                startNanos,
                plansAfterSelection(run));
    }

    private CompactionEvaluationOutcome compactSession(
            AgentRun run,
            int iteration,
            FrozenModelBinding binding,
            List<AgentMessage> visible,
            List<List<AgentMessage>> groups,
            Function<ToolCallId, ToolCall> resolver,
            Optional<ConversationSummary> previousSummary,
            long expectedPreviousVersion,
            CompactionTriggerReason reason,
            boolean overflow,
            ModelMessageProjectionPlan projectionPlan,
            long initialEstimatedTokens,
            long targetTailBudget,
            long startNanos,
            boolean completeSource) {
        List<List<AgentMessage>> activeGroups = groupsAfterSummary(visible, previousSummary);
        if (activeGroups.isEmpty()) {
            return CompactionEvaluationOutcome.untriggered(initialEstimatedTokens, elapsedMillis(startNanos));
        }

        long available = SummaryModelInvoker.inputBudget(binding);

        // Retained-tail selection must measure the authoritative payloads the tail will actually send, not the short
        // reference/summary metadata. Source-summary prompt sizing (nextBatchEnd) stays on its own projected estimate.
        int split = tailSplit(activeGroups, targetTailBudget, resolver, completeSource);
        if (split <= 0) {
            return CompactionEvaluationOutcome.untriggered(initialEstimatedTokens, elapsedMillis(startNanos));
        }

        List<AgentMessage> candidateSource =
                activeGroups.subList(0, split).stream().flatMap(List::stream).toList();
        if (candidateSource.isEmpty()) {
            return CompactionEvaluationOutcome.untriggered(initialEstimatedTokens, elapsedMillis(startNanos));
        }

        List<AgentMessage> allActiveMessages =
                activeGroups.stream().flatMap(List::stream).toList();
        int validatedCutoff = CutoffPointValidator.validateCutoff(allActiveMessages, candidateSource.size());
        if (validatedCutoff <= 0) {
            log.info("Cutoff point validator rolled cutoff back to 0; skipping compaction for this cycle");
            return CompactionEvaluationOutcome.untriggered(initialEstimatedTokens, elapsedMillis(startNanos));
        }

        int safeSplit = 0;
        int accumulatedCount = 0;
        for (int i = 0; i < activeGroups.size(); i++) {
            int groupSize = activeGroups.get(i).size();
            if (accumulatedCount + groupSize <= validatedCutoff) {
                accumulatedCount += groupSize;
                safeSplit = i + 1;
            } else {
                break;
            }
        }
        if (safeSplit <= 0) {
            log.info("Cutoff point validator safe group split is 0; skipping compaction for this cycle");
            return CompactionEvaluationOutcome.untriggered(initialEstimatedTokens, elapsedMillis(startNanos));
        }

        List<AgentMessage> sourceToCompact = activeGroups.subList(0, safeSplit).stream()
                .flatMap(List::stream)
                .toList();

        var complete = new HashMap<AgentMessageId, AgentMessage>();
        Function<List<AgentMessage>, ProjectedCompactionSource> projector = completeSource
                ? messages -> CompactionSourceProjector.projectComplete(messages.stream()
                        .map(message -> complete.get(message.id()))
                        .toList())
                : CompactionSourceProjector::project;

        long prevSummaryTokens =
                previousSummary.map(ConversationSummary::estimatedTokens).orElse(0);
        long sourceEstimatedTokens = estimateGroups(activeGroups.subList(0, safeSplit));
        double cacheHitRate = (prevSummaryTokens + sourceEstimatedTokens > 0)
                ? (double) prevSummaryTokens / (prevSummaryTokens + sourceEstimatedTokens)
                : 0.0;

        Optional<SemanticConversationSummaryV1> workingSemantic =
                previousSummary.flatMap(ConversationSummary::semanticSummary);
        Set<String> historicalDurableRefs = new HashSet<>();
        previousSummary.ifPresent(prev -> {
            prev.sourceMessageIds().forEach(id -> historicalDurableRefs.add(id.value()));
            prev.toolOutcomeReferences().forEach(id -> historicalDurableRefs.add(id.value()));
            prev.semanticSummary().ifPresent(summary -> addSemanticSourceRefs(summary, historicalDurableRefs));
        });

        String evaluationId = ids.nextValue();
        long initialModelCalls = run.usage().modelCalls();
        String systemPrompt = CompactionPromptRenderer.systemPrompt();
        int physicalCalls = 0;
        int batchStart = 0;
        boolean sourcePrepared = false;
        try {
            invoker.remainingRunCalls(run);
            if (completeSource) {
                var expectedSource = visible.stream()
                        .filter(message -> message.cursor()
                                                .compareTo(previousSummary
                                                        .map(ConversationSummary::coveredThrough)
                                                        .orElse(MessageCursor.BEFORE_FIRST))
                                        > 0
                                && message.cursor()
                                                .compareTo(sourceToCompact
                                                        .getLast()
                                                        .cursor())
                                        <= 0)
                        .toList();
                if (!expectedSource.equals(sourceToCompact))
                    throw new ContextBuildException(
                            ContextBuildFailure.UNSUPPORTED_CONTEXT_CONTENT,
                            "complete compaction cannot skip an unfinished protocol group");
            }
            if (completeSource)
                for (var message : sourceToCompact)
                    complete.put(message.id(), hydrateCompactionSource(run, message, resolver));
            sourcePrepared = true;
            while (batchStart < safeSplit) {
                int remaining = (int)
                        Math.min(policy.maxCompactionPhysicalCalls() - physicalCalls, invoker.remainingRunCalls(run));
                SummaryModelInvoker.requireStructuredOutput(binding);
                if (minimumBatchCalls(
                                activeGroups, batchStart, safeSplit, available, remaining, projector, completeSource)
                        > remaining) {
                    throw new CompactionBudgetExceededException();
                }
                int batchEnd = nextBatchEnd(activeGroups, batchStart, safeSplit, available, projector, completeSource);
                List<SemanticSummaryItem> carryForward = workingSemantic
                        .map(SemanticConversationSummaryV1::mandatoryCarryForwardItems)
                        .orElse(List.of());
                List<AgentMessage> batchSource;
                ProjectedCompactionSource projected;
                String userPrompt;
                while (true) {
                    batchSource = activeGroups.subList(batchStart, batchEnd).stream()
                            .flatMap(List::stream)
                            .toList();
                    projected = projector.apply(batchSource);
                    userPrompt = batchStart == 0
                            ? CompactionPromptRenderer.userPromptFromConversationSummary(
                                    previousSummary, carryForward, projected)
                            : CompactionPromptRenderer.userPrompt(workingSemantic, carryForward, projected);
                    if (SummaryModelInvoker.estimatedInput(systemPrompt, userPrompt) <= available) break;
                    batchEnd = smallerBatchEnd(activeGroups, batchStart, batchEnd, completeSource);
                }
                int mandatoryLaterCalls = minimumBatchCalls(
                        activeGroups, batchEnd, safeSplit, available, remaining, projector, completeSource);
                if (mandatoryLaterCalls + 1 > remaining) throw new CompactionBudgetExceededException();
                SemanticConversationSummaryV1 candidate;
                boolean correcting = false;
                while (true) {
                    ensureSourcesCurrent(sourceToCompact);
                    try {
                        candidate = prepareCandidate(
                                invoker.invoke(
                                        binding, run, iteration, systemPrompt, userPrompt, physicalCalls++, correcting),
                                carryForward,
                                projected);
                        SemanticSummaryValidator.validate(
                                candidate, projected, carryForward, historicalDurableRefs, workingSemantic);
                        break;
                    } catch (SemanticSummaryValidationException invalid) {
                        if (correcting
                                || invalid.code() == SemanticSummaryValidationException.Code.UNEXPECTED_FINISH_REASON)
                            throw invalid;
                        int left = (int) Math.min(
                                policy.maxCompactionPhysicalCalls() - physicalCalls, invoker.remainingRunCalls(run));
                        if (left <= mandatoryLaterCalls) throw invalid;
                        // Only a stable owning-rule code is fed back, never exception text or the invalid candidate.
                        userPrompt = "The previous summary output failed validation ["
                                + invalid.code().name() + "]"
                                + ". Regenerate valid JSON from the authorized source; preserve all required evidence.\n\n"
                                + userPrompt;
                        SummaryModelInvoker.requireInputFits(binding, systemPrompt, userPrompt);
                        correcting = true;
                    }
                }
                // Keep the complete, validated semantic state only in memory until every batch succeeds.
                workingSemantic =
                        Optional.of(resolveAliases(candidate, projected.messageAliases(), projected.toolAliases()));
                batchSource.forEach(
                        message -> historicalDurableRefs.add(message.id().value()));
                projected.toolAliases().values().forEach(id -> historicalDurableRefs.add(id.value()));
                batchStart = batchEnd;
            }
            if (completeSource) {
                ensureSourcesCurrent(sourceToCompact);
                var fresh = rereadSourceToolCalls(run, sourceToCompact);
                for (var message : sourceToCompact)
                    if (!complete.get(message.id()).equals(hydrateCompactionSource(run, message, fresh)))
                        throw new OptimisticLockException("complete compaction source changed");
            }

        } catch (CancellationObservedException
                | RuntimeLimitExceededException
                | RuntimeQuotaExceededException
                | StorageException
                | OptimisticLockException stopped) {
            throw stopped;
        } catch (Exception ex) {
            // A model may fail concurrently with a stop or deadline. Hard controls take precedence.
            invoker.checkControl(run);
            String category = failureCategory(ex);
            String errorCode = validationErrorCode(ex);
            long elapsed = elapsedMillis(startNanos);
            boolean activeBudget = reason == CompactionTriggerReason.ACTIVE_HISTORY_BUDGET;
            boolean sourceRefusal = completeSource && ex instanceof ContextBuildException;
            boolean degraded = !sourceRefusal
                    && (activeBudget
                            || ex instanceof CompactionModelCapabilityException
                            || policy.allowDeterministicDegradedFallback()
                            || overflow);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("evaluationId", evaluationId);
            data.put("reason", reason.name());
            data.put("semanticCompactionReason", reason.name());
            data.put("tier1PruningBypassedSummary", false);
            data.put("projectedActiveHistoryTokensBefore", initialEstimatedTokens);
            data.put("projectedActiveHistoryTokensAfter", initialEstimatedTokens);
            data.put("omittedToolPayloadTokens", 0L);
            data.put("omittedToolResultCount", 0);
            data.put("compactionSummaryCacheHitRate", cacheHitRate);
            data.put("previousSummaryInputShare", cacheHitRate);
            data.put("compactionEvaluationElapsedMillis", elapsed);
            data.put("failureCategory", category);
            data.put("validationErrorCode", errorCode);
            data.put(
                    "failureStage",
                    sourceRefusal ? (sourcePrepared ? "SOURCE_VALIDATION" : "SOURCE_PREPARATION") : failureStage(ex));
            data.put("failureCode", failureCode(ex));
            data.put("physicalCalls", Math.toIntExact(run.usage().modelCalls() - initialModelCalls));
            data.put("degraded", degraded);
            events.append(run.id(), "session.compaction-failed", data, time.now());
            if (sourceRefusal) {
                // An unreadable complete range cannot authorize deterministic coverage or a blind retry.
                recoveryOutcome(
                        run,
                        evaluationId,
                        reason,
                        new SummaryCommit("NO_REDUCTION", previousSummary, false),
                        visible,
                        resolver,
                        initialEstimatedTokens,
                        cacheHitRate,
                        startNanos);
                throw (ContextBuildException) ex;
            }
            if (activeBudget
                    || (completeSource && ex instanceof CompactionBudgetExceededException)
                    || (ex instanceof CompactionBudgetExceededException
                            && !policy.allowDeterministicDegradedFallback())) {
                if (activeBudget && recoverableFailure(ex) && !(ex instanceof CompactionBudgetExceededException)) {
                    failureBackoff.record(
                            CompactionFailureBackoff.Scope.of(run, binding, policy, expectedPreviousVersion),
                            sourceToCompact);
                }
                CompactionEvaluationOutcome recovery = recoveryOutcome(
                        run,
                        evaluationId,
                        reason,
                        new SummaryCommit("NO_REDUCTION", Optional.empty(), false),
                        visible,
                        resolver,
                        initialEstimatedTokens,
                        cacheHitRate,
                        startNanos);
                if (activeBudget && !recoverableFailure(ex)) {
                    if (ex instanceof io.haifa.agent.model.api.ModelInvocationException model) throw model;
                    if (ex instanceof CompactionModelCapabilityException capability) throw capability;
                    throw new IllegalStateException("Compaction failed without an eligible bounded recovery");
                }
                return recovery;
            }
            if (degraded) {
                SummaryCommit commit = fallbackToDeterministic(
                        run, previousSummary, sourceToCompact, visible, expectedPreviousVersion, evaluationId);
                return recoveryOutcome(
                        run,
                        evaluationId,
                        reason,
                        commit,
                        visible,
                        resolver,
                        initialEstimatedTokens,
                        cacheHitRate,
                        startNanos);
            }
            recoveryOutcome(
                    run,
                    evaluationId,
                    reason,
                    new SummaryCommit("NO_REDUCTION", Optional.empty(), false),
                    visible,
                    resolver,
                    initialEstimatedTokens,
                    cacheHitRate,
                    startNanos);
            throw (ex instanceof RuntimeException re) ? re : new RuntimeException(ex);
        }

        List<List<AgentMessage>> tailGroups = activeGroups.subList(safeSplit, activeGroups.size());
        long retainedTailTokens = estimateGroups(tailGroups, resolver);

        return commitSummary(
                run,
                evaluationId,
                resolver,
                previousSummary,
                sourceToCompact,
                visible,
                workingSemantic.orElseThrow(),
                reason,
                physicalCalls,
                expectedPreviousVersion,
                projectionPlan,
                initialEstimatedTokens,
                retainedTailTokens,
                cacheHitRate,
                startNanos,
                completeSource);
    }

    private static boolean recoverableFailure(Exception failure) {
        if (failure instanceof SemanticSummaryValidationException) return true;
        if (failure instanceof io.haifa.agent.model.api.ModelInvocationException model) {
            return switch (model.category()) {
                case RATE_LIMITED,
                        TIMEOUT,
                        PROVIDER_UNAVAILABLE,
                        SERVER_ERROR,
                        TRANSPORT_ERROR,
                        EMPTY_RESPONSE,
                        PARTIAL_RESPONSE,
                        MALFORMED_RESPONSE -> true;
                default -> false;
            };
        }
        return failure instanceof CompactionBudgetExceededException;
    }

    private static String failureCategory(Exception exception) {
        if (exception instanceof ContextBuildException) return "SOURCE";
        if (exception instanceof CompactionBudgetExceededException) return "PLAN_BUDGET";
        if (exception instanceof CompactionModelCapabilityException) return "MODEL_CAPABILITY";
        return exception instanceof SemanticSummaryValidationException ? "VALIDATION" : "MODEL_OR_RUNTIME";
    }

    private static String validationErrorCode(Exception exception) {
        if (exception instanceof CompactionModelCapabilityException capability) {
            return capability.missingCapability() + "_UNSUPPORTED";
        }
        return exception instanceof SemanticSummaryValidationException ? "VALIDATION_REJECTED" : "NONE";
    }

    private static String failureCode(Exception exception) {
        if (exception instanceof ContextBuildException source)
            return source.failure().name();
        if (exception instanceof SemanticSummaryValidationException validation)
            return validation.code().name();
        if (exception instanceof CompactionBudgetExceededException budget)
            return budget.code().name();
        if (exception instanceof CompactionModelCapabilityException capability) {
            return capability.missingCapability() + "_UNSUPPORTED";
        }
        if (exception instanceof io.haifa.agent.model.api.ModelInvocationException model) {
            return model.category().name();
        }
        return "UNKNOWN";
    }

    private static String failureStage(Exception exception) {
        if (exception instanceof CompactionBudgetExceededException) return "PLANNING";
        if (exception instanceof SemanticSummaryValidationException validation) {
            return switch (validation.code()) {
                case JSON_PARSE_ERROR -> "PARSING";
                case SCHEMA_MAPPING_ERROR -> "MAPPING";
                case UNEXPECTED_FINISH_REASON, EMPTY_STRUCTURED_OUTPUT -> "GENERATION";
                default -> "VALIDATION";
            };
        }
        return "GENERATION";
    }

    private SummaryCommit fallbackToDeterministic(
            AgentRun run,
            Optional<ConversationSummary> previousSummary,
            List<AgentMessage> sourceToCompact,
            List<AgentMessage> visible,
            long expectedPreviousVersion,
            String evaluationId) {
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

        MessageCursor coveredThrough = sourceToCompact.getLast().cursor();
        MessageCursor coveredFrom = previousSummary
                .map(ConversationSummary::coveredFrom)
                .orElseGet(() -> sourceToCompact.getFirst().cursor());
        List<AgentMessage> allCoveredMessages = visible.stream()
                .filter(message -> message.cursor().compareTo(coveredFrom) >= 0)
                .filter(message -> message.cursor().compareTo(coveredThrough) <= 0)
                .toList();
        List<AgentMessage> provenanceMessages = activeContexts == null ? allCoveredMessages : sourceToCompact;
        List<AgentMessage> coverageDeltaMessages = coverageDelta(previousSummary, visible, coveredFrom, coveredThrough);
        List<AgentMessageId> sourceIds =
                provenanceMessages.stream().map(AgentMessage::id).distinct().toList();
        long coveredSourceCount = activeContexts == null
                ? sourceIds.size()
                : previousSummary.map(ConversationSummary::coveredSourceCount).orElse(0L)
                        + coverageDeltaMessages.size();

        List<String> factValues = new ArrayList<>();
        List<String> decisionValues = new ArrayList<>();
        List<String> openItemValues = new ArrayList<>();
        List<String> priorityFacts = new ArrayList<>();
        List<String> priorityDecisions = new ArrayList<>();
        List<String> priorityOpenItems = new ArrayList<>();
        previousSummary.ifPresent(prev -> {
            factValues.addAll(prev.facts());
            decisionValues.addAll(prev.decisions());
            openItemValues.addAll(prev.openItems());
            prev.semanticSummary().ifPresent(sem -> {
                sem.goals().forEach(g -> factValues.add("Goal: " + g.text()));
                sem.constraints().forEach(c -> {
                    String value = "Constraint: " + c.text();
                    factValues.add(value);
                    priorityFacts.add(value);
                });
                sem.progress().completed().forEach(c -> factValues.add("Completed: " + c.text()));
                sem.criticalContext().forEach(c -> factValues.add("Context: " + c.text()));
                sem.decisions().forEach(d -> {
                    String value = "Decision: " + d.statement();
                    decisionValues.add(value);
                    priorityDecisions.add(value);
                });
                sem.unresolvedQuestions()
                        .forEach(q -> addPriority(openItemValues, priorityOpenItems, "Question: " + q.text()));
                sem.progress()
                        .active()
                        .forEach(a -> addPriority(openItemValues, priorityOpenItems, "Active: " + a.text()));
                sem.progress()
                        .blocked()
                        .forEach(b -> addPriority(openItemValues, priorityOpenItems, "Blocked: " + b.text()));
                sem.nextSteps().forEach(n -> addPriority(openItemValues, priorityOpenItems, "Next: " + n.text()));
            });
        });
        factValues.addAll(baseSummary.facts());
        decisionValues.addAll(baseSummary.decisions());
        openItemValues.addAll(baseSummary.openItems());
        List<String> facts = boundedDistinct(factValues, priorityFacts, policy.maxSummaryFacts());
        List<String> decisions = boundedDistinct(decisionValues, priorityDecisions, policy.maxSummaryFacts());
        List<String> openItems = boundedDistinct(openItemValues, priorityOpenItems, policy.maxSummaryFacts());

        Set<ToolCallId> toolRefs = new LinkedHashSet<>();
        previousSummary.ifPresent(prev -> toolRefs.addAll(prev.toolOutcomeReferences()));
        toolRefs.addAll(baseSummary.toolOutcomeReferences());
        provenanceMessages.stream()
                .flatMap(message -> message.contents().stream())
                .filter(ToolResultPart.class::isInstance)
                .map(ToolResultPart.class::cast)
                .map(ToolResultPart::toolCallId)
                .forEach(toolRefs::add);

        Set<String> securityLabels = new LinkedHashSet<>();
        previousSummary.ifPresent(prev -> securityLabels.addAll(prev.securityLabels()));
        provenanceMessages.forEach(
                message -> securityLabels.add(message.visibility().name().toLowerCase(Locale.ROOT)));
        List<ToolCallId> boundedToolRefs = boundedToolReferences(toolRefs);
        int estimatedTokens = Math.max(
                1,
                HeuristicTokenEstimator.tokens(String.join("\n", facts))
                        + HeuristicTokenEstimator.tokens(String.join("\n", decisions))
                        + HeuristicTokenEstimator.tokens(String.join("\n", openItems))
                        + (boundedToolRefs.size() * 8));

        ConversationSummary mergedSummary = new ConversationSummary(
                baseSummary.id(),
                baseSummary.version(),
                baseSummary.sessionId(),
                coveredFrom,
                coveredThrough,
                sourceIds,
                coveredSourceCount,
                activeContexts == null
                        ? coverageHash(Optional.empty(), allCoveredMessages)
                        : coverageHash(previousSummary, coverageDeltaMessages),
                facts,
                decisions,
                openItems,
                boundedToolRefs,
                estimatedTokens,
                time.now(),
                baseSummary.policyVersion(),
                baseSummary.compressorVersion(),
                Set.copyOf(securityLabels),
                true,
                Optional.empty(),
                CompactionQuality.DETERMINISTIC_DEGRADED);

        return commitCandidate(run, mergedSummary, expectedPreviousVersion, evaluationId);
    }

    private CompactionEvaluationOutcome commitSummary(
            AgentRun run,
            String evaluationId,
            Function<ToolCallId, ToolCall> resolver,
            Optional<ConversationSummary> previousSummary,
            List<AgentMessage> sourceToCompact,
            List<AgentMessage> visible,
            SemanticConversationSummaryV1 summary,
            CompactionTriggerReason reason,
            int physicalCalls,
            long expectedPreviousVersion,
            ModelMessageProjectionPlan projectionPlan,
            long initialEstimatedTokens,
            long retainedTailTokens,
            double cacheHitRate,
            long startNanos,
            boolean completeSource) {
        MessageCursor coveredFrom = previousSummary
                .map(ConversationSummary::coveredFrom)
                .orElseGet(() -> sourceToCompact.getFirst().cursor());
        MessageCursor coveredThrough = sourceToCompact.getLast().cursor();

        List<AgentMessage> allCoveredMessages = visible.stream()
                .filter(m -> m.cursor().compareTo(coveredFrom) >= 0)
                .filter(m -> m.cursor().compareTo(coveredThrough) <= 0)
                .toList();
        List<AgentMessage> provenanceMessages = activeContexts == null ? allCoveredMessages : sourceToCompact;
        List<AgentMessage> coverageDeltaMessages = coverageDelta(previousSummary, visible, coveredFrom, coveredThrough);
        String sourceHash = activeContexts == null
                ? coverageHash(Optional.empty(), allCoveredMessages)
                : coverageHash(previousSummary, coverageDeltaMessages);
        List<AgentMessageId> sourceIds =
                provenanceMessages.stream().map(AgentMessage::id).distinct().toList();
        long coveredSourceCount = activeContexts == null
                ? sourceIds.size()
                : previousSummary.map(ConversationSummary::coveredSourceCount).orElse(0L)
                        + coverageDeltaMessages.size();

        String markdown = SemanticSummaryRenderer.renderMarkdown(summary);
        int estimatedTokens = Math.max(1, HeuristicTokenEstimator.tokens(markdown));
        long projectedActiveHistoryTokensAfter = (long) estimatedTokens + retainedTailTokens;

        List<String> facts =
                summary.goals().stream().map(SemanticSummaryItem::text).toList();
        List<String> decisions = summary.decisions().stream()
                .map(SemanticDecisionItem::statement)
                .toList();
        List<String> openItems =
                summary.nextSteps().stream().map(SemanticSummaryItem::text).toList();
        Set<ToolCallId> toolOutcomeRefs = new LinkedHashSet<>();
        previousSummary.ifPresent(previous -> toolOutcomeRefs.addAll(previous.toolOutcomeReferences()));
        provenanceMessages.stream()
                .flatMap(m -> m.contents().stream())
                .filter(ToolResultPart.class::isInstance)
                .map(ToolResultPart.class::cast)
                .map(ToolResultPart::toolCallId)
                .forEach(toolOutcomeRefs::add);

        ConversationSummary domainSummary = new ConversationSummary(
                new SummaryId(ids.nextValue()),
                new SummaryVersion(expectedPreviousVersion + 1),
                run.sessionId(),
                coveredFrom,
                coveredThrough,
                sourceIds,
                coveredSourceCount,
                sourceHash,
                facts,
                decisions,
                openItems,
                boundedToolReferences(toolOutcomeRefs),
                estimatedTokens,
                time.now(),
                policy.version(),
                "semantic-v1",
                completeSource ? Set.of("internal", COMPLETE_SOURCE) : Set.of("internal"),
                true,
                Optional.of(summary),
                CompactionQuality.SEMANTIC_VALIDATED);

        SummaryCommit commit = commitCandidate(run, domainSummary, expectedPreviousVersion, evaluationId);
        if (commit.committed()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("evaluationId", evaluationId);
            data.put("summaryId", domainSummary.id().value());
            data.put("version", domainSummary.version().value());
            data.put("reason", reason.name());
            data.put("semanticCompactionReason", reason.name());
            data.put("tier1PruningBypassedSummary", false);
            data.put("projectedActiveHistoryTokensBefore", initialEstimatedTokens);
            data.put("projectedActiveHistoryTokensAfter", projectedActiveHistoryTokensAfter);
            data.put("omittedToolPayloadTokens", 0L);
            data.put("omittedToolResultCount", 0);
            data.put("compactionSummaryCacheHitRate", cacheHitRate);
            data.put("previousSummaryInputShare", cacheHitRate);
            data.put("compactionEvaluationElapsedMillis", elapsedMillis(startNanos));
            data.put("physicalCalls", physicalCalls);
            data.put("estimatedTokens", estimatedTokens);
            data.put("coveredThrough", domainSummary.coveredThrough().serialize());
            events.append(run.id(), "session.compacted", data, time.now());
        }
        return recoveryOutcome(
                run, evaluationId, reason, commit, visible, resolver, initialEstimatedTokens, cacheHitRate, startNanos);
    }

    private record SummaryCommit(String outcome, Optional<ConversationSummary> selected, boolean committed) {}

    private SummaryCommit commitCandidate(
            AgentRun run, ConversationSummary candidate, long expectedVersion, String evaluationId) {
        invoker.checkControl(run);
        if (!summaries.coversValidSource(candidate, candidate.coveredThrough())) {
            invalidateSummary(run);
            return new SummaryCommit("SOURCE_INVALIDATED", Optional.empty(), false);
        }
        // Keep only the repository write inside the conflict catch; event/cache errors are not CAS conflicts.
        ConversationSummary committed;
        try {
            invoker.checkControl(run);
            committed = summaries.compareAndSetValid(candidate, expectedVersion);
        } catch (OptimisticLockException conflict) {
            invalidateSummary(run);
            Optional<ConversationSummary> winner = summaries
                    .latestSnapshot(run.sessionId())
                    .latestValid()
                    .filter(summary -> summary.version().value() > expectedVersion)
                    .filter(summary -> summaries.coversValidSource(summary, summary.coveredThrough()));
            invoker.checkControl(run);
            return new SummaryCommit(winner.isPresent() ? "CONCURRENT_WINNER_SELECTED" : "CAS_CONFLICT", winner, false);
        } catch (CancellationObservedException
                | RuntimeLimitExceededException
                | RuntimeQuotaExceededException stopped) {
            throw stopped;
        } catch (RuntimeException storageFailure) {
            try {
                events.append(
                        run.id(),
                        "session.compaction-recovery",
                        Map.of(
                                "evaluationId",
                                evaluationId,
                                "outcome",
                                "STORAGE_FAILED",
                                "committed",
                                false,
                                "failureStage",
                                "PERSISTENCE",
                                "failureCode",
                                "STORAGE_FAILED"),
                        time.now());
            } catch (RuntimeException reportingFailure) {
                // The same unavailable store may reject diagnostics too. Preserve the authoritative write failure.
            }
            throw storageFailure;
        }
        invalidateSummary(run);
        return new SummaryCommit(
                candidate.quality() == CompactionQuality.SEMANTIC_VALIDATED
                        ? "SEMANTIC_COMMITTED"
                        : "DETERMINISTIC_COMMITTED",
                Optional.of(committed),
                true);
    }

    private void invalidateSummary(AgentRun run) {
        if (activeContexts != null) {
            activeContexts.invalidate(run.sessionId(), ActiveContextSnapshots.RebuildReason.SUMMARY_CHANGED);
        }
    }

    private CompactionEvaluationOutcome recoveryOutcome(
            AgentRun run,
            String evaluationId,
            CompactionTriggerReason reason,
            SummaryCommit commit,
            List<AgentMessage> visible,
            Function<ToolCallId, ToolCall> resolver,
            long beforeTokens,
            double cacheHitRate,
            long startNanos) {
        long afterTokens = commit.selected()
                .map(summary -> (long) summary.estimatedTokens()
                        + estimateGroups(groupsAfterSummary(visible, Optional.of(summary)), resolver))
                .orElse(beforeTokens);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("evaluationId", evaluationId);
        data.put("reason", reason.name());
        data.put("outcome", commit.outcome());
        data.put("committed", commit.committed());
        data.put("projectedActiveHistoryTokensBefore", beforeTokens);
        data.put("projectedActiveHistoryTokensAfter", afterTokens);
        commit.selected().ifPresent(summary -> {
            data.put("summaryId", summary.id().value());
            data.put("version", summary.version().value());
            data.put("quality", summary.quality().name());
        });
        if (commit.outcome().equals("SOURCE_INVALIDATED") || commit.outcome().equals("CAS_CONFLICT")) {
            data.put("failureStage", commit.outcome().equals("CAS_CONFLICT") ? "PERSISTENCE" : "SOURCE_VALIDATION");
            data.put("failureCode", commit.outcome());
        }
        events.append(run.id(), "session.compaction-recovery", data, time.now());
        return new CompactionEvaluationOutcome(
                reason,
                false,
                beforeTokens,
                afterTokens,
                0L,
                0,
                cacheHitRate,
                elapsedMillis(startNanos),
                commit.committed());
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

    /**
     * Aligns a model-produced candidate before validation.
     *
     * <p>The ordering is deliberate. The first reconciliation must run <strong>before</strong>
     * {@link #assignStableIds(SemanticConversationSummaryV1, List)}: a carry item the model placed in a category whose
     * id prefix does not match is otherwise anonymous to id assignment and would be re-numbered, losing its identity.
     * The second reconciliation runs after id assignment as an idempotent alignment pass that re-applies the verbatim
     * text, merged sourceRefs, category continuity and confidence invariants to the now-canonically identified
     * candidate.
     */
    private SemanticConversationSummaryV1 prepareCandidate(
            SemanticConversationSummaryV1 rawCandidate,
            List<SemanticSummaryItem> carryForward,
            ProjectedCompactionSource projected) {
        SemanticConversationSummaryV1 reconciled =
                CarryForwardReconciler.reconcile(rawCandidate, carryForward, projected);
        SemanticConversationSummaryV1 withIds = assignStableIds(reconciled, carryForward);
        return CarryForwardReconciler.reconcile(withIds, carryForward, projected);
    }

    private SemanticConversationSummaryV1 assignStableIds(SemanticConversationSummaryV1 original) {
        return assignStableIds(original, List.of());
    }

    private SemanticConversationSummaryV1 assignStableIds(
            SemanticConversationSummaryV1 original, List<SemanticSummaryItem> carryForwardItems) {
        Set<String> reservedIds = new HashSet<>();
        if (carryForwardItems != null) {
            for (SemanticSummaryItem item : carryForwardItems) {
                if (item.stableItemId() != null && !item.stableItemId().isBlank()) {
                    reservedIds.add(item.stableItemId());
                }
            }
        }
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
            goals.add(canonicalizeItem(item, "G-", Set.of("G-"), gIdx, usedIds, reservedIds));
        }

        List<SemanticSummaryItem> constraints = new ArrayList<>();
        for (SemanticSummaryItem item : original.constraints()) {
            constraints.add(canonicalizeItem(item, "C-", Set.of("C-"), cIdx, usedIds, reservedIds));
        }

        List<SemanticSummaryItem> completed = new ArrayList<>();
        for (SemanticSummaryItem item : original.progress().completed()) {
            completed.add(canonicalizeItem(item, "PC-", Set.of("PC-", "PA-", "PB-"), pcIdx, usedIds, reservedIds));
        }

        List<SemanticSummaryItem> active = new ArrayList<>();
        for (SemanticSummaryItem item : original.progress().active()) {
            active.add(canonicalizeItem(item, "PA-", Set.of("PA-", "PB-"), paIdx, usedIds, reservedIds));
        }

        List<SemanticSummaryItem> blocked = new ArrayList<>();
        for (SemanticSummaryItem item : original.progress().blocked()) {
            blocked.add(canonicalizeItem(item, "PB-", Set.of("PB-", "PA-"), pbIdx, usedIds, reservedIds));
        }

        List<SemanticDecisionItem> decisions = new ArrayList<>();
        for (SemanticDecisionItem item : original.decisions()) {
            decisions.add(canonicalizeDecision(item, "D-", Set.of("D-"), dIdx, usedIds, reservedIds));
        }

        List<SemanticSummaryItem> nextSteps = new ArrayList<>();
        for (SemanticSummaryItem item : original.nextSteps()) {
            nextSteps.add(canonicalizeItem(item, "N-", Set.of("N-"), nIdx, usedIds, reservedIds));
        }

        List<SemanticSummaryItem> criticalContext = new ArrayList<>();
        for (SemanticSummaryItem item : original.criticalContext()) {
            criticalContext.add(canonicalizeItem(item, "CC-", Set.of("CC-"), ccIdx, usedIds, reservedIds));
        }

        List<SemanticSummaryItem> questions = new ArrayList<>();
        for (SemanticSummaryItem item : original.unresolvedQuestions()) {
            questions.add(canonicalizeItem(item, "Q-", Set.of("Q-"), qIdx, usedIds, reservedIds));
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
            Set<String> usedIds,
            Set<String> reservedIds) {
        String id = item.stableItemId();
        boolean valid = id != null
                && !id.isBlank()
                && allowedPrefixes.stream().anyMatch(id::startsWith)
                && !usedIds.contains(id);
        if (!valid) {
            do {
                id = defaultPrefix + (counter.getAndIncrement());
            } while (usedIds.contains(id) || reservedIds.contains(id));
        }
        usedIds.add(id);
        return new SemanticSummaryItem(id, item.text(), item.sourceRefs(), item.confidence());
    }

    private SemanticDecisionItem canonicalizeDecision(
            SemanticDecisionItem item,
            String defaultPrefix,
            Set<String> allowedPrefixes,
            AtomicInteger counter,
            Set<String> usedIds,
            Set<String> reservedIds) {
        String id = item.stableItemId();
        boolean valid = id != null
                && !id.isBlank()
                && allowedPrefixes.stream().anyMatch(id::startsWith)
                && !usedIds.contains(id);
        if (!valid) {
            do {
                id = defaultPrefix + (counter.getAndIncrement());
            } while (usedIds.contains(id) || reservedIds.contains(id));
        }
        usedIds.add(id);
        return new SemanticDecisionItem(id, item.statement(), item.rationale(), item.status(), item.sourceRefs());
    }

    private AgentMessage hydrateCompactionSource(
            AgentRun run, AgentMessage message, Function<ToolCallId, ToolCall> resolver) {
        var parts = new ArrayList<io.haifa.agent.core.content.ContentPart>(message.contents());
        for (var part : message.contents()) {
            // Validate every part before planning any physical summary call, without concatenating a
            // second projection of the whole range just to discover unreadable content.
            if (!(part instanceof io.haifa.agent.core.content.TextPart)
                    && !(part instanceof io.haifa.agent.core.content.ToolCallPart)
                    && !(part instanceof io.haifa.agent.core.content.ToolResultPart))
                throw new ContextBuildException(
                        ContextBuildFailure.UNSUPPORTED_CONTEXT_CONTENT,
                        "complete compaction requires readable text sources");
            ToolCallId id = part instanceof io.haifa.agent.core.content.ToolCallPart call
                    ? call.toolCallId()
                    : part instanceof io.haifa.agent.core.content.ToolResultPart result ? result.toolCallId() : null;
            if (id == null) continue;
            var call = resolver.apply(id);
            if (call == null || !message.runId().equals(Optional.of(call.runId())))
                throw new OptimisticLockException("complete compaction Tool source is unavailable");
            var correlation = part instanceof io.haifa.agent.core.content.ToolCallPart request
                    ? request.providerCorrelationId()
                    : ((io.haifa.agent.core.content.ToolResultPart) part).providerCorrelationId();
            if (!call.providerCorrelationId().equals(correlation))
                throw new OptimisticLockException("complete compaction Tool identity changed");
            String body;
            if (part instanceof io.haifa.agent.core.content.ToolCallPart request) {
                if (!call.toolName().equals(request.toolName())
                        || !call.toolVersion().equals(request.toolVersion()))
                    throw new OptimisticLockException("complete compaction Tool definition changed");
                body = "Authoritative Tool arguments: "
                        + completeValue(call.arguments().values());
            } else {
                if (call.result().isEmpty()) {
                    if (!Set.of(
                                    io.haifa.agent.core.tool.ToolCallStatus.DENIED,
                                    io.haifa.agent.core.tool.ToolCallStatus.CANCELLED,
                                    io.haifa.agent.core.tool.ToolCallStatus.FAILED,
                                    io.haifa.agent.core.tool.ToolCallStatus.TIMEOUT)
                            .contains(call.status()))
                        throw new OptimisticLockException("complete compaction Tool result is pending");
                    continue; // The stored, complete protocol result is the whole available source.
                }
                var result =
                        history == null ? call.result().orElseThrow() : history.savedResultForCompaction(run, call);
                if (!result.artifacts().isEmpty()
                        || result.assets().stream().anyMatch(asset -> !asset.assetId()
                                .equals("tool-result:" + call.id().value())))
                    throw new ContextBuildException(
                            ContextBuildFailure.UNSUPPORTED_CONTEXT_CONTENT,
                            "complete compaction requires readable asset and artifact bodies");
                if (history == null
                        && result.assets().stream().anyMatch(asset -> asset.assetId()
                                .equals("tool-result:" + call.id().value())))
                    throw new ContextBuildException(
                            ContextBuildFailure.UNSUPPORTED_CONTEXT_CONTENT,
                            "saved compaction result requires its existing asset reader");
                body = "Authoritative saved Tool result: " + result.summary() + "\n"
                        + completeValue(result.structuredData()) + "\nsourceTruncated=" + result.truncated()
                        + "; assets=" + result.assets() + "; artifacts=" + result.artifacts();
            }
            parts.add(new io.haifa.agent.core.content.TextPart(body, "plain"));
        }
        return new AgentMessage(
                message.id(),
                message.sessionId(),
                message.runId(),
                message.parentMessageId(),
                message.role(),
                message.status(),
                message.visibility(),
                message.sequence(),
                parts,
                message.metadata(),
                message.createdAt());
    }

    private static String completeValue(Object value) {
        if (value instanceof Map<?, ?> map)
            return map.entrySet().stream()
                    .sorted(java.util.Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                    .map(entry -> entry.getKey() + ": " + completeValue(entry.getValue()))
                    .collect(Collectors.joining("\n"));
        if (value instanceof Set<?> set)
            return set.stream()
                    .map(SemanticCompactionCoordinator::completeValue)
                    .sorted()
                    .collect(Collectors.joining("\n"));
        if (value != null && value.getClass().isArray())
            return java.util.stream.IntStream.range(0, java.lang.reflect.Array.getLength(value))
                    .mapToObj(index -> completeValue(java.lang.reflect.Array.get(value, index)))
                    .collect(Collectors.joining("\n"));
        if (value instanceof List<?> list)
            return list.stream()
                    .map(SemanticCompactionCoordinator::completeValue)
                    .collect(Collectors.joining("\n"));
        return String.valueOf(value);
    }

    private void ensureSourcesCurrent(List<AgentMessage> source) {
        for (AgentMessage message : source) {
            if (state.message(message.id())
                    .filter(message::equals)
                    .filter(this::visibleToContext)
                    .isEmpty()) {
                throw new OptimisticLockException("compaction source is no longer visible");
            }
        }
    }

    private int minimumBatchCalls(
            List<List<AgentMessage>> groups,
            int start,
            int split,
            long available,
            int cap,
            Function<List<AgentMessage>, ProjectedCompactionSource> projector,
            boolean atomicBoundary) {
        int calls = 0;
        while (start < split && calls <= cap) {
            start = nextBatchEnd(groups, start, split, available, projector, atomicBoundary);
            calls++;
        }
        return calls;
    }

    private int smallerBatchEnd(List<List<AgentMessage>> groups, int start, int end, boolean atomicBoundary) {
        if (atomicBoundary && end > start + 1) return end - 1;
        for (int index = end - 1; index > start; index--) {
            if (isTurnAnchor(groups.get(index))) return index;
        }
        throw new CompactionBudgetExceededException(CompactionBudgetExceededException.Code.SUMMARY_INPUT_BUDGET);
    }

    private int nextBatchEnd(
            List<List<AgentMessage>> activeGroups,
            int start,
            int split,
            long availableTokens,
            Function<List<AgentMessage>, ProjectedCompactionSource> projector,
            boolean atomicBoundary) {
        if (start < 0 || start >= split) {
            throw new IllegalArgumentException("batch start must be within the compaction range");
        }
        int maxBatchGroups = 40;
        long maxBatchTokens = Math.max(1L, (availableTokens / 5) * 3 + (availableTokens % 5) * 3 / 5);

        long accumulatedTokens = 0L;
        int boundedIndex = start;
        for (int i = start; i < split; i++) {
            long gTokens = HeuristicTokenEstimator.tokens(
                    projector.apply(activeGroups.get(i)).safeConversationText());
            if (atomicBoundary && gTokens > availableTokens)
                throw new CompactionBudgetExceededException(
                        CompactionBudgetExceededException.Code.SUMMARY_INPUT_BUDGET);
            if (i > start && (i - start >= maxBatchGroups || accumulatedTokens + gTokens > maxBatchTokens)) {
                break;
            }
            accumulatedTokens += gTokens;
            boundedIndex = i + 1;
        }

        if (atomicBoundary) return boundedIndex;
        if (boundedIndex >= split) {
            return split;
        }

        int anchor = boundedIndex;
        while (anchor > start && !isTurnAnchor(activeGroups.get(anchor))) {
            anchor--;
        }
        if (anchor > start) {
            return anchor;
        }
        int forward = boundedIndex + 1;
        while (forward < split && !isTurnAnchor(activeGroups.get(forward))) {
            forward++;
        }
        return Math.min(forward, split);
    }

    private void addPriority(List<String> values, List<String> priorities, String value) {
        values.add(value);
        priorities.add(value);
    }

    private List<String> boundedDistinct(List<String> values, List<String> priorities, int maximum) {
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        for (String priority : priorities) {
            if (selected.size() >= maximum) {
                break;
            }
            selected.add(priority);
        }
        for (int index = values.size() - 1; index >= 0 && selected.size() < maximum; index--) {
            selected.add(values.get(index));
        }
        return List.copyOf(selected);
    }

    private int tailSplit(
            List<List<AgentMessage>> groups,
            long retainedTailBudget,
            Function<ToolCallId, ToolCall> resolver,
            boolean atomicBoundary) {
        long retained = 0L;
        int candidateSplit = groups.size();
        for (int index = groups.size() - 1; index >= 0; index--) {
            long groupTokens = estimateGroup(groups.get(index), resolver);
            if (candidateSplit < groups.size() && (retained + groupTokens > retainedTailBudget)) {
                break;
            }
            retained += groupTokens;
            candidateSplit = index;
        }

        if (atomicBoundary) return candidateSplit;
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
                    if (results.containsAll(calls)) break;
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

    /**
     * Owns the budget decision for skipping semantic compaction. Result pruning may bypass Tier 2 only when it saved
     * tokens and the complete session estimate (previous summary plus projected active history) already fits inside the
     * budget soft limit, which the trigger evaluator derived after reserving instructions, tools, memory and output.
     */
    private static boolean bypassCompactionRecommended(
            long softLimitTokens, ModelMessageProjectionPlan plan, long projectedSessionTokens) {
        return softLimitTokens > 0 && plan.tokensSavedByPruning() > 0 && projectedSessionTokens <= softLimitTokens;
    }

    /**
     * Shares one authoritative Tool Call lookup across planning and estimation: the loaded snapshot is reused when
     * present, otherwise each referenced Run is loaded once per operation.
     */
    private Function<ToolCallId, ToolCall> toolCallResolver(
            AgentRun run, ActiveContextSnapshot activeSnapshot, List<AgentMessage> visible) {
        if (activeSnapshot != null) {
            return id -> safeHistoryCall(run, activeSnapshot.toolCalls().get(id));
        }
        Map<io.haifa.agent.core.run.AgentRunId, Map<ToolCallId, ToolCall>> toolCallsByRun = new HashMap<>();
        return callId -> {
            for (AgentMessage message : visible) {
                io.haifa.agent.core.run.AgentRunId messageRunId =
                        message.runId().orElse(run.id());
                Map<ToolCallId, ToolCall> runCalls =
                        toolCallsByRun.computeIfAbsent(messageRunId, rId -> state.toolCalls(rId).stream()
                                .collect(Collectors.toMap(ToolCall::id, Function.identity(), (a, b) -> a)));
                ToolCall call = runCalls.get(callId);
                if (call != null) return safeHistoryCall(run, call);
            }
            return null;
        };
    }

    private Function<ToolCallId, ToolCall> rereadSourceToolCalls(AgentRun run, List<AgentMessage> source) {
        var ids = new HashMap<io.haifa.agent.core.run.AgentRunId, Set<ToolCallId>>();
        for (var message : source)
            for (var part : message.contents()) {
                ToolCallId id = part instanceof ToolCallPart call
                        ? call.toolCallId()
                        : part instanceof ToolResultPart result ? result.toolCallId() : null;
                if (id != null)
                    ids.computeIfAbsent(message.runId().orElse(run.id()), ignored -> new HashSet<>())
                            .add(id);
            }
        // Revalidate only the occurrences whose bodies were summarized, not every Tool in their owning Runs.
        var calls = state.toolCallsByIds(ids).stream().collect(Collectors.toMap(ToolCall::id, Function.identity()));
        return id -> safeHistoryCall(run, calls.get(id));
    }

    private ToolCall safeHistoryCall(AgentRun run, ToolCall call) {
        return history == null ? call : history.sanitize(run, call);
    }

    private long estimateGroups(List<List<AgentMessage>> groups) {
        return estimateGroups(groups, callId -> null);
    }

    private long estimateGroups(List<List<AgentMessage>> groups, Function<ToolCallId, ToolCall> resolver) {
        long total = 0;
        for (List<AgentMessage> group : groups) {
            total += estimateGroup(group, resolver);
        }
        return total;
    }

    private long estimateGroup(List<AgentMessage> group) {
        return estimateGroup(group, callId -> null);
    }

    private long estimateGroup(List<AgentMessage> group, Function<ToolCallId, ToolCall> resolver) {
        long total = 0;
        for (AgentMessage message : group) {
            for (var part : message.contents()) {
                if (part instanceof io.haifa.agent.core.content.TextPart text) {
                    total += HeuristicTokenEstimator.tokens(text.text());
                } else if (part instanceof ToolCallPart call) {
                    ToolCall authoritative = resolver != null ? resolver.apply(call.toolCallId()) : null;
                    if (authoritative != null) {
                        total += HeuristicTokenEstimator.tokens(authoritative.toolName())
                                + HeuristicTokenEstimator.tokens(
                                        authoritative.arguments().values());
                    } else {
                        total += HeuristicTokenEstimator.tokens(call.toolName())
                                + HeuristicTokenEstimator.tokens(
                                        call.providerCorrelationId().value())
                                + 16;
                    }
                } else if (part instanceof ToolResultPart res) {
                    ToolCall authoritative = resolver != null ? resolver.apply(res.toolCallId()) : null;
                    if (authoritative != null && authoritative.result().isPresent()) {
                        var tr = authoritative.result().get();
                        total += HeuristicTokenEstimator.tokens(tr.summary())
                                + HeuristicTokenEstimator.tokens(tr.structuredData());
                    } else {
                        total += HeuristicTokenEstimator.tokens(res.summary())
                                + HeuristicTokenEstimator.tokens(
                                        res.providerCorrelationId().value())
                                + 16;
                    }
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

    private String coverageHash(Optional<ConversationSummary> previousSummary, List<AgentMessage> messages) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            previousSummary.ifPresent(previous -> {
                digest.update(previous.sourceHash().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '|');
            });
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

    private static List<AgentMessage> coverageDelta(
            Optional<ConversationSummary> previousSummary,
            List<AgentMessage> visible,
            MessageCursor coveredFrom,
            MessageCursor coveredThrough) {
        MessageCursor exclusivePreviousBoundary =
                previousSummary.map(ConversationSummary::coveredThrough).orElse(MessageCursor.BEFORE_FIRST);
        return visible.stream()
                .filter(message -> message.cursor().compareTo(exclusivePreviousBoundary) > 0)
                .filter(message -> message.cursor().compareTo(coveredFrom) >= 0)
                .filter(message -> message.cursor().compareTo(coveredThrough) <= 0)
                .toList();
    }

    private static List<ToolCallId> boundedToolReferences(Iterable<ToolCallId> references) {
        LinkedHashSet<ToolCallId> distinct = new LinkedHashSet<>();
        references.forEach(distinct::add);
        if (distinct.size() <= MAX_SUMMARY_TOOL_REFERENCES) {
            return List.copyOf(distinct);
        }
        List<ToolCallId> ordered = new ArrayList<>(distinct);
        return List.copyOf(ordered.subList(ordered.size() - MAX_SUMMARY_TOOL_REFERENCES, ordered.size()));
    }

    private static void addSemanticSourceRefs(SemanticConversationSummaryV1 summary, Set<String> target) {
        summary.goals().forEach(item -> target.addAll(item.sourceRefs()));
        summary.constraints().forEach(item -> target.addAll(item.sourceRefs()));
        summary.progress().completed().forEach(item -> target.addAll(item.sourceRefs()));
        summary.progress().active().forEach(item -> target.addAll(item.sourceRefs()));
        summary.progress().blocked().forEach(item -> target.addAll(item.sourceRefs()));
        summary.decisions().forEach(item -> target.addAll(item.sourceRefs()));
        summary.nextSteps().forEach(item -> target.addAll(item.sourceRefs()));
        summary.criticalContext().forEach(item -> target.addAll(item.sourceRefs()));
        summary.unresolvedQuestions().forEach(item -> target.addAll(item.sourceRefs()));
    }

    private static long elapsedMillis(long startNanos) {
        return Math.max(0L, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos));
    }
}
