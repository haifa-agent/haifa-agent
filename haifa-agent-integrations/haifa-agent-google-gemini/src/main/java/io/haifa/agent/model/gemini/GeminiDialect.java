package io.haifa.agent.model.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.ResolvedCredential;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.util.Map;

interface GeminiDialect {
    String id();

    String version();

    void validateSnapshot(
            ResolvedModelSnapshot snapshot, boolean allowInsecureLoopback, boolean allowStandardLoopbackStub);

    default void validateRequest(AgentChatRequest request) {}

    HttpRequest.Builder requestBuilder(AgentChatRequest request, ResolvedCredential credential, boolean streaming);

    default Map<String, Object> customizeRequestBody(
            AgentChatRequest request,
            Map<String, Object> innerBody,
            AntigravityCloudCodeProjectResolver projectResolver) {
        return innerBody;
    }

    default JsonNode unwrapResponsePayload(AgentChatRequest request, JsonNode root) {
        return root;
    }

    default DialectErrorMapping classifyError(int statusCode, HttpHeaders headers, byte[] body, JsonNode errorRoot) {
        return GeminiDialectSupport.classifyGeminiError(statusCode, headers, body, errorRoot);
    }
}
