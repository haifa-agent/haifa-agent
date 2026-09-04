package io.haifa.agent.runtime.core.model;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.context.api.AgentContext;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelCallId;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelErrorCategory;
import io.haifa.agent.model.api.ModelInvocationException;
import io.haifa.agent.model.api.ModelRequestId;
import io.haifa.agent.model.api.ModelStreamControl;
import io.haifa.agent.model.api.ModelStreamEvent;
import io.haifa.agent.model.api.ModelToolSpecification;
import io.haifa.agent.runtime.core.bootstrap.RuntimeConfigurationSnapshot;
import io.haifa.agent.runtime.core.bootstrap.RuntimeControlOptions;
import io.haifa.agent.runtime.core.control.CancellationObservedException;
import io.haifa.agent.runtime.core.control.RunControlRegistry;
import io.haifa.agent.runtime.core.control.RunControlSignal;
import io.haifa.agent.runtime.core.storage.RuntimeEventAppender;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import io.haifa.agent.tool.api.FrozenToolBinding;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Invokes Model API from the exact immutable adapter/provider/model snapshot frozen for a run. */
public final class FrozenModelInvoker {
    private final RuntimeStateRepository state;
    private final Map<ModelAdapterKey, AgentChatModel> adapters;
    private final IdentifierGenerator ids;
    private final ModelMessageAssembler messages;
    private final AgentChatResponseMapper responses;
    private final RuntimeModelOutputPublisher output;
    private final RunControlRegistry controls;
    private final RuntimeEventAppender events;
    private final TimeProvider time;

    public FrozenModelInvoker(
            RuntimeStateRepository state,
            Map<ModelAdapterKey, AgentChatModel> adapters,
            IdentifierGenerator ids,
            RuntimeModelOutputPublisher output,
            RunControlRegistry controls,
            RuntimeEventAppender events,
            TimeProvider time,
            ModelImageResolver imageResolver,
            ModelAudioResolver audioResolver) {
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.adapters = Map.copyOf(Objects.requireNonNull(adapters, "adapters must not be null"));
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.messages = new ModelMessageAssembler(state, imageResolver, audioResolver);
        this.responses = new AgentChatResponseMapper(ids);
        this.output = Objects.requireNonNull(output, "output must not be null");
        this.controls = Objects.requireNonNull(controls, "controls must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
        this.time = Objects.requireNonNull(time, "time must not be null");
    }

    public FrozenModelBinding bind(AgentRun run) {
        Objects.requireNonNull(run, "run must not be null");
        RuntimeConfigurationSnapshot configuration = state.configuration(run.configurationSnapshot())
                .orElseThrow(() -> new IllegalStateException("run configuration snapshot is unavailable"));
        var model = configuration.model();
        ModelAdapterKey key = new ModelAdapterKey(model.adapterType(), model.adapterVersion());
        AgentChatModel adapter = adapters.get(key);
        if (adapter == null) {
            throw new IllegalStateException(
                    "frozen model adapter is unavailable: " + key.adapterType() + "@" + key.adapterVersion());
        }
        List<ModelToolSpecification> tools = configuration.toolBindings().stream()
                .map(FrozenModelInvoker::toModelSpecification)
                .toList();
        return new FrozenModelBinding(configuration, adapter, tools);
    }

    private static ModelToolSpecification toModelSpecification(FrozenToolBinding binding) {
        var definition = binding.definition();
        var schema = definition.inputSchema();
        return new ModelToolSpecification(
                binding.alias().value(),
                definition.version().value(),
                definition.description(),
                schema.id(),
                schema.version(),
                schema.document(),
                false);
    }

    public ModelInvocationResult invoke(FrozenModelBinding binding, AgentRun run, int iteration, AgentContext context) {
        return invoke(binding, run, iteration, context, new ModelRequestId(ids.nextValue()), 1);
    }

    public ModelInvocationResult invoke(
            FrozenModelBinding binding,
            AgentRun run,
            int iteration,
            AgentContext context,
            ModelRequestId requestId,
            int physicalAttempt) {
        return invoke(binding, run, iteration, context, requestId, physicalAttempt, context.tools());
    }

    /**
     * Performs one final answer attempt against the frozen model with the existing context but no disclosed tools.
     * Callers must still account for this physical model call against the Run budget.
     */
    public ModelInvocationResult invokeWithoutTools(
            FrozenModelBinding binding,
            AgentRun run,
            int iteration,
            AgentContext context,
            ModelRequestId requestId,
            int physicalAttempt) {
        return invoke(binding, run, iteration, context, requestId, physicalAttempt, List.of());
    }

    private ModelInvocationResult invoke(
            FrozenModelBinding binding,
            AgentRun run,
            int iteration,
            AgentContext context,
            ModelRequestId requestId,
            int physicalAttempt,
            List<ModelToolSpecification> disclosedTools) {
        if (!binding.configuration().reference().equals(run.configurationSnapshot())) {
            throw new IllegalArgumentException("model binding belongs to another configuration snapshot");
        }
        Objects.requireNonNull(requestId, "requestId must not be null");
        if (physicalAttempt < 1) throw new IllegalArgumentException("physicalAttempt must be positive");
        ModelCallId callId = new ModelCallId(ids.nextValue());
        if (binding.configuration().structuredOutput().isPresent()
                && !binding.configuration().model().capabilities().contains(ModelCapability.STRUCTURED_OUTPUT)) {
            throw new ModelInvocationException(
                    ModelErrorCategory.INVALID_REQUEST,
                    false,
                    0,
                    "structured_output_unsupported",
                    callId,
                    "selected model does not support structured output",
                    null);
        }
        AgentChatRequest request = new AgentChatRequest(
                callId,
                requestId,
                run.id(),
                iteration,
                physicalAttempt,
                binding.configuration().model(),
                messages.assemble(run.id(), context, binding.configuration().model()),
                disclosedTools,
                Math.toIntExact(Math.min(
                        context.budget().outputReserve(),
                        binding.configuration().model().maxOutputTokens())),
                Duration.ofMillis(Math.max(1, run.limits().maxIdleTimeMillis())),
                RuntimeControlOptions.providerOptions(binding.configuration().modelRequestOptions()),
                binding.configuration().structuredOutput());
        Instant startedAt = time.now();
        appendLifecycle(
                binding,
                run,
                callId,
                requestId,
                iteration,
                physicalAttempt,
                "model.attempt.scheduled",
                "SCHEDULED",
                0,
                0,
                "",
                "NONE",
                0,
                null);
        appendLifecycle(
                binding,
                run,
                callId,
                requestId,
                iteration,
                physicalAttempt,
                "model.call.started",
                "STARTED",
                0,
                0,
                "",
                "NONE",
                0,
                null);
        output.started(run.id(), callId.value(), physicalAttempt, iteration);
        AgentChatResponse response;
        try {
            response = binding.chatModel().invokeStreaming(request, event -> {
                if (controls.signal(run.id()) != RunControlSignal.NONE) return ModelStreamControl.CANCEL;
                if (event instanceof ModelStreamEvent.ContentDelta content) {
                    output.content(run.id(), callId.value(), physicalAttempt, content.delta());
                }
                return ModelStreamControl.CONTINUE;
            });
            var decision = responses.map(request, response, disclosedTools);
            var invocation = new ModelInvocationResult(
                    decision,
                    response.usage().inputTokens(),
                    response.usage().outputTokens(),
                    response.usage().costKnown(),
                    response.usage().costMinorUnits(),
                    Map.ofEntries(
                            Map.entry(
                                    "providerId",
                                    binding.configuration().model().providerId().value()),
                            Map.entry(
                                    "providerVersion",
                                    binding.configuration().model().providerVersion()),
                            Map.entry(
                                    "modelId",
                                    binding.configuration().model().modelId().value()),
                            Map.entry(
                                    "modelVersion",
                                    binding.configuration().model().modelVersion()),
                            Map.entry(
                                    "adapterVersion",
                                    binding.configuration().model().adapterVersion()),
                            Map.entry("modelCallId", callId.value()),
                            Map.entry("modelRequestId", requestId.value()),
                            Map.entry("responseId", response.responseId()),
                            Map.entry("finishReason", response.finishReason().name()),
                            Map.entry("cacheHitTokens", response.usage().cacheHitTokens()),
                            Map.entry("cacheMissTokens", response.usage().cacheMissTokens()),
                            Map.entry("reasoningTokens", response.usage().reasoningTokens())),
                    callId.value(),
                    physicalAttempt,
                    binding.configuration().model(),
                    response.reasoning());
            appendLifecycle(
                    binding,
                    run,
                    callId,
                    requestId,
                    iteration,
                    physicalAttempt,
                    "model.call.succeeded",
                    "SUCCEEDED",
                    response.usage().inputTokens(),
                    response.usage().outputTokens(),
                    response.finishReason().name(),
                    "NONE",
                    elapsedMillis(startedAt),
                    null);
            return invocation;
        } catch (RuntimeException exception) {
            boolean cancelled = controls.signal(run.id()) == RunControlSignal.CANCEL;
            output.failed(run.id(), callId.value(), physicalAttempt, iteration);
            if (exception instanceof ModelInvocationException modelFailure
                    && (modelFailure.category() == ModelErrorCategory.EMPTY_RESPONSE
                            || modelFailure.providerCode().equals("empty_response"))) {
                events.append(
                        run.id(),
                        "model.empty-response",
                        Map.of(
                                "modelCallId", callId.value(),
                                "modelRequestId", requestId.value(),
                                "providerId",
                                        binding.configuration()
                                                .model()
                                                .providerId()
                                                .value(),
                                "modelId", binding.configuration().model().providerModelId(),
                                "attempt", physicalAttempt,
                                "category", modelFailure.category().name(),
                                "providerCode", modelFailure.providerCode(),
                                "retryable", modelFailure.retryable()),
                        time.now());
            }
            appendLifecycle(
                    binding,
                    run,
                    callId,
                    requestId,
                    iteration,
                    physicalAttempt,
                    "model.call.failed",
                    cancelled ? "CANCELLED" : "FAILED",
                    0,
                    0,
                    "",
                    cancelled
                            ? "CANCELLED"
                            : exception instanceof ModelInvocationException modelFailure
                                    ? modelFailure.category().name()
                                    : exception
                                                    instanceof
                                                    io.haifa.agent.runtime.core.model.continuation
                                                                    .ModelContinuationException
                                                            continuationFailure
                                            ? continuationFailure.failure().name()
                                            : "MODEL_CALL_FAILED",
                    elapsedMillis(startedAt),
                    exception instanceof ModelInvocationException modelFailure ? modelFailure : null);
            if (cancelled) throw new CancellationObservedException();
            throw exception;
        }
    }

    private void appendLifecycle(
            FrozenModelBinding binding,
            AgentRun run,
            ModelCallId callId,
            ModelRequestId requestId,
            int iteration,
            int attempt,
            String type,
            String status,
            long inputTokens,
            long outputTokens,
            String finishReason,
            String reasonCode,
            long durationMillis,
            ModelInvocationException failure) {
        var model = binding.configuration().model();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("modelCallId", callId.value());
        data.put("modelRequestId", requestId.value());
        data.put("providerId", model.providerId().value());
        data.put("modelId", model.providerModelId());
        data.put("status", status);
        data.put("iteration", iteration);
        data.put("attempt", attempt);
        data.put("inputTokens", inputTokens);
        data.put("outputTokens", outputTokens);
        data.put("finishReason", finishReason);
        data.put("reasonCode", reasonCode);
        data.put("durationMillis", durationMillis);
        if (failure != null) {
            data.put("providerCode", failure.providerCode());
            data.put("retryable", failure.retryable());
            data.put("outputObserved", failure.outputObserved());
            data.put("retryAfterMillis", failure.retryAfterMillis().orElse(0L));
        }
        events.append(run.id(), type, data, time.now());
    }

    private long elapsedMillis(Instant startedAt) {
        return Math.max(0, Duration.between(startedAt, time.now()).toMillis());
    }

    public void committed(AgentRun run, ModelInvocationResult invocation, int iteration) {
        output.committed(run.id(), invocation.modelCallId(), invocation.physicalAttempt(), iteration);
    }

    public void failed(AgentRun run, ModelInvocationResult invocation, int iteration) {
        output.failed(run.id(), invocation.modelCallId(), invocation.physicalAttempt(), iteration);
    }
}
