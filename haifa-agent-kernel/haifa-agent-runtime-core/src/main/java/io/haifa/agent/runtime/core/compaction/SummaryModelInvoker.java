package io.haifa.agent.runtime.core.compaction;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.context.budget.HeuristicTokenEstimator;
import io.haifa.agent.context.compression.CompressionPolicy;
import io.haifa.agent.context.compression.SemanticConversationSummaryV1;
import io.haifa.agent.context.compression.SemanticSummarySchema;
import io.haifa.agent.context.compression.SemanticSummaryValidationException;
import io.haifa.agent.context.compression.SimpleJsonParser;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunUsageDelta;
import io.haifa.agent.core.run.StructuredOutputRequirement;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelCallId;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelMessage;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelRequestId;
import io.haifa.agent.runtime.core.bootstrap.RuntimeControlOptions;
import io.haifa.agent.runtime.core.control.CancellationObservedException;
import io.haifa.agent.runtime.core.control.RunControlRegistry;
import io.haifa.agent.runtime.core.control.RunControlSignal;
import io.haifa.agent.runtime.core.guard.RuntimeLimitExceededException;
import io.haifa.agent.runtime.core.guard.RuntimeQuotaExceededException;
import io.haifa.agent.runtime.core.lifecycle.RunTransitionCoordinator;
import io.haifa.agent.runtime.core.model.FrozenModelBinding;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Dedicated model invoker for conversation compaction.
 * Executes model calls outside database transactions and strictly accounts for Run budget.
 */
public final class SummaryModelInvoker {

    /**
     * Default upper bound for compaction summary output tokens, bounded by the model's max output limit.
     */
    private static final int DEFAULT_MAX_SUMMARY_OUTPUT_TOKENS = 32_768;

    private final RunTransitionCoordinator transitions;
    private final RunControlRegistry controls;
    private final IdentifierGenerator ids;
    private final TimeProvider time;
    private final CompressionPolicy policy;
    private final io.haifa.agent.runtime.core.storage.RuntimeEventAppender events;

    public SummaryModelInvoker(
            RunTransitionCoordinator transitions,
            RunControlRegistry controls,
            IdentifierGenerator ids,
            TimeProvider time,
            CompressionPolicy policy) {
        this(transitions, controls, ids, time, policy, null);
    }

    public SummaryModelInvoker(
            RunTransitionCoordinator transitions,
            RunControlRegistry controls,
            IdentifierGenerator ids,
            TimeProvider time,
            CompressionPolicy policy,
            io.haifa.agent.runtime.core.storage.RuntimeEventAppender events) {
        this.events = events;
        this.transitions = Objects.requireNonNull(transitions, "transitions must not be null");
        this.controls = Objects.requireNonNull(controls, "controls must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.time = Objects.requireNonNull(time, "time must not be null");
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
    }

    public SemanticConversationSummaryV1 invoke(
            FrozenModelBinding binding,
            AgentRun run,
            int iteration,
            String systemPrompt,
            String userPrompt,
            int physicalCallCount,
            boolean isRepair) {
        Objects.requireNonNull(binding, "binding must not be null");
        Objects.requireNonNull(run, "run must not be null");

        long remainingWallMillis = checkControl(run);

        requireStructuredOutput(binding);

        remainingRunCalls(run);
        requireInputFits(binding, systemPrompt, userPrompt);

        // 5. Hard cap check: physical calls per compaction session
        if (physicalCallCount >= policy.maxCompactionPhysicalCalls()) {
            throw new CompactionBudgetExceededException();
        }

        // 6. Account for the physical model call in Run usage
        transitions.usage(run, new AgentRunUsageDelta(0, 0, 0, 1, 0, 0, 0, 0));

        // 7. Build model request
        ModelCallId callId = new ModelCallId(ids.nextValue());
        ModelRequestId requestId = new ModelRequestId(ids.nextValue());
        Map<String, Object> options = new HashMap<>(
                RuntimeControlOptions.providerOptions(binding.configuration().modelRequestOptions()));

        StructuredOutputRequirement structuredRequirement = new StructuredOutputRequirement(
                "json-schema:SemanticConversationSummaryV1",
                "sha256:semantic-summary-v1",
                "SemanticConversationSummaryV1",
                SemanticSummarySchema.jsonSchema());

        List<ModelMessage> messages = List.of(
                ModelMessage.text(ModelMessageRole.SYSTEM, systemPrompt),
                ModelMessage.text(ModelMessageRole.USER, userPrompt));

        int maxOutput = summaryOutputTokens(binding);
        Duration timeout = Duration.ofMillis(Math.min(run.limits().maxIdleTimeMillis(), remainingWallMillis));

        AgentChatRequest request = new AgentChatRequest(
                callId,
                requestId,
                run.id(),
                run.rootRunId().value(),
                iteration,
                physicalCallCount + 1,
                binding.configuration().model(),
                messages,
                List.of(),
                maxOutput,
                timeout,
                options,
                Optional.of(structuredRequirement));

        io.haifa.agent.runtime.core.model.ModelInputAccounting.record(
                events,
                time,
                binding,
                request,
                isRepair ? "SUMMARY_REGENERATION" : "SUMMARY",
                io.haifa.agent.runtime.core.model.ModelInputAccounting.summarySources(request),
                Map.of());

        // 8. Invoke outside of DB transaction
        AgentChatResponse response = binding.chatModel().invoke(request);

        io.haifa.agent.runtime.core.model.ModelInputAccounting.recordUsage(
                events, time, binding, request, isRepair ? "SUMMARY_REGENERATION" : "SUMMARY", response.usage());

        // 9. Account for tokens and cost (must record usage even if finishReason != STOP)
        if (response.usage() != null) {
            transitions.usage(
                    run,
                    new AgentRunUsageDelta(
                            response.usage().inputTokens(),
                            response.usage().outputTokens(),
                            response.usage().cacheHitTokens(),
                            0,
                            0,
                            0,
                            response.usage().costMinorUnits(),
                            0));
        }

        // An admitted response is accounted before observing a stop; this does not admit another call.
        checkControl(run);

        // 10. Enforce fail-closed check on finish reason
        if (response.finishReason() != ModelFinishReason.STOP) {
            throw new SemanticSummaryValidationException(
                    SemanticSummaryValidationException.Code.UNEXPECTED_FINISH_REASON,
                    "Model compaction response did not finish with STOP (finishReason=" + response.finishReason() + ")",
                    List.of("UNEXPECTED_FINISH_REASON"));
        }

        // 11. Extract and parse structured output
        Map<String, Object> outputMap = null;
        if (response.structuredOutput().isPresent()) {
            outputMap = response.structuredOutput().get();
        } else if (response.content() != null && !response.content().isBlank()) {
            try {
                outputMap = SimpleJsonParser.parseObject(response.content());
            } catch (Exception ignored) {
                throw new SemanticSummaryValidationException(
                        SemanticSummaryValidationException.Code.JSON_PARSE_ERROR,
                        "Failed to parse JSON structured output from model response",
                        List.of("JSON_PARSE_ERROR"));
            }
        }

        if (outputMap == null || outputMap.isEmpty()) {
            throw new SemanticSummaryValidationException(
                    SemanticSummaryValidationException.Code.EMPTY_STRUCTURED_OUTPUT,
                    "Model returned empty response for compaction request",
                    List.of("EMPTY_STRUCTURED_OUTPUT"));
        }

        try {
            return SemanticConversationSummaryV1.fromMap(outputMap);
        } catch (Exception ignored) {
            throw new SemanticSummaryValidationException(
                    SemanticSummaryValidationException.Code.SCHEMA_MAPPING_ERROR,
                    "Failed to map structured output to SemanticConversationSummaryV1",
                    List.of("SCHEMA_MAPPING_ERROR"));
        }
    }

    static void requireStructuredOutput(FrozenModelBinding binding) {
        if (!binding.configuration().model().capabilities().contains(ModelCapability.STRUCTURED_OUTPUT)) {
            throw new CompactionModelCapabilityException(
                    binding.configuration().model().modelId().value(), ModelCapability.STRUCTURED_OUTPUT);
        }
    }

    static int summaryOutputTokens(FrozenModelBinding binding) {
        return Math.min(
                DEFAULT_MAX_SUMMARY_OUTPUT_TOKENS,
                binding.configuration().model().maxOutputTokens());
    }

    static long inputBudget(FrozenModelBinding binding) {
        long window = binding.configuration().model().contextWindow();
        long margin = Math.min(16_384L, Math.max(256L, window / 20L));
        return Math.max(0L, window - summaryOutputTokens(binding) - margin);
    }

    static long estimatedInput(String systemPrompt, String userPrompt) {
        return (long) HeuristicTokenEstimator.tokens(systemPrompt)
                + HeuristicTokenEstimator.tokens(userPrompt)
                + HeuristicTokenEstimator.tokens(SemanticSummarySchema.jsonSchema())
                + 24L;
    }

    static void requireInputFits(FrozenModelBinding binding, String systemPrompt, String userPrompt) {
        if (estimatedInput(systemPrompt, userPrompt) > inputBudget(binding)) {
            throw new CompactionBudgetExceededException(CompactionBudgetExceededException.Code.SUMMARY_INPUT_BUDGET);
        }
    }

    long remainingRunCalls(AgentRun run) {
        checkControl(run);
        long remaining = run.limits().maxModelCalls() - run.usage().modelCalls();
        if (run.budget().maxModelCalls() > 0) {
            remaining = Math.min(
                    remaining, run.budget().maxModelCalls() - run.usage().modelCalls());
        }
        if (remaining <= 0) throw RuntimeLimitExceededException.forRunLimits(run);
        return remaining;
    }

    /** Observes hard controls without treating the last admitted call as a new admission. */
    long checkControl(AgentRun run) {
        RunControlSignal signal = controls.signal(run.id());
        if (signal == RunControlSignal.CANCEL || signal == RunControlSignal.TIMEOUT) {
            throw new CancellationObservedException(controls.directive(run.id()));
        }
        if (signal.stopsExecution()) throw new CancellationObservedException(signal);
        Instant now = time.now();
        long elapsed = run.activeElapsedMillis(now);
        if (elapsed >= run.limits().maxWallTimeMillis()) {
            throw new RuntimeLimitExceededException(
                    "wallTimeMillis", run.limits().maxWallTimeMillis(), elapsed);
        }
        if (run.quotaPolicy().isExceededBy(run.usage())) {
            throw RuntimeQuotaExceededException.forRunQuota(run);
        }
        if (run.budget().isExceededBy(run.usage())) {
            throw RuntimeLimitExceededException.forRunBudget(run);
        }
        return run.limits().maxWallTimeMillis() - elapsed;
    }
}
