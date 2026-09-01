package io.haifa.agent.model.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.util.Map;

interface AnthropicMessagesDialect {
    String id();

    String version();

    void validateSnapshot(ResolvedModelSnapshot snapshot, boolean allowInsecureHttp);

    default void validateRequest(AgentChatRequest request) {}

    default void decorateHeaders(HttpRequest.Builder builder, AgentChatRequest request, String credentialSecret) {
        builder.header("x-api-key", validateSecret(credentialSecret));
    }

    default void configureThinking(Map<String, Object> body, Map<String, Object> options) {
        StandardAnthropicMessagesDialect.INSTANCE.configureThinking(body, options);
    }

    default void validateContentBlock(String type, JsonNode block) {}

    default DialectErrorMapping classifyError(int statusCode, HttpHeaders headers, byte[] body, JsonNode errorRoot) {
        return StandardAnthropicMessagesDialect.INSTANCE.classifyError(statusCode, headers, body, errorRoot);
    }

    private static String validateSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("model credential value must not be blank");
        }
        if (secret.indexOf('\r') >= 0 || secret.indexOf('\n') >= 0 || secret.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("model credential contains invalid control characters");
        }
        return secret.trim();
    }
}
