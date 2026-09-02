package io.haifa.agent.runtime.core.model;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.core.run.AgentRunOutcome;
import io.haifa.agent.core.tool.RuntimeIdempotencyKey;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelErrorCategory;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelInvocationException;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelToolSpecification;
import io.haifa.agent.runtime.core.decision.AgentDecision;
import io.haifa.agent.runtime.core.decision.FinalAnswerDecision;
import io.haifa.agent.runtime.core.decision.ToolCallDecision;
import io.haifa.agent.runtime.core.decision.ToolRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Maps Model API responses into Runtime decisions while allocating Runtime-owned identifiers. */
public final class AgentChatResponseMapper {
    private final IdentifierGenerator ids;

    public AgentChatResponseMapper(IdentifierGenerator ids) {
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
    }

    public AgentDecision map(
            AgentChatRequest request, AgentChatResponse response, List<ModelToolSpecification> disclosedTools) {
        if (response.content().isBlank()
                && response.toolCalls().isEmpty()
                && response.structuredOutput().isEmpty()) {
            throw new ModelInvocationException(
                    ModelErrorCategory.EMPTY_RESPONSE,
                    true,
                    200,
                    "empty_response",
                    request.callId(),
                    "model returned no usable output",
                    null);
        }
        if (!response.toolCalls().isEmpty()) {
            Map<String, ModelToolSpecification> byName = new LinkedHashMap<>();
            disclosedTools.forEach(tool -> byName.put(tool.name(), tool));
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
        if (request.structuredOutput().isPresent()) {
            var requirement = request.structuredOutput().orElseThrow();
            Map<String, Object> output = response.structuredOutput()
                    .orElseThrow(() -> new ModelInvocationException(
                            ModelErrorCategory.MALFORMED_RESPONSE,
                            false,
                            200,
                            "structured_output_invalid",
                            request.callId(),
                            "model did not return a structured final output",
                            null));
            return new FinalAnswerDecision(
                    AgentRunOutcome.SUCCESS,
                    response.content(),
                    requirement.schemaId(),
                    requirement.schemaVersion(),
                    output,
                    List.of(),
                    List.of());
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
                    true,
                    200,
                    "undisclosed_tool",
                    request.callId(),
                    "model requested a tool that was not disclosed",
                    null);
        }
        return new ToolRequest(
                new ToolCallId(ids.nextValue()),
                call.providerCorrelationId(),
                new RuntimeIdempotencyKey(ids.nextValue()),
                specification.name(),
                specification.version(),
                new ToolArguments(specification.inputSchemaId(), specification.inputSchemaVersion(), call.arguments()));
    }
}
