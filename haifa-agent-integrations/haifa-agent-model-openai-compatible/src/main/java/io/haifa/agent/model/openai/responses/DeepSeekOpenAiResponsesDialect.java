package io.haifa.agent.model.openai.responses;

import com.fasterxml.jackson.databind.JsonNode;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelMessage;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class DeepSeekOpenAiResponsesDialect implements OpenAiResponsesDialect {
    static final DeepSeekOpenAiResponsesDialect INSTANCE = new DeepSeekOpenAiResponsesDialect();

    private DeepSeekOpenAiResponsesDialect() {}

    @Override
    public String id() {
        return OpenAiResponsesDialects.DEEPSEEK;
    }

    @Override
    public String version() {
        return "2026-08-31";
    }

    @Override
    public void validateSnapshot(ResolvedModelSnapshot snapshot, boolean allowInsecureHttp) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        URI endpoint = snapshot.endpoint();
        OpenAiResponsesDialectSupport.validateEndpoint(endpoint, allowInsecureHttp);
        if (!allowInsecureHttp && !OpenAiResponsesDialectSupport.isLoopback(endpoint)) {
            if (!"https".equalsIgnoreCase(endpoint.getScheme())
                    || !"api.deepseek.com".equalsIgnoreCase(endpoint.getHost())) {
                throw new IllegalArgumentException("DeepSeek Responses endpoint must be https://api.deepseek.com");
            }
        }
        if (!OpenAiResponsesBindingRegistry.isAdmitted(
                snapshot.providerId().value(),
                snapshot.providerModelId(),
                ModelApiStyles.OPENAI_RESPONSES,
                OpenAiResponsesDialects.DEEPSEEK)) {
            throw new IllegalArgumentException("DeepSeek Responses model profile is not verified");
        }
    }

    @Override
    public void validateRequest(AgentChatRequest request) {
        if (request.messages().stream().anyMatch(msg -> !msg.images().isEmpty())) {
            throw new IllegalArgumentException("DeepSeek Responses image input is not verified");
        }
    }

    @Override
    public void customizeRequestBody(AgentChatRequest request, Map<String, Object> body) {
        body.put("max_output_tokens", request.maxOutputTokens());
        body.put("thinking", Map.of("type", "enabled"));
    }

    @Override
    public void validateToolChoice(Object toolChoice) {
        if (toolChoice != null
                && !"auto".equals(toolChoice)
                && !Map.of("type", "auto").equals(toolChoice)) {
            throw new IllegalArgumentException("DeepSeek Responses dialect requires automatic function selection");
        }
    }

    @Override
    public Optional<Map<String, Object>> customizeReasoningInputItem(ModelMessage message) {
        return message.reasoning()
                .map(reasoning ->
                        reasoning.use(content -> Map.<String, Object>of("type", "reasoning", "content", content)));
    }

    @Override
    public void validateEventSequence(JsonNode event) {
        if (event == null || !event.hasNonNull("sequence_number")) {
            throw new IllegalArgumentException("DeepSeek Responses SSE event missing sequence_number");
        }
    }

    @Override
    public boolean preservesReasoningContent() {
        return true;
    }
}
