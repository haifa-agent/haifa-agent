package io.haifa.agent.runtime.core.model;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.context.api.AgentContext;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelCallId;
import io.haifa.agent.model.api.ModelStreamControl;
import io.haifa.agent.model.api.ModelStreamEvent;
import io.haifa.agent.model.api.ModelToolSpecification;
import io.haifa.agent.runtime.core.bootstrap.RuntimeConfigurationSnapshot;
import io.haifa.agent.runtime.core.control.RunControlRegistry;
import io.haifa.agent.runtime.core.control.RunControlSignal;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import io.haifa.agent.tool.api.FrozenToolBinding;
import java.time.Duration;
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

    public FrozenModelInvoker(
            RuntimeStateRepository state,
            Map<ModelAdapterKey, AgentChatModel> adapters,
            IdentifierGenerator ids,
            RuntimeModelOutputPublisher output,
            RunControlRegistry controls) {
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.adapters = Map.copyOf(Objects.requireNonNull(adapters, "adapters must not be null"));
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.messages = new ModelMessageAssembler(state);
        this.responses = new AgentChatResponseMapper(ids);
        this.output = Objects.requireNonNull(output, "output must not be null");
        this.controls = Objects.requireNonNull(controls, "controls must not be null");
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
        if (!binding.configuration().reference().equals(run.configurationSnapshot())) {
            throw new IllegalArgumentException("model binding belongs to another configuration snapshot");
        }
        ModelCallId callId = new ModelCallId(ids.nextValue());
        int attempt = Math.max(
                1, Math.toIntExact(Math.min(Integer.MAX_VALUE, run.usage().modelCalls())));
        AgentChatRequest request = new AgentChatRequest(
                callId,
                run.id(),
                iteration,
                attempt,
                binding.configuration().model(),
                messages.assemble(run.id(), context),
                context.tools(),
                Math.toIntExact(context.budget().outputReserve()),
                Duration.ofMillis(Math.max(1, run.limits().maxIdleTimeMillis())),
                Map.of());
        output.started(run.id(), callId.value(), attempt, iteration);
        AgentChatResponse response;
        try {
            response = binding.chatModel().invokeStreaming(request, event -> {
                if (controls.signal(run.id()) != RunControlSignal.NONE) return ModelStreamControl.CANCEL;
                if (event instanceof ModelStreamEvent.ContentDelta content) {
                    output.content(run.id(), callId.value(), attempt, content.delta());
                }
                return ModelStreamControl.CONTINUE;
            });
            output.committed(run.id(), callId.value(), attempt, iteration);
        } catch (RuntimeException exception) {
            output.failed(run.id(), callId.value(), attempt, iteration);
            throw exception;
        }
        var decision = responses.map(request, response, binding.tools());
        return new ModelInvocationResult(
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
                                "modelVersion", binding.configuration().model().modelVersion()),
                        Map.entry(
                                "adapterVersion",
                                binding.configuration().model().adapterVersion()),
                        Map.entry("modelCallId", callId.value()),
                        Map.entry("responseId", response.responseId()),
                        Map.entry("finishReason", response.finishReason().name()),
                        Map.entry("cacheHitTokens", response.usage().cacheHitTokens()),
                        Map.entry("cacheMissTokens", response.usage().cacheMissTokens()),
                        Map.entry("reasoningTokens", response.usage().reasoningTokens())));
    }
}
