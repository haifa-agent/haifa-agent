package io.haifa.agent.runtime.core.compression;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
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
import io.haifa.agent.model.api.ModelMessage;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelRequestId;
import io.haifa.agent.runtime.core.bootstrap.RuntimeControlOptions;
import io.haifa.agent.runtime.core.control.CancellationObservedException;
import io.haifa.agent.runtime.core.control.RunControlRegistry;
import io.haifa.agent.runtime.core.control.RunControlSignal;
import io.haifa.agent.runtime.core.guard.RuntimeLimitExceededException;
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

    private final RunTransitionCoordinator transitions;
    private final RunControlRegistry controls;
    private final IdentifierGenerator ids;
    private final TimeProvider time;
    private final CompressionPolicy policy;

    public SummaryModelInvoker(
            RunTransitionCoordinator transitions,
            RunControlRegistry controls,
            IdentifierGenerator ids,
            TimeProvider time,
            CompressionPolicy policy) {
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

        // 1. Guard check: wall time limit
        Instant now = time.now();
        if (run.activeElapsedMillis(now) > run.limits().maxWallTimeMillis()) {
            throw new RuntimeLimitExceededException(
                    "wallTimeMillis", run.limits().maxWallTimeMillis(), run.activeElapsedMillis(now));
        }

        // 2. Guard check: cancellation
        if (controls.signal(run.id()) != RunControlSignal.NONE) {
            throw new CancellationObservedException();
        }

        // 3. Guard check: model call limit
        if (run.usage().modelCalls() >= run.limits().maxModelCalls()) {
            throw new RuntimeLimitExceededException(
                    "modelCalls", run.limits().maxModelCalls(), run.usage().modelCalls());
        }

        // 4. Hard cap check: physical calls per compaction session
        if (physicalCallCount >= policy.maxCompactionPhysicalCalls()) {
            throw new IllegalStateException(
                    "Compaction physical call limit (" + policy.maxCompactionPhysicalCalls() + ") reached");
        }

        // 5. Account for the physical model call in Run usage
        transitions.usage(run, new AgentRunUsageDelta(0, 0, 0, 1, 0, 0, 0, 0));

        // 6. Build model request
        ModelCallId callId = new ModelCallId(ids.nextValue());
        ModelRequestId requestId = new ModelRequestId(ids.nextValue());
        Map<String, Object> options = new HashMap<>(
                RuntimeControlOptions.providerOptions(binding.configuration().modelRequestOptions()));
        options.put("modelRequestPurpose", isRepair ? "REPAIR" : "COMPACTION");

        StructuredOutputRequirement structuredRequirement = new StructuredOutputRequirement(
                "json-schema:SemanticConversationSummaryV1",
                "sha256:semantic-summary-v1",
                "SemanticConversationSummaryV1",
                SemanticSummarySchema.jsonSchema());

        List<ModelMessage> messages = List.of(
                ModelMessage.text(ModelMessageRole.SYSTEM, systemPrompt),
                ModelMessage.text(ModelMessageRole.USER, userPrompt));

        int maxOutput = Math.min(4096, binding.configuration().model().maxOutputTokens());
        Duration timeout = Duration.ofMillis(Math.max(1, run.limits().maxIdleTimeMillis()));

        AgentChatRequest request = new AgentChatRequest(
                callId,
                requestId,
                run.id(),
                iteration,
                physicalCallCount + 1,
                binding.configuration().model(),
                messages,
                List.of(),
                maxOutput,
                timeout,
                options,
                Optional.of(structuredRequirement));

        // 7. Invoke outside of DB transaction
        AgentChatResponse response = binding.chatModel().invoke(request);

        // 8. Account for tokens and cost
        transitions.usage(run, new AgentRunUsageDelta(
                response.usage().inputTokens(),
                response.usage().outputTokens(),
                response.usage().cacheHitTokens(),
                0,
                0,
                0,
                response.usage().costMinorUnits(),
                0));

        // 9. Extract and parse structured output
        Map<String, Object> outputMap = null;
        if (response.structuredOutput().isPresent()) {
            outputMap = response.structuredOutput().get();
        } else if (response.content() != null && !response.content().isBlank()) {
            try {
                outputMap = SimpleJsonParser.parseObject(response.content());
            } catch (Exception parseException) {
                throw new SemanticSummaryValidationException(
                        "Failed to parse JSON structured output from model response: " + parseException.getMessage(),
                        List.of("JSON_PARSE_ERROR"));
            }
        }

        if (outputMap == null || outputMap.isEmpty()) {
            throw new SemanticSummaryValidationException(
                    "Model returned empty response for compaction request",
                    List.of("EMPTY_STRUCTURED_OUTPUT"));
        }

        try {
            return SemanticConversationSummaryV1.fromMap(outputMap);
        } catch (Exception mappingException) {
            throw new SemanticSummaryValidationException(
                    "Failed to map structured output to SemanticConversationSummaryV1: " + mappingException.getMessage(),
                    List.of("SCHEMA_MAPPING_ERROR"));
        }
    }
}
