package io.haifa.agent.runtime.core.model;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.core.content.ContentPart;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.content.ToolCallPart;
import io.haifa.agent.core.content.ToolResultPart;
import io.haifa.agent.core.message.AgentMessage;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunOutcome;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelCallId;
import io.haifa.agent.model.api.ModelErrorCategory;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelInvocationException;
import io.haifa.agent.model.api.ModelMessage;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelToolSpecification;
import io.haifa.agent.runtime.core.bootstrap.RuntimeConfigurationSnapshot;
import io.haifa.agent.runtime.core.decision.FinalAnswerDecision;
import io.haifa.agent.runtime.core.decision.ToolCallDecision;
import io.haifa.agent.runtime.core.decision.ToolRequest;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Resolves the frozen run model and bridges Runtime messages to the provider-neutral Model API. */
public final class SnapshotModelSelector implements ModelSelector {
    private final RuntimeStateRepository state;
    private final Map<String, AgentChatModel> adapters;
    private final Map<String, ModelToolSpecification> toolSpecifications;
    private final IdentifierGenerator ids;

    public SnapshotModelSelector(
            RuntimeStateRepository state,
            Map<String, AgentChatModel> adapters,
            Map<String, ModelToolSpecification> toolSpecifications,
            IdentifierGenerator ids) {
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.adapters = immutableByTextKey(adapters, "adapter");
        this.toolSpecifications = immutableByTextKey(toolSpecifications, "tool specification");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
    }

    @Override
    public ModelClient select(AgentRun run) {
        Objects.requireNonNull(run, "run must not be null");
        RuntimeConfigurationSnapshot configuration = state.configuration(run.configurationSnapshot())
                .orElseThrow(() -> new IllegalStateException("run configuration snapshot is unavailable"));
        AgentChatModel chatModel = adapters.get(configuration.model().adapterType());
        if (chatModel == null) {
            throw new IllegalStateException(
                    "model adapter is unavailable: " + configuration.model().adapterType());
        }
        return request -> invoke(run, configuration, chatModel, request);
    }

    private ModelResponse invoke(
            AgentRun run, RuntimeConfigurationSnapshot configuration, AgentChatModel chatModel, ModelRequest request) {
        ModelCallId callId = new ModelCallId(ids.nextValue());
        List<ModelToolSpecification> tools = configuration.allowedTools().stream()
                .sorted()
                .map(toolSpecifications::get)
                .filter(Objects::nonNull)
                .toList();
        List<ModelMessage> messages = messages(request);
        int maxOutputTokens = maxOutputTokens(configuration);
        int attempt = Math.max(
                1, Math.toIntExact(Math.min(Integer.MAX_VALUE, run.usage().modelCalls())));
        AgentChatRequest chatRequest = new AgentChatRequest(
                callId,
                run.id(),
                request.iteration(),
                attempt,
                configuration.model(),
                messages,
                tools,
                maxOutputTokens,
                Duration.ofMillis(Math.max(1, run.limits().maxIdleTimeMillis())),
                Map.of());
        AgentChatResponse response = chatModel.invoke(chatRequest);
        var decision = decision(chatRequest, response, tools);
        return new ModelResponse(
                decision,
                response.usage().inputTokens(),
                response.usage().outputTokens(),
                response.usage().costKnown(),
                response.usage().costMinorUnits(),
                Map.of(
                        "providerId", configuration.model().providerId().value(),
                        "modelId", configuration.model().modelId().value(),
                        "adapterVersion", configuration.model().adapterVersion(),
                        "modelCallId", callId.value(),
                        "responseId", response.responseId(),
                        "finishReason", response.finishReason().name(),
                        "cacheHitTokens", response.usage().cacheHitTokens(),
                        "cacheMissTokens", response.usage().cacheMissTokens(),
                        "reasoningTokens", response.usage().reasoningTokens()));
    }

    private List<ModelMessage> messages(ModelRequest request) {
        List<ModelMessage> result = new ArrayList<>();
        addSystemAttribute(result, request.attributes(), "agent.instruction");
        addSystemAttribute(result, request.attributes(), "safety.instruction");
        Object convergence = request.attributes().get("runtime.convergenceRequired");
        if (convergence != null) {
            result.add(ModelMessage.text(ModelMessageRole.SYSTEM, "Completion requirements: " + convergence));
        }
        for (AgentMessage message : request.messages()) result.addAll(mapMessage(message));
        if (result.isEmpty()) throw new IllegalArgumentException("model context must not be empty");
        return List.copyOf(result);
    }

    private void addSystemAttribute(List<ModelMessage> result, Map<String, Object> attributes, String key) {
        Object value = attributes.get(key);
        if (value instanceof String text && !text.isBlank()) {
            result.add(ModelMessage.text(ModelMessageRole.SYSTEM, text));
        }
    }

    private List<ModelMessage> mapMessage(AgentMessage message) {
        List<ToolCallPart> calls = message.contents().stream()
                .filter(ToolCallPart.class::isInstance)
                .map(ToolCallPart.class::cast)
                .toList();
        List<ToolResultPart> results = message.contents().stream()
                .filter(ToolResultPart.class::isInstance)
                .map(ToolResultPart.class::cast)
                .toList();
        String text = renderText(message.contents());
        if (!results.isEmpty()) {
            return results.stream()
                    .map(result ->
                            new ModelMessage(ModelMessageRole.TOOL, result.text(), List.of(), result.toolCallId()))
                    .toList();
        }
        if (!calls.isEmpty()) {
            List<ModelToolCall> mapped = calls.stream()
                    .map(call -> new ModelToolCall(
                            call.toolCallId(), call.toolName(), call.arguments().values()))
                    .toList();
            return List.of(new ModelMessage(ModelMessageRole.ASSISTANT, text, mapped, ""));
        }
        if (message.role() == MessageRole.TOOL) {
            return List.of(ModelMessage.text(ModelMessageRole.SYSTEM, "Uncorrelated historical tool result: " + text));
        }
        return List.of(ModelMessage.text(mapRole(message.role()), text));
    }

    private String renderText(List<ContentPart> contents) {
        List<String> values = new ArrayList<>();
        for (ContentPart content : contents) {
            if (content instanceof TextPart text) values.add(text.text());
            else if (!(content instanceof ToolCallPart) && !(content instanceof ToolResultPart)) {
                values.add("[" + content.contentType() + " content]");
            }
        }
        return String.join("\n", values).trim();
    }

    private ModelMessageRole mapRole(MessageRole role) {
        return switch (role) {
            case USER -> ModelMessageRole.USER;
            case ASSISTANT -> ModelMessageRole.ASSISTANT;
            case TOOL -> ModelMessageRole.TOOL;
            case SYSTEM, DEVELOPER, AGENT, RUNTIME -> ModelMessageRole.SYSTEM;
        };
    }

    private io.haifa.agent.runtime.core.decision.AgentDecision decision(
            AgentChatRequest request, AgentChatResponse response, List<ModelToolSpecification> tools) {
        if (!response.toolCalls().isEmpty()) {
            Map<String, ModelToolSpecification> byName = new LinkedHashMap<>();
            tools.forEach(tool -> byName.put(tool.name(), tool));
            List<ToolRequest> requests = response.toolCalls().stream()
                    .map(call -> toolRequest(request, call, byName.get(call.name())))
                    .toList();
            return new ToolCallDecision(requests);
        }
        if (response.finishReason() == ModelFinishReason.LENGTH) {
            throw new ModelInvocationException(
                    ModelErrorCategory.MALFORMED_RESPONSE,
                    false,
                    200,
                    "output_truncated",
                    request.callId(),
                    "model output was truncated before completion",
                    null);
        }
        if (response.finishReason() == ModelFinishReason.UNKNOWN) {
            throw new ModelInvocationException(
                    ModelErrorCategory.UNKNOWN_PROVIDER_ERROR,
                    false,
                    200,
                    "unknown_finish_reason",
                    request.callId(),
                    "model returned an unknown finish reason",
                    null);
        }
        return new FinalAnswerDecision(
                AgentRunOutcome.SUCCESS,
                response.content(),
                "haifa.agent.final-answer",
                "1.0",
                Map.of("answer", response.content()),
                List.of(),
                List.of());
    }

    private ToolRequest toolRequest(
            AgentChatRequest request, ModelToolCall call, ModelToolSpecification specification) {
        if (specification == null) {
            throw new ModelInvocationException(
                    ModelErrorCategory.MALFORMED_RESPONSE,
                    false,
                    200,
                    "undisclosed_tool",
                    request.callId(),
                    "model requested a tool that was not disclosed",
                    null);
        }
        return new ToolRequest(
                specification.name(),
                specification.version(),
                new ToolArguments(specification.inputSchemaId(), specification.inputSchemaVersion(), call.arguments()),
                call.id());
    }

    private int maxOutputTokens(RuntimeConfigurationSnapshot configuration) {
        Object configured = configuration.model().invocationOptions().get("maxOutputTokens");
        if (configured == null) return 8192;
        if (!(configured instanceof Number number)
                || number.longValue() < 1
                || number.longValue() > Integer.MAX_VALUE) {
            throw new IllegalStateException("frozen maxOutputTokens is invalid");
        }
        return number.intValue();
    }

    private static <T> Map<String, T> immutableByTextKey(Map<String, T> source, String valueName) {
        Objects.requireNonNull(source, valueName + "s must not be null");
        LinkedHashMap<String, T> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            String normalized =
                    Objects.requireNonNull(key, "key must not be null").trim();
            if (normalized.isEmpty()) throw new IllegalArgumentException("key must not be blank");
            if (copy.putIfAbsent(normalized, Objects.requireNonNull(value, valueName + " must not be null")) != null) {
                throw new IllegalArgumentException("duplicate key: " + normalized.toLowerCase(Locale.ROOT));
            }
        });
        return Map.copyOf(copy);
    }
}
