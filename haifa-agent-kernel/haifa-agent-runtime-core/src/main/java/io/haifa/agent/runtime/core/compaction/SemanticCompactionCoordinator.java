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
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.runtime.core.control.CancellationObservedException;
import io.haifa.agent.runtime.core.model.FrozenModelBinding;
import io.haifa.agent.runtime.core.model.ModelMessageProjectionPlan;
import io.haifa.agent.runtime.core.model.ModelMessageProjectionPlanner;
import io.haifa.agent.runtime.core.storage.OptimisticLockException;
import io.haifa.agent.runtime.core.storage.RuntimeEventAppender;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
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
    private final ModelMessageProjectionPlanner projectionPlanner;
    private static final int MAX_FAILED_RUNS_CACHE = 1024;
    private final Set<AgentRunId> activeBudgetCompactionFailedRuns = Collections.synchronizedSet(
            Collections.newSetFromMap(new LinkedHashMap<AgentRunId, Boolean>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<AgentRunId, Boolean> eldest) {
                    return size() > MAX_FAILED_RUNS_CACHE;
                }
            }));

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
                new ModelMessageProjectionPlanner(state));
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
    }

    /**
     * Evaluates compaction trigger before ContextBuild and executes compaction if soft limit is reached.
     */
    public CompactionEvaluationOutcome evaluateAndCompactIfNeeded(
            AgentRun run, int iteration, FrozenModelBinding binding) {
        long startNanos = System.nanoTime();
        if (!policy.semanticCompactionEnabled()) {
            return CompactionEvaluationOutcome.NONE;
        }

        List<AgentMessage> visible =
                state.messagesAfter(run.sessionId(), MessageCursor.BEFORE_FIRST, Integer.MAX_VALUE).stream()
                        .filter(this::visibleToContext)
                        .toList();
        if (visible.size() < 2) {
            return CompactionEvaluationOutcome.untriggered(0L, elapsedMillis(startNanos));
        }

        List<List<AgentMessage>> groups = atomicGroups(visible);
        if (groups.size() < 2) {
            return CompactionEvaluationOutcome.untriggered(0L, elapsedMillis(startNanos));
        }

        SummarySnapshot snapshot = summaries.latestSnapshot(run.sessionId());
        Optional<ConversationSummary> previousSummary = snapshot.latestValid();
        long expectedPreviousVersion = snapshot.latestVersion();
        List<List<AgentMessage>> activeGroups = groupsAfterSummary(visible, previousSummary);

        Map<io.haifa.agent.core.run.AgentRunId, Map<ToolCallId, ToolCall>> toolCallsByRun = new HashMap<>();
        Function<ToolCallId, ToolCall> resolver = callId -> {
            for (AgentMessage message : visible) {
                io.haifa.agent.core.run.AgentRunId messageRunId =
                        message.runId().orElse(run.id());
                Map<ToolCallId, ToolCall> runCalls =
                        toolCallsByRun.computeIfAbsent(messageRunId, rId -> state.toolCalls(rId).stream()
                                .collect(Collectors.toMap(ToolCall::id, Function.identity(), (a, b) -> a)));
                ToolCall call = runCalls.get(callId);
                if (call != null) {
                    return call;
                }
            }
            return null;
        };

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

        CompactionTriggerDecision decision = triggerEvaluator.evaluate(
                contextWindow, outputReserve, fixedPrefix, otherSources, currentTokens, activeGroups.size());
        if (!decision.shouldCompact()) {
            return CompactionEvaluationOutcome.untriggered(currentTokens, elapsedMillis(startNanos));
        }

        if (decision.reason() == CompactionTriggerReason.ACTIVE_HISTORY_BUDGET
                && activeBudgetCompactionFailedRuns.contains(run.id())) {
            log.info(
                    "Skipping active history budget compaction for run {} because a previous attempt failed in this run",
                    run.id().value());
            return CompactionEvaluationOutcome.untriggered(currentTokens, elapsedMillis(startNanos));
        }

        long softLimit = decision.budgetBreakdown().softLimitTokens();
        long prevTokens =
                previousSummary.map(ConversationSummary::estimatedTokens).orElse(0);
        long remainingSoftLimit = Math.max(0L, softLimit - prevTokens);
        List<AgentMessage> activeMessages =
                activeGroups.stream().flatMap(List::stream).toList();
        ModelMessageProjectionPlan projectionPlan = projectionPlanner.plan(
                activeMessages, resolver, ModelMessageProjectionPlanner.DEFAULT_PURE_READ_TOOLS, remainingSoftLimit);
        if (projectionPlan.bypassCompactionRecommended()) {
            long elapsed = elapsedMillis(startNanos);
            long beforeTokens = prevTokens + projectionPlan.rawActiveTokens();
            long afterTokens = prevTokens + projectionPlan.projectedActiveTokens();
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
            eventData.put("compactionEvaluationElapsedMillis", elapsed);
            eventData.put("rawActiveTokens", beforeTokens);
            eventData.put("projectedActiveTokens", afterTokens);
            eventData.put("tokensSaved", projectionPlan.tokensSavedByPruning());
            eventData.put(
                    "prunedToolResultsCount", projectionPlan.prunedToolResults().size());
            eventData.put(
                    "truncatedToolCallsCount",
                    projectionPlan.truncatedToolCalls().size());
            events.append(run.id(), "session.compaction-bypassed", eventData, time.now());
            return CompactionEvaluationOutcome.bypassed(
                    decision.reason(),
                    beforeTokens,
                    afterTokens,
                    projectionPlan.tokensSavedByPruning(),
                    projectionPlan.prunedToolResults().size(),
                    elapsed);
        }

        log.info(
                "Triggering semantic compaction for session {} reason: {}, activeBudget={}",
                run.sessionId().value(),
                decision.reason(),
                softLimit);
        return compactSession(
                run,
                iteration,
                binding,
                visible,
                groups,
                previousSummary,
                expectedPreviousVersion,
                decision.reason(),
                false,
                projectionPlan,
                currentTokens,
                softLimit,
                startNanos);
    }

    /**
     * Forces immediate compaction upon receiving CONTEXT_TOO_LONG error from provider.
     */
    public CompactionEvaluationOutcome forceCompactOnOverflow(AgentRun run, int iteration, FrozenModelBinding binding) {
        long startNanos = System.nanoTime();
        if (!policy.semanticCompactionEnabled()) {
            return CompactionEvaluationOutcome.NONE;
        }
        List<AgentMessage> visible =
                state.messagesAfter(run.sessionId(), MessageCursor.BEFORE_FIRST, Integer.MAX_VALUE).stream()
                        .filter(this::visibleToContext)
                        .toList();
        if (visible.size() < 2) {
            return CompactionEvaluationOutcome.untriggered(0L, elapsedMillis(startNanos));
        }
        List<List<AgentMessage>> groups = atomicGroups(visible);
        if (groups.size() < 2) {
            return CompactionEvaluationOutcome.untriggered(0L, elapsedMillis(startNanos));
        }
        SummarySnapshot snapshot = summaries.latestSnapshot(run.sessionId());
        Optional<ConversationSummary> previousSummary = snapshot.latestValid();
        long expectedPreviousVersion = snapshot.latestVersion();

        List<List<AgentMessage>> activeGroups = groupsAfterSummary(visible, previousSummary);
        if (activeGroups.size() < 2) {
            return CompactionEvaluationOutcome.untriggered(0L, elapsedMillis(startNanos));
        }

        Map<io.haifa.agent.core.run.AgentRunId, Map<ToolCallId, ToolCall>> toolCallsByRun = new HashMap<>();
        Function<ToolCallId, ToolCall> resolver = callId -> {
            for (AgentMessage message : visible) {
                io.haifa.agent.core.run.AgentRunId messageRunId =
                        message.runId().orElse(run.id());
                Map<ToolCallId, ToolCall> runCalls =
                        toolCallsByRun.computeIfAbsent(messageRunId, rId -> state.toolCalls(rId).stream()
                                .collect(Collectors.toMap(ToolCall::id, Function.identity(), (a, b) -> a)));
                ToolCall call = runCalls.get(callId);
                if (call != null) {
                    return call;
                }
            }
            return null;
        };

        long currentTokens =
                previousSummary.map(ConversationSummary::estimatedTokens).orElse(0)
                        + estimateGroups(activeGroups, resolver);

        // Tier 1 projection pruning is executed before Tier 2 compaction
        List<AgentMessage> activeMessages =
                activeGroups.stream().flatMap(List::stream).toList();
        long contextWindow = binding.configuration().model().contextWindow();
        long outputReserve = binding.configuration().model().maxOutputTokens();
        int safetyMargin = Math.min(16_384, Math.max(256, (int) (contextWindow / 20)));
        long available = Math.max(1000L, contextWindow - outputReserve - safetyMargin);
        ModelMessageProjectionPlan projectionPlan = projectionPlanner.plan(
                activeMessages, resolver, ModelMessageProjectionPlanner.DEFAULT_PURE_READ_TOOLS, available);

        log.info(
                "Forcing semantic compaction due to context overflow for session {}",
                run.sessionId().value());
        return compactSession(
                run,
                iteration,
                binding,
                visible,
                groups,
                previousSummary,
                expectedPreviousVersion,
                CompactionTriggerReason.PROVIDER_CONTEXT_TOO_LONG,
                true,
                projectionPlan,
                currentTokens,
                0L,
                startNanos);
    }

    private CompactionEvaluationOutcome compactSession(
            AgentRun run,
            int iteration,
            FrozenModelBinding binding,
            List<AgentMessage> visible,
            List<List<AgentMessage>> groups,
            Optional<ConversationSummary> previousSummary,
            long expectedPreviousVersion,
            CompactionTriggerReason reason,
            boolean overflow,
            ModelMessageProjectionPlan projectionPlan,
            long initialEstimatedTokens,
            long softLimit,
            long startNanos) {
        List<List<AgentMessage>> activeGroups = groupsAfterSummary(visible, previousSummary);
        if (activeGroups.isEmpty()) {
            return CompactionEvaluationOutcome.untriggered(initialEstimatedTokens, elapsedMillis(startNanos));
        }

        long contextWindow = binding.configuration().model().contextWindow();
        long outputReserve = binding.configuration().model().maxOutputTokens();
        int safetyMargin = Math.min(16_384, Math.max(256, (int) (contextWindow / 20)));
        long available = Math.max(1000L, contextWindow - outputReserve - safetyMargin);

        long targetTailBudget;
        if (overflow) {
            targetTailBudget = policy.minTailTokens();
        } else {
            long resolvedActiveBudget = softLimit > 0 ? softLimit : available;
            long calculated = (resolvedActiveBudget * policy.targetTailTokenPercent()) / 100L;
            long clamped = Math.clamp(calculated, (long) policy.minTailTokens(), (long) policy.maxTailTokens());
            targetTailBudget = Math.min(clamped, resolvedActiveBudget);
        }

        int split = tailSplit(activeGroups, targetTailBudget);
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
        });

        String systemPrompt = CompactionPromptRenderer.systemPrompt();
        int physicalCalls = 0;
        int batchStart = 0;
        try {
            while (batchStart < safeSplit) {
                int batchEnd = nextBatchEnd(activeGroups, batchStart, safeSplit, available);
                List<AgentMessage> batchSource = activeGroups.subList(batchStart, batchEnd).stream()
                        .flatMap(List::stream)
                        .toList();
                ProjectedCompactionSource projected = CompactionSourceProjector.project(batchSource);
                List<SemanticSummaryItem> carryForward = workingSemantic
                        .map(SemanticConversationSummaryV1::mandatoryCarryForwardItems)
                        .orElse(List.of());

                if (physicalCalls >= policy.maxCompactionPhysicalCalls()) {
                    throw new SemanticSummaryValidationException(
                            "compaction batch plan exceeded maxCompactionPhysicalCalls before the evicted range was complete");
                }
                String userPrompt = batchStart == 0
                        ? CompactionPromptRenderer.userPromptFromConversationSummary(
                                previousSummary, carryForward, projected)
                        : CompactionPromptRenderer.userPrompt(workingSemantic, carryForward, projected);
                SemanticConversationSummaryV1 candidate = assignStableIds(
                        invoker.invoke(binding, run, iteration, systemPrompt, userPrompt, physicalCalls++, false));
                try {
                    SemanticSummaryValidator.validate(
                            candidate, projected, carryForward, historicalDurableRefs, workingSemantic);
                } catch (SemanticSummaryValidationException validationEx) {
                    if (physicalCalls >= policy.maxCompactionPhysicalCalls()) {
                        throw validationEx;
                    }
                    log.info(
                            "Compaction validation failed [errorCount={}]. Attempting repair call.",
                            validationEx.validationErrors().size());
                    String repairPrompt = batchStart == 0
                            ? CompactionPromptRenderer.repairPromptFromConversationSummary(
                                    candidate,
                                    validationEx.validationErrors(),
                                    previousSummary,
                                    carryForward,
                                    projected)
                            : CompactionPromptRenderer.repairPrompt(
                                    candidate,
                                    validationEx.validationErrors(),
                                    workingSemantic,
                                    carryForward,
                                    projected);
                    candidate = assignStableIds(
                            invoker.invoke(binding, run, iteration, systemPrompt, repairPrompt, physicalCalls++, true));
                    SemanticSummaryValidator.validate(
                            candidate, projected, carryForward, historicalDurableRefs, workingSemantic);
                }
                // Keep the complete, validated semantic state only in memory until every batch succeeds.
                workingSemantic =
                        Optional.of(resolveAliases(candidate, projected.messageAliases(), projected.toolAliases()));
                batchSource.forEach(
                        message -> historicalDurableRefs.add(message.id().value()));
                projected.toolAliases().values().forEach(id -> historicalDurableRefs.add(id.value()));
                batchStart = batchEnd;
            }
        } catch (CancellationObservedException cancelled) {
            throw cancelled;
        } catch (Exception ex) {
            String category = failureCategory(ex);
            String errorCode = validationErrorCode(ex);
            long elapsed = elapsedMillis(startNanos);
            if (reason == CompactionTriggerReason.ACTIVE_HISTORY_BUDGET) {
                log.warn(
                        "Active history budget compaction failed for run {} [category={}, code={}], continuing gracefully without compaction",
                        run.id().value(),
                        category,
                        errorCode);
                activeBudgetCompactionFailedRuns.add(run.id());
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("reason", reason.name());
                data.put("semanticCompactionReason", reason.name());
                data.put("tier1PruningBypassedSummary", false);
                data.put("projectedActiveHistoryTokensBefore", initialEstimatedTokens);
                data.put("projectedActiveHistoryTokensAfter", initialEstimatedTokens);
                data.put("omittedToolPayloadTokens", 0L);
                data.put("omittedToolResultCount", 0);
                data.put("compactionSummaryCacheHitRate", cacheHitRate);
                data.put("compactionEvaluationElapsedMillis", elapsed);
                data.put("failureCategory", category);
                data.put("validationErrorCode", errorCode);
                data.put("physicalCalls", physicalCalls);
                data.put("degraded", true);
                events.append(run.id(), "session.compaction-failed", data, time.now());
                return CompactionEvaluationOutcome.failed(reason, initialEstimatedTokens, 0L, 0, cacheHitRate, elapsed);
            }
            log.warn("Semantic compaction failed: category={}, code={}", category, errorCode);
            boolean degraded = policy.allowDeterministicDegradedFallback() || overflow;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("reason", reason.name());
            data.put("semanticCompactionReason", reason.name());
            data.put("tier1PruningBypassedSummary", false);
            data.put("projectedActiveHistoryTokensBefore", initialEstimatedTokens);
            data.put("projectedActiveHistoryTokensAfter", initialEstimatedTokens);
            data.put("omittedToolPayloadTokens", 0L);
            data.put("omittedToolResultCount", 0);
            data.put("compactionSummaryCacheHitRate", cacheHitRate);
            data.put("compactionEvaluationElapsedMillis", elapsed);
            data.put("failureCategory", category);
            data.put("validationErrorCode", errorCode);
            data.put("physicalCalls", physicalCalls);
            data.put("degraded", degraded);
            events.append(run.id(), "session.compaction-failed", data, time.now());
            if (degraded) {
                log.info("Falling back to deterministic degraded compaction");
                fallbackToDeterministic(run, previousSummary, sourceToCompact, visible, expectedPreviousVersion);
                return CompactionEvaluationOutcome.failed(reason, initialEstimatedTokens, 0L, 0, cacheHitRate, elapsed);
            }
            throw (ex instanceof RuntimeException re) ? re : new RuntimeException(ex);
        }

        List<List<AgentMessage>> tailGroups = activeGroups.subList(safeSplit, activeGroups.size());
        long retainedTailTokens = estimateGroups(tailGroups);

        return commitSummary(
                run,
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
                startNanos);
    }

    private static String failureCategory(Exception exception) {
        return exception instanceof SemanticSummaryValidationException ? "VALIDATION" : "MODEL_OR_RUNTIME";
    }

    private static String validationErrorCode(Exception exception) {
        return exception instanceof SemanticSummaryValidationException ? "VALIDATION_REJECTED" : "NONE";
    }

    private void fallbackToDeterministic(
            AgentRun run,
            Optional<ConversationSummary> previousSummary,
            List<AgentMessage> sourceToCompact,
            List<AgentMessage> visible,
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

        MessageCursor coveredThrough = sourceToCompact.getLast().cursor();
        List<AgentMessage> allCoveredMessages = visible.stream()
                .filter(message -> message.cursor().compareTo(coveredThrough) <= 0)
                .toList();
        List<AgentMessageId> allSourceIds =
                allCoveredMessages.stream().map(AgentMessage::id).distinct().toList();

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
        allCoveredMessages.stream()
                .flatMap(message -> message.contents().stream())
                .filter(ToolResultPart.class::isInstance)
                .map(ToolResultPart.class::cast)
                .map(ToolResultPart::toolCallId)
                .forEach(toolRefs::add);

        Set<String> securityLabels = new LinkedHashSet<>();
        previousSummary.ifPresent(prev -> securityLabels.addAll(prev.securityLabels()));
        allCoveredMessages.forEach(
                message -> securityLabels.add(message.visibility().name().toLowerCase(Locale.ROOT)));
        int estimatedTokens = Math.max(
                1,
                HeuristicTokenEstimator.tokens(String.join("\n", facts))
                        + HeuristicTokenEstimator.tokens(String.join("\n", decisions))
                        + HeuristicTokenEstimator.tokens(String.join("\n", openItems))
                        + (toolRefs.size() * 8));

        ConversationSummary mergedSummary = new ConversationSummary(
                baseSummary.id(),
                baseSummary.version(),
                baseSummary.sessionId(),
                previousSummary.map(ConversationSummary::coveredFrom).orElse(baseSummary.coveredFrom()),
                coveredThrough,
                allSourceIds,
                hashMessages(allCoveredMessages),
                facts,
                decisions,
                openItems,
                List.copyOf(toolRefs),
                estimatedTokens,
                time.now(),
                baseSummary.policyVersion(),
                baseSummary.compressorVersion(),
                Set.copyOf(securityLabels),
                true,
                Optional.empty(),
                CompactionQuality.DETERMINISTIC_DEGRADED);

        try {
            summaries.compareAndSetValid(mergedSummary, expectedPreviousVersion);
        } catch (Exception conflict) {
            log.info("Deterministic fallback CAS conflict: {}", conflict.getMessage());
        }
    }

    private CompactionEvaluationOutcome commitSummary(
            AgentRun run,
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
            long startNanos) {
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
        long projectedActiveHistoryTokensAfter = (long) estimatedTokens + retainedTailTokens;

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
            return CompactionEvaluationOutcome.failed(
                    reason,
                    initialEstimatedTokens,
                    projectionPlan.tokensSavedByPruning(),
                    projectionPlan.prunedToolResults().size(),
                    cacheHitRate,
                    elapsedMillis(startNanos));
        }

        long elapsed = elapsedMillis(startNanos);
        try {
            summaries.compareAndSetValid(domainSummary, expectedPreviousVersion);
            Map<String, Object> data = new LinkedHashMap<>();
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
            data.put("compactionEvaluationElapsedMillis", elapsed);
            data.put("physicalCalls", physicalCalls);
            data.put("estimatedTokens", estimatedTokens);
            data.put("coveredThrough", domainSummary.coveredThrough().serialize());
            events.append(run.id(), "session.compacted", data, time.now());
            log.info(
                    "Committed semantic conversation summary {}@{} for session {}",
                    domainSummary.id().value(),
                    domainSummary.version().value(),
                    run.sessionId().value());
            return CompactionEvaluationOutcome.compacted(
                    reason, initialEstimatedTokens, projectedActiveHistoryTokensAfter, 0L, 0, cacheHitRate, elapsed);
        } catch (OptimisticLockException conflict) {
            log.warn("CAS conflict when committing summary: {}. Re-evaluating next iteration.", conflict.getMessage());
            return CompactionEvaluationOutcome.failed(reason, initialEstimatedTokens, 0L, 0, cacheHitRate, elapsed);
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
            completed.add(canonicalizeItem(item, "PC-", Set.of("PC-", "PA-", "PB-"), pcIdx, usedIds));
        }

        List<SemanticSummaryItem> active = new ArrayList<>();
        for (SemanticSummaryItem item : original.progress().active()) {
            active.add(canonicalizeItem(item, "PA-", Set.of("PA-", "PB-"), paIdx, usedIds));
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

    private int nextBatchEnd(List<List<AgentMessage>> activeGroups, int start, int split, long availableTokens) {
        if (start < 0 || start >= split) {
            throw new IllegalArgumentException("batch start must be within the compaction range");
        }
        int maxBatchGroups = 40;
        long maxBatchTokens = Math.max(1L, (availableTokens * 3) / 5);

        long accumulatedTokens = 0L;
        int boundedIndex = start;
        for (int i = start; i < split; i++) {
            long gTokens = estimateGroup(activeGroups.get(i));
            if (i > start && (i - start >= maxBatchGroups || accumulatedTokens + gTokens > maxBatchTokens)) {
                break;
            }
            accumulatedTokens += gTokens;
            boundedIndex = i + 1;
        }

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

    private static long elapsedMillis(long startNanos) {
        return Math.max(0L, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos));
    }
}
