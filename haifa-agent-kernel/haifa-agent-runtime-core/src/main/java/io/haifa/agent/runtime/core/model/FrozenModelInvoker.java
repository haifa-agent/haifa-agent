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
import io.haifa.agent.runtime.core.bootstrap.DefinitionResolver;
import io.haifa.agent.runtime.core.bootstrap.RuntimeConfigurationSnapshot;
import io.haifa.agent.runtime.core.bootstrap.RuntimeControlOptions;
import io.haifa.agent.runtime.core.context.ActiveContextSnapshots;
import io.haifa.agent.runtime.core.control.CancellationObservedException;
import io.haifa.agent.runtime.core.control.RunControlRegistry;
import io.haifa.agent.runtime.core.control.RunControlSignal;
import io.haifa.agent.runtime.core.delegation.DelegationTool;
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
    private final DefinitionResolver childDefinitions;

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
        this(state, adapters, ids, output, controls, events, time, imageResolver, audioResolver, null);
    }

    public FrozenModelInvoker(
            RuntimeStateRepository state,
            Map<ModelAdapterKey, AgentChatModel> adapters,
            IdentifierGenerator ids,
            RuntimeModelOutputPublisher output,
            RunControlRegistry controls,
            RuntimeEventAppender events,
            TimeProvider time,
            ModelImageResolver imageResolver,
            ModelAudioResolver audioResolver,
            ActiveContextSnapshots activeContexts) {
        this(state, adapters, ids, output, controls, events, time, imageResolver, audioResolver, activeContexts, null);
    }

    /**
     * @param childDefinitions resolves allowed child agents for the Runtime delegation Tool; {@code null} never
     *     discloses delegation
     */
    public FrozenModelInvoker(
            RuntimeStateRepository state,
            Map<ModelAdapterKey, AgentChatModel> adapters,
            IdentifierGenerator ids,
            RuntimeModelOutputPublisher output,
            RunControlRegistry controls,
            RuntimeEventAppender events,
            TimeProvider time,
            ModelImageResolver imageResolver,
            ModelAudioResolver audioResolver,
            ActiveContextSnapshots activeContexts,
            DefinitionResolver childDefinitions) {
        this.childDefinitions = childDefinitions;
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.adapters = Map.copyOf(Objects.requireNonNull(adapters, "adapters must not be null"));
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.messages = new ModelMessageAssembler(
                state, imageResolver, audioResolver, new ModelMessageProjectionPlanner(state), activeContexts);
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
        List<ModelToolSpecification> tools = new java.util.ArrayList<>(configuration.toolBindings().stream()
                .map(FrozenModelInvoker::toModelSpecification)
                .toList());
        if (childDefinitions != null) {
            DelegationTool.specification(run, configuration, childDefinitions).ifPresent(delegation -> {
                if (tools.stream().anyMatch(tool -> tool.name().equals(delegation.name()))) {
                    throw new IllegalStateException("a frozen tool alias collides with the Runtime delegation tool");
                }
                tools.add(delegation);
            });
        }
        return new FrozenModelBinding(configuration, adapter, List.copyOf(tools));
    }

    private static ModelToolSpecification toModelSpecification(FrozenToolBinding binding) {
        var definition = binding.definition();
        var schema = definition.inputSchema();
        return new ModelToolSpecification(
                definition.name().value(),
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
        ModelMessageAssembler.AssemblyResult messageAssembly = messages.assembleWithMetrics(
                run, context, binding.configuration().model());
        AgentChatRequest request = new AgentChatRequest(
                callId,
                requestId,
                run.id(),
                iteration,
                physicalAttempt,
                binding.configuration().model(),
                messageAssembly.messages(),
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
                0,
                "",
                "NONE",
                0,
                null,
                Map.of(
                        "requestAssemblyElapsedMillis",
                                messageAssembly.metrics().elapsedMillis(),
                        "continuationBatchCount", messageAssembly.metrics().continuationBatchCount(),
                        "continuationRecordCount", messageAssembly.metrics().continuationRecordCount(),
                        "assemblerToolCallBatchCount", messageAssembly.metrics().toolCallBatchCount(),
                        "snapshotFactsReused", messageAssembly.metrics().snapshotFactsReused()));
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
                0,
                "",
                "NONE",
                0,
                null);
        output.started(run.id(), callId.value(), physicalAttempt, iteration);
        ReasoningBudget reasoningBudget = new ReasoningBudget(
                RuntimeControlOptions.maxReasoningBytes(binding.configuration().modelRequestOptions()),
                RuntimeControlOptions.maxReasoningDurationMillis(
                        binding.configuration().modelRequestOptions()));
        AgentChatResponse response;
        try {
            response = binding.chatModel().invokeStreaming(request, event -> {
                if (controls.signal(run.id()).stopsExecution()) return ModelStreamControl.CANCEL;
                if (event instanceof ModelStreamEvent.ContentDelta content) {
                    output.content(run.id(), callId.value(), physicalAttempt, content.delta());
                } else if (event instanceof ModelStreamEvent.ReasoningDelta reasoning) {
                    output.modelActivity(run.id(), callId.value(), physicalAttempt);
                    if (!reasoningBudget.observe(reasoning.delta(), time.now())) return ModelStreamControl.CANCEL;
                }
                return ModelStreamControl.CONTINUE;
            });
            RunControlSignal completedSignal = controls.signal(run.id());
            if (completedSignal.stopsExecution()) {
                if (completedSignal == RunControlSignal.CANCEL || completedSignal == RunControlSignal.TIMEOUT) {
                    throw new CancellationObservedException(controls.directive(run.id()));
                }
                throw new CancellationObservedException(completedSignal);
            }
            var decision = responses.map(request, response, disclosedTools);
            var invocation = new ModelInvocationResult(
                    decision,
                    response.usage().inputTokens(),
                    response.usage().outputTokens(),
                    Math.min(response.usage().cacheHitTokens(), response.usage().inputTokens()),
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
                    Math.min(response.usage().cacheHitTokens(), response.usage().inputTokens()),
                    response.finishReason().name(),
                    "NONE",
                    elapsedMillis(startedAt),
                    null);
            return invocation;
        } catch (RuntimeException exception) {
            RunControlSignal stopSignal = controls.signal(run.id());
            boolean cancelled = stopSignal.stopsExecution();
            RuntimeException failure = exception;
            if (!cancelled && reasoningBudget.exceeded()) {
                failure = new ModelInvocationException(
                        ModelErrorCategory.OUTPUT_LIMIT_EXCEEDED,
                        false,
                        0,
                        "reasoning_budget_exceeded",
                        callId,
                        "reasoning budget exceeded",
                        exception,
                        null,
                        true);
                events.append(
                        run.id(),
                        "model.reasoning-budget-exceeded",
                        Map.of(
                                "modelCallId", callId.value(),
                                "modelRequestId", requestId.value(),
                                "attempt", physicalAttempt,
                                "reasoningBytes", reasoningBudget.bytes(),
                                "reasoningEvents", reasoningBudget.events(),
                                "reasoningDurationMillis", reasoningBudget.durationMillis(),
                                "maxReasoningBytes", reasoningBudget.maxBytes(),
                                "maxReasoningDurationMillis", reasoningBudget.maxDurationMillis()),
                        time.now());
            }
            output.failed(run.id(), callId.value(), physicalAttempt, iteration);
            if (failure instanceof ModelInvocationException modelFailure
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
                    0,
                    "",
                    cancelled
                            ? "CANCELLED"
                            : failure instanceof ModelInvocationException modelFailure
                                    ? modelFailure.category().name()
                                    : failure
                                                    instanceof
                                                    io.haifa.agent.runtime.core.model.continuation
                                                                    .ModelContinuationException
                                                            continuationFailure
                                            ? continuationFailure.failure().name()
                                            : "MODEL_CALL_FAILED",
                    elapsedMillis(startedAt),
                    failure instanceof ModelInvocationException modelFailure ? modelFailure : null);
            if (cancelled) {
                if (stopSignal == RunControlSignal.CANCEL || stopSignal == RunControlSignal.TIMEOUT) {
                    throw new CancellationObservedException(controls.directive(run.id()));
                }
                throw new CancellationObservedException(stopSignal);
            }
            throw failure;
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
            long cachedInputTokens,
            String finishReason,
            String reasonCode,
            long durationMillis,
            ModelInvocationException failure) {
        appendLifecycle(
                binding,
                run,
                callId,
                requestId,
                iteration,
                attempt,
                type,
                status,
                inputTokens,
                outputTokens,
                cachedInputTokens,
                finishReason,
                reasonCode,
                durationMillis,
                failure,
                Map.of());
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
            long cachedInputTokens,
            String finishReason,
            String reasonCode,
            long durationMillis,
            ModelInvocationException failure,
            Map<String, Object> diagnostics) {
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
        data.put("cachedInputTokens", cachedInputTokens);
        data.put("finishReason", finishReason);
        data.put("reasonCode", reasonCode);
        data.put("durationMillis", durationMillis);
        diagnostics.forEach((key, value) -> {
            if (data.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("model lifecycle diagnostic conflicts with a stable field: " + key);
            }
        });
        if (failure != null) {
            data.put("providerCode", failure.providerCode());
            data.put("retryable", failure.retryable());
            data.put("outputObserved", failure.outputObserved());
            data.put("retryAfterMillis", failure.retryAfterMillis().orElse(0L));
            if (failure.httpStatus() > 0) {
                data.put("httpStatus", failure.httpStatus());
            }
            data.put("retryDecision", failure.retryDecision());
            failure.providerRequestId().ifPresent(id -> data.put("providerRequestId", id));
            failure.responseLimit().ifPresent(limit -> {
                data.put("limitKind", limit.limitKind().name());
                data.put("limitBytes", limit.limitBytes());
                data.put("observedBytes", limit.observedBytes());
                data.put("attempt", limit.attempt());
            });
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
