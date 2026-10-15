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
import io.haifa.agent.runtime.api.TruncatedOutputPolicy;
import io.haifa.agent.runtime.core.decision.AgentDecision;
import io.haifa.agent.runtime.core.decision.DelegationDecision;
import io.haifa.agent.runtime.core.decision.FinalAnswerDecision;
import io.haifa.agent.runtime.core.decision.ToolCallDecision;
import io.haifa.agent.runtime.core.decision.ToolRequest;
import io.haifa.agent.runtime.core.delegation.DelegationTool;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Maps Model API responses into Runtime decisions while allocating Runtime-owned identifiers. */
public final class AgentChatResponseMapper {
    public static final String TRUNCATED_LENGTH_WARNING = "TRUNCATED:LENGTH";

    private final IdentifierGenerator ids;

    public AgentChatResponseMapper(IdentifierGenerator ids) {
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
    }

    public AgentDecision map(
            AgentChatRequest request, AgentChatResponse response, List<ModelToolSpecification> disclosedTools) {
        return map(request, response, disclosedTools, List.of(), TruncatedOutputPolicy.FAIL_CLOSED);
    }

    public AgentDecision map(
            AgentChatRequest request,
            AgentChatResponse response,
            List<ModelToolSpecification> disclosedTools,
            TruncatedOutputPolicy policy) {
        return map(request, response, disclosedTools, List.of(), policy);
    }

    /**
     * Maps a response using the tools disclosed to the model plus a fallback source of frozen tools that the model may
     * legitimately name even though this particular request hid them (for example the built-in Skill reader withheld
     * before activation). The disclosed list always wins; the fallback only prevents a valid frozen tool from being
     * classified as an undisclosed response.
     */
    AgentDecision map(
            AgentChatRequest request,
            AgentChatResponse response,
            List<ModelToolSpecification> disclosedTools,
            List<ModelToolSpecification> fallbackTools) {
        return map(request, response, disclosedTools, fallbackTools, TruncatedOutputPolicy.FAIL_CLOSED);
    }

    AgentDecision map(
            AgentChatRequest request,
            AgentChatResponse response,
            List<ModelToolSpecification> disclosedTools,
            List<ModelToolSpecification> fallbackTools,
            TruncatedOutputPolicy policy) {
        Objects.requireNonNull(policy, "policy must not be null");
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
        if (policy == TruncatedOutputPolicy.ACCEPT_NONEMPTY_PLAIN_TEXT) {
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
            if (response.finishReason() == ModelFinishReason.CONTENT_FILTER) {
                throw new ModelInvocationException(
                        ModelErrorCategory.CONTENT_REJECTED,
                        false,
                        200,
                        "content_filter",
                        request.callId(),
                        "model response was rejected by content safety filters",
                        null);
            }
            if (response.finishReason() == ModelFinishReason.LENGTH) {
                if (!response.toolCalls().isEmpty()) {
                    throw new ModelInvocationException(
                            ModelErrorCategory.MALFORMED_RESPONSE,
                            false,
                            200,
                            "output_truncated",
                            request.callId(),
                            "model output was truncated during tool calling",
                            null);
                }
                if (request.structuredOutput().isPresent()
                        || response.structuredOutput().isPresent()) {
                    throw new ModelInvocationException(
                            ModelErrorCategory.MALFORMED_RESPONSE,
                            false,
                            200,
                            "output_truncated",
                            request.callId(),
                            "model output was truncated before structured output completion",
                            null);
                }
                if (response.content().isBlank()) {
                    throw new ModelInvocationException(
                            ModelErrorCategory.EMPTY_RESPONSE,
                            true,
                            200,
                            "empty_response",
                            request.callId(),
                            "model returned no usable output",
                            null);
                }
                return new FinalAnswerDecision(
                        AgentRunOutcome.SUCCESS,
                        response.content(),
                        "haifa.agent.final-answer",
                        "1.0",
                        Map.of("answer", response.content()),
                        List.of(),
                        List.of(TRUNCATED_LENGTH_WARNING));
            }
        }
        if (!response.toolCalls().isEmpty()) {
            Map<String, ModelToolSpecification> byName = new LinkedHashMap<>();
            disclosedTools.forEach(tool -> byName.put(tool.name(), tool));
            fallbackTools.forEach(tool -> byName.putIfAbsent(tool.name(), tool));
            List<ToolRequest> requests = response.toolCalls().stream()
                    .map(call -> toolRequest(request, call, byName.get(call.name())))
                    .toList();
            if (requests.stream().anyMatch(DelegationTool::isDelegation)) return new DelegationDecision(requests);
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
