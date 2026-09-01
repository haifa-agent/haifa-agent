package io.haifa.agent.model.openai.responses;

import com.fasterxml.jackson.databind.JsonNode;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.ModelMessage;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.util.Map;
import java.util.Optional;

interface OpenAiResponsesDialect {
    String id();

    String version();

    void validateSnapshot(ResolvedModelSnapshot snapshot, boolean allowInsecureHttp);

    default void validateRequest(AgentChatRequest request) {}

    default void decorateHeaders(
            HttpRequest.Builder builder,
            AgentChatRequest request,
            String credentialSecret,
            CodexAccountIdentityResolver codexResolver) {
        String secret = OpenAiCodexAuthentication.validateHeaderValue(credentialSecret, "model credential");
        builder.header("Authorization", "Bearer " + secret);
    }

    default void customizeRequestBody(AgentChatRequest request, Map<String, Object> body) {
        body.put("max_output_tokens", request.maxOutputTokens());
    }

    default void validateToolChoice(Object toolChoice) {}

    default Optional<Map<String, Object>> customizeReasoningInputItem(ModelMessage message) {
        throw new IllegalArgumentException(
                "standard Responses reasoning continuation requires a protected opaque item");
    }

    default void validateEventSequence(JsonNode event) {}

    default boolean allowsEmptyContentType() {
        return false;
    }

    default boolean preservesReasoningContent() {
        return false;
    }

    default DialectErrorMapping classifyError(int statusCode, HttpHeaders headers, byte[] body, JsonNode errorRoot) {
        return StandardOpenAiResponsesDialect.INSTANCE.classifyError(statusCode, headers, body, errorRoot);
    }
}
