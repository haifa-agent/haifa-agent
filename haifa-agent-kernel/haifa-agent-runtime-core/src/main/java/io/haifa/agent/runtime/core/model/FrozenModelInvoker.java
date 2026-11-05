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
import io.haifa.agent.runtime.core.skill.SkillToolProvider;
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
    private io.haifa.agent.runtime.core.loop.RunWorkingSetSource workingSets;

    public FrozenModelInvoker workingSets(io.haifa.agent.runtime.core.loop.RunWorkingSetSource value) {
        workingSets = value;
        return this;
    }

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
    private final io.haifa.agent.runtime.core.history.HistoryToolProvider history;

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
        this(
                state,
                adapters,
                ids,
                output,
                controls,
                events,
                time,
                imageResolver,
                audioResolver,
                activeContexts,
                childDefinitions,
                new ModelMessageProjectionPlanner(state));
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
            ActiveContextSnapshots activeContexts,
            DefinitionResolver childDefinitions,
            ModelMessageProjectionPlanner projectionPlanner) {
        this(
                state,
                adapters,
                ids,
                output,
                controls,
                events,
                time,
                imageResolver,
                audioResolver,
                activeContexts,
                childDefinitions,
                projectionPlanner,
                null);
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
            ActiveContextSnapshots activeContexts,
            DefinitionResolver childDefinitions,
            ModelMessageProjectionPlanner projectionPlanner,
            io.haifa.agent.runtime.core.history.HistoryToolProvider history) {
        this.childDefinitions = childDefinitions;
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.adapters = Map.copyOf(Objects.requireNonNull(adapters, "adapters must not be null"));
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.history = history;
        this.messages = new ModelMessageAssembler(
                        state,
                        imageResolver,
                        audioResolver,
                        Objects.requireNonNull(projectionPlanner, "projectionPlanner must not be null"),
                        activeContexts,
                        history == null ? (run, call) -> call : history::sanitize)
                .historicalData(history);
        this.responses = new AgentChatResponseMapper(ids);
        this.output = Objects.requireNonNull(output, "output must not be null");
        this.controls = Objects.requireNonNull(controls, "controls must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
        this.time = Objects.requireNonNull(time, "time must not be null");
    }

    private Map<String, Object> accountingSelection(AgentRun run, Map<String, Object> existing) {
        if (workingSets == null || !workingSets.enabled(run)) return existing;
        var values = new java.util.LinkedHashMap<>(existing);
        values.putAll(workingSets.evidence(run.id()));
        return Map.copyOf(values);
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

    /**
     * Returns the frozen built-in Skill reader specification when the current request hid it only because no Skill was
     * activated yet. A model that follows earlier Run history may still name the reader; the existing activation guard
     * then rejects it as a normal {@code SKILL_NOT_ACTIVATED} Tool failure instead of an invalid model response.
     *
     * <p>The fallback is deliberately narrow: it never applies to requests that disclose no tools at all, never
     * duplicates an already disclosed reader, and only covers the built-in Skill provider alias. Host tools sharing the
     * alias with another provider and other hidden tools keep their existing undisclosed behavior.
     */
    static List<ModelToolSpecification> repairableSkillReadTools(
            List<FrozenToolBinding> frozenBindings,
            List<ModelToolSpecification> frozenTools,
            List<ModelToolSpecification> disclosedTools) {
        if (disclosedTools.isEmpty()) {
            return List.of();
        }
        if (disclosedTools.stream()
                .anyMatch(tool -> tool.name().equals(SkillToolProvider.RESOURCE_READ_ALIAS.value()))) {
            return List.of();
        }
        boolean builtInReader = frozenBindings.stream()
                .anyMatch(binding -> binding.alias().equals(SkillToolProvider.RESOURCE_READ_ALIAS)
                        && binding.definition().providerId().equals(SkillToolProvider.PROVIDER_ID));
        if (!builtInReader) {
            return List.of();
        }
        return frozenTools.stream()
                .filter(tool -> tool.name().equals(SkillToolProvider.RESOURCE_READ_ALIAS.value()))
                .toList();
    }

    public PreparedModelInput prepare(FrozenModelBinding binding, AgentRun run, AgentContext context) {
        if (history != null) history.requireCurrentScope(run);
        var assembled = messages.assembleWithMetrics(
                run, context, binding.configuration().model());
        var schema = binding.configuration()
                .structuredOutput()
                .map(value -> value.jsonSchema())
                .orElse(Map.of());
        var estimate = ModelInputAccounting.admissionEstimate(
                binding.configuration().model(), assembled.messages(), context.tools(), schema);
        long fixed = assembled.messages().stream()
                        .limit(context.prompts().size())
                        .mapToLong(message -> ModelInputAccounting.visibleTokens(message) + 6L)
                        .sum()
                + context.tools().stream()
                        .mapToLong(new io.haifa.agent.context.budget.HeuristicTokenEstimator()::estimate)
                        .sum()
                + (schema.isEmpty() ? 0L : io.haifa.agent.context.budget.HeuristicTokenEstimator.tokens(schema));
        long other = assembled.sources().getOrDefault("memory", 0L)
                + assembled.sources().getOrDefault("runtimeState", 0L)
                + context.items().stream()
                                .filter(item -> item.content()
                                                instanceof io.haifa.agent.context.item.MemoryReferenceContent
                                        || item.content() instanceof io.haifa.agent.context.item.TextContextContent)
                                .count()
                        * 6L;
        return new PreparedModelInput(
                context,
                assembled,
                estimate.totalTokens(),
                fixed,
                Math.min(other, Math.max(0L, estimate.totalTokens() - fixed)));
    }

    public ModelInvocationResult invoke(
            FrozenModelBinding binding,
            AgentRun run,
            int iteration,
            io.haifa.agent.runtime.core.loop.RuntimeContextBuildResult built,
            ModelRequestId requestId,
            int physicalAttempt) {
        return invoke(
                binding,
                run,
                iteration,
                built.context().context(),
                requestId,
                physicalAttempt,
                built.context().context().tools(),
                built.preparedInput());
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
            io.haifa.agent.runtime.core.loop.RuntimeContextBuildResult built,
            ModelRequestId requestId,
            int physicalAttempt) {
        return invoke(
                binding,
                run,
                iteration,
                built.context().context(),
                requestId,
                physicalAttempt,
                List.of(),
                built.preparedInput());
    }

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
        return invoke(
                binding,
                run,
                iteration,
                context,
                requestId,
                physicalAttempt,
                disclosedTools,
                java.util.Optional.empty());
    }

    private ModelInvocationResult invoke(
            FrozenModelBinding binding,
            AgentRun run,
            int iteration,
            AgentContext context,
            ModelRequestId requestId,
            int physicalAttempt,
            List<ModelToolSpecification> disclosedTools,
            java.util.Optional<PreparedModelInput> prepared) {
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
        if (history != null) history.requireCurrentScope(run);
        ModelMessageAssembler.AssemblyResult messageAssembly;
        if (prepared.isPresent()) {
            var value = prepared.orElseThrow();
            if (!value.matches(context)) throw new IllegalArgumentException("prepared context changed");
            messages.validateSources(run, context);
            messageAssembly = value.assembly();
        } else {
            messageAssembly = messages.assembleWithMetrics(
                    run, context, binding.configuration().model());
        }
        if (prepared.isPresent() || (workingSets != null && workingSets.unified(run))) {
            var estimate = ModelInputAccounting.admissionEstimate(
                    binding.configuration().model(),
                    messageAssembly.messages(),
                    disclosedTools,
                    binding.configuration()
                            .structuredOutput()
                            .map(schema -> schema.jsonSchema())
                            .orElse(Map.of()));
            long hard = Math.min(
                    context.budget().availableInputTokens(),
                    io.haifa.agent.context.budget.ContextWindowBudget.capacity(
                                    binding.configuration().model().contextWindow(),
                                    context.budget().outputReserve(),
                                    context.budget().safetyMargin())
                            .availableInputTokens());
            if (estimate.totalTokens() > hard)
                throw new io.haifa.agent.context.api.ContextBuildException(
                        io.haifa.agent.context.api.ContextBuildFailure.REQUIRED_CONTEXT_TOO_LARGE,
                        "prepared model input exceeds its hard budget");
        }
        AgentChatRequest request = new AgentChatRequest(
                callId,
                requestId,
                run.id(),
                run.rootRunId().value(),
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
        ModelInputAccounting.record(
                events,
                time,
                binding,
                request,
                "ORDINARY",
                messageAssembly.sources(),
                accountingSelection(
                        run,
                        Map.of(
                                "hardInputLimitTokens", context.budget().availableInputTokens(),
                                "admissionPolicy",
                                        prepared.isPresent() ? RuntimeControlOptions.UNIFIED_CONTEXT_V1 : "legacy",
                                "rawActiveHistoryEstimatedTokens",
                                        messageAssembly.projection().rawActiveTokens(),
                                "projectedActiveHistoryEstimatedTokens",
                                        messageAssembly.projection().projectedActiveTokens(),
                                "savedResultViewCount",
                                        messageAssembly
                                                .projection()
                                                .resultViews()
                                                .size(),
                                "resultRepresentationReason",
                                        messageAssembly
                                                        .projection()
                                                        .resultViews()
                                                        .isEmpty()
                                                ? "UNCHANGED"
                                                : context.estimatedInputTokens()
                                                                        + messageAssembly
                                                                                .projection()
                                                                                .tokensSavedByPruning()
                                                                > context.budget()
                                                                        .availableInputTokens()
                                                        ? "HARD_CAPACITY"
                                                        : "TARGET_PRESSURE",
                                "sourceOccurrences", messageAssembly.sourceOccurrences(),
                                "repeatedSourceOccurrences", messageAssembly.repeatedSourceOccurrences())));
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
            ModelInputAccounting.recordUsage(events, time, binding, request, "ORDINARY", response.usage());
            if (workingSets != null) workingSets.recordRequest(run, request, startedAt.toEpochMilli(), context);
            RunControlSignal completedSignal = controls.signal(run.id());
            if (completedSignal.stopsExecution()) {
                if (completedSignal == RunControlSignal.CANCEL || completedSignal == RunControlSignal.TIMEOUT) {
                    throw new CancellationObservedException(controls.directive(run.id()));
                }
                throw new CancellationObservedException(completedSignal);
            }
            var decision = responses.map(
                    request,
                    response,
                    disclosedTools,
                    repairableSkillReadTools(binding.configuration().toolBindings(), binding.tools(), disclosedTools),
                    binding.configuration().truncatedOutputPolicy());
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
                            Map.entry("reasoningTokens", response.usage().reasoningTokens()),
                            Map.entry("contextProjectionPhase", ModelMessageAssembler.contextPhase(run, context))),
                    callId.value(),
                    physicalAttempt,
                    binding.configuration().model(),
                    response.reasoning(),
                    response.content());
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
