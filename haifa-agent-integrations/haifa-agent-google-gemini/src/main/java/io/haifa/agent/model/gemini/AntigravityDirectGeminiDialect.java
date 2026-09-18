package io.haifa.agent.model.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.ModelErrorCategory;
import io.haifa.agent.model.api.ResolvedCredential;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class AntigravityDirectGeminiDialect implements GeminiDialect {
    static final AntigravityDirectGeminiDialect INSTANCE = new AntigravityDirectGeminiDialect();

    private AntigravityDirectGeminiDialect() {}

    @Override
    public String id() {
        return GeminiDialects.ANTIGRAVITY_DIRECT;
    }

    @Override
    public String version() {
        return "2026-08-31";
    }

    @Override
    public void validateSnapshot(
            ResolvedModelSnapshot snapshot, boolean allowInsecureLoopback, boolean allowStandardLoopbackStub) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        URI endpoint = snapshot.endpoint();
        GeminiDialectSupport.validateEndpoint(endpoint, allowInsecureLoopback || allowStandardLoopbackStub);
        if (!allowInsecureLoopback
                && !allowStandardLoopbackStub
                && !GeminiDialectSupport.isGovernedAntigravityDirectEndpoint(endpoint)) {
            throw new DialectValidationException(
                    "invalid_antigravity_direct_endpoint",
                    "official Antigravity direct binding requires the governed CloudCode PA API endpoint");
        }
        if (!"model-auth://google-antigravity/default"
                .equals(snapshot.credentialRef().value())) {
            throw new DialectValidationException(
                    "invalid_antigravity_binding",
                    "official Antigravity direct binding requires the governed credential reference model-auth://google-antigravity/default");
        }
    }

    @Override
    public HttpRequest.Builder requestBuilder(
            AgentChatRequest request, ResolvedCredential credential, boolean streaming) {
        String secret = GeminiDialectSupport.validateSecret(credential.value());
        URI uri =
                GeminiDialectSupport.antigravityDirectRequestUri(request.model().endpoint(), streaming);
        return HttpRequest.newBuilder(uri)
                .timeout(request.timeout())
                .header("Content-Type", "application/json")
                .header("User-Agent", "Antigravity")
                .header("Authorization", "Bearer " + secret);
    }

    @Override
    public Map<String, Object> customizeRequestBody(
            AgentChatRequest request,
            Map<String, Object> innerBody,
            AntigravityCloudCodeProjectResolver projectResolver) {
        if (request.model().providerOptions().containsKey("project")
                || request.options().containsKey("project")) {
            throw new DialectValidationException(
                    "project_injection_forbidden",
                    "Antigravity project injection via request or model options is forbidden");
        }
        String project = projectResolver
                .resolveProject(request.model().credentialRef())
                .orElseThrow(() -> new DialectValidationException(
                        "antigravity_project_unavailable",
                        ModelErrorCategory.AUTHENTICATION_FAILED,
                        "trusted Antigravity project is unavailable for the selected credential"));
        if (!project.matches("[a-z0-9-]{6,30}")) {
            throw new DialectValidationException(
                    "antigravity_project_invalid",
                    ModelErrorCategory.AUTHENTICATION_FAILED,
                    "trusted Antigravity project is invalid");
        }

        stripMaxOutputTokens(innerBody);
        innerBody.put("sessionId", sessionId(request));

        Map<String, Object> outerBody = new LinkedHashMap<>();
        outerBody.put("project", project);
        outerBody.put("model", request.model().providerModelId());
        outerBody.put("userAgent", "antigravity");
        outerBody.put("requestType", "agent");
        outerBody.put("requestId", "agent-req-" + UUID.randomUUID());
        outerBody.put("request", innerBody);
        return outerBody;
    }

    @SuppressWarnings("unchecked")
    private static void stripMaxOutputTokens(Map<String, Object> body) {
        Object configObj = body.get("generationConfig");
        if (configObj instanceof Map<?, ?> configMap) {
            Map<String, Object> mutableConfig = new LinkedHashMap<>((Map<String, Object>) configMap);
            mutableConfig.remove("maxOutputTokens");
            body.put("generationConfig", mutableConfig);
        }
    }

    static boolean isGovernedAntigravityDirectEndpoint(URI endpoint) {
        return GeminiDialectSupport.isGovernedAntigravityDirectEndpoint(endpoint);
    }

    private static String sessionId(AgentChatRequest request) {
        long hash = Math.abs(
                (long) Objects.hash(request.runId().value(), request.callId().value()));
        return "-" + (hash == 0 ? 1 : hash);
    }

    @Override
    public JsonNode unwrapResponsePayload(AgentChatRequest request, JsonNode root) {
        if (root == null
                || !root.hasNonNull("response")
                || !root.get("response").isObject()) {
            throw new DialectValidationException(
                    "invalid_antigravity_response_envelope",
                    ModelErrorCategory.MALFORMED_RESPONSE,
                    "Antigravity response envelope has no response object");
        }
        return root.get("response");
    }
}
