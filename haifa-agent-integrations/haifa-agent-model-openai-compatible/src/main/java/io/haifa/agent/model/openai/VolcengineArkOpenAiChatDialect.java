package io.haifa.agent.model.openai;

import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelErrorCategory;
import io.haifa.agent.model.api.ModelProviderDefinition;
import io.haifa.agent.model.api.ModelReferenceKind;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class VolcengineArkOpenAiChatDialect implements OpenAiCompatibleDialect {
    static final VolcengineArkOpenAiChatDialect INSTANCE = new VolcengineArkOpenAiChatDialect();

    private VolcengineArkOpenAiChatDialect() {}

    @Override
    public String id() {
        return OpenAiCompatibleDialects.VOLCENGINE_ARK;
    }

    @Override
    public String version() {
        return OpenAiCompatibleDialects.VERSION_1;
    }

    @Override
    public void validateProvider(ModelProviderDefinition provider, boolean allowInsecureHttp) {
        validateEndpoint(provider.endpoint(), provider.options(), allowInsecureHttp);
        provider.models()
                .forEach(model ->
                        validateProfile(model.options(), model.capabilities().contains(ModelCapability.REASONING)));
    }

    @Override
    public void validateSnapshot(ResolvedModelSnapshot snapshot, boolean allowInsecureHttp) {
        validateEndpoint(snapshot.endpoint(), snapshot.providerOptions(), allowInsecureHttp);
        validateProfile(snapshot.invocationOptions(), snapshot.capabilities().contains(ModelCapability.REASONING));
    }

    @Override
    public void applyRequest(AgentChatRequest request, Map<String, Object> body) {
        Map<String, Object> options = request.model().invocationOptions();
        String parameter = String.valueOf(options.getOrDefault("token_limit_parameter", "max_tokens"));
        if ("max_completion_tokens".equals(parameter)) {
            Object limit = body.remove("max_tokens");
            body.put("max_completion_tokens", limit);
        }
        String profile = String.valueOf(options.getOrDefault("thinking_profile", "none"));
        boolean thinkingEnabled = booleanOption(options, "thinking_enabled", "always".equals(profile));
        if (!"none".equals(profile)) body.put("thinking", Map.of("type", thinkingEnabled ? "enabled" : "disabled"));
        if (thinkingEnabled && options.containsKey("reasoning_effort")) {
            body.put("reasoning_effort", options.get("reasoning_effort"));
        }
        if (options.containsKey("service_tier")) body.put("service_tier", options.get("service_tier"));
    }

    @Override
    public boolean acceptsResponseObject(String object, boolean streaming) {
        return object.isBlank() || (streaming ? "chat.completion.chunk" : "chat.completion").equals(object);
    }

    @Override
    public ModelErrorCategory classifyError(int status, String providerCode, String safeDetail) {
        String normalized = (providerCode + " " + safeDetail).toLowerCase(Locale.ROOT);
        if (normalized.contains("content")
                && (normalized.contains("filter") || normalized.contains("safety") || normalized.contains("risk"))) {
            return ModelErrorCategory.CONTENT_REJECTED;
        }
        if (normalized.contains("endpoint")
                && (normalized.contains("not found")
                        || normalized.contains("stopped")
                        || normalized.contains("not_found"))) {
            return ModelErrorCategory.MODEL_NOT_FOUND;
        }
        if (normalized.contains("quota") || normalized.contains("rate") || normalized.contains("throttl")) {
            return ModelErrorCategory.RATE_LIMITED;
        }
        return OpenAiCompatibleDialect.super.classifyError(status, providerCode, safeDetail);
    }

    private static void validateEndpoint(
            java.net.URI endpoint, Map<String, Object> providerOptions, boolean allowInsecureHttp) {
        String region = requireText(providerOptions, "region");
        String trustedHost = requireText(providerOptions, "endpoint_host").toLowerCase(Locale.ROOT);
        if (!trustedHost.endsWith(".volces.com") || !trustedHost.startsWith("ark.")) {
            throw new IllegalArgumentException("Ark endpoint_host is not an allowed official host");
        }
        if ("cn-beijing".equals(region) && !"ark.cn-beijing.volces.com".equals(trustedHost)) {
            throw new IllegalArgumentException("Ark Beijing region and endpoint_host do not match");
        }
        OpenAiCompatibleEndpointPolicy.validate(
                endpoint, allowInsecureHttp, Set.of(trustedHost), allowInsecureHttp ? null : "/api/v3");
        if (!allowInsecureHttp && !trustedHost.equalsIgnoreCase(endpoint.getHost())) {
            throw new IllegalArgumentException("Ark endpoint does not match its governed endpoint_host");
        }
    }

    private static void validateProfile(Map<String, Object> options, boolean reasoningCapability) {
        Object kind = options.get("model_reference_kind");
        try {
            ModelReferenceKind.valueOf(String.valueOf(kind));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Ark model_reference_kind must be MODEL_ID or ENDPOINT_ID", exception);
        }
        String profile = String.valueOf(options.getOrDefault("thinking_profile", "none"));
        if (!Set.of("none", "hybrid", "always").contains(profile)) {
            throw new IllegalArgumentException("unsupported Ark thinking profile: " + profile);
        }
        boolean thinkingEnabled = booleanOption(options, "thinking_enabled", "always".equals(profile));
        if (thinkingEnabled && (!reasoningCapability || "none".equals(profile))) {
            throw new IllegalArgumentException("Ark model profile does not support thinking");
        }
        if (!thinkingEnabled && "always".equals(profile)) {
            throw new IllegalArgumentException("Ark thinking-only model cannot disable thinking");
        }
        if (booleanOption(options, "requires_reasoning_continuation", false) && !thinkingEnabled) {
            throw new IllegalArgumentException("Ark reasoning continuation requires enabled thinking");
        }
        Object effort = options.get("reasoning_effort");
        if (effort != null && !Set.of("low", "medium", "high").contains(String.valueOf(effort))) {
            throw new IllegalArgumentException("unsupported Ark reasoning_effort");
        }
        Object parameter = options.get("token_limit_parameter");
        if (parameter != null && !Set.of("max_tokens", "max_completion_tokens").contains(String.valueOf(parameter))) {
            throw new IllegalArgumentException("unsupported Ark token limit parameter");
        }
        Object serviceTier = options.get("service_tier");
        if (serviceTier != null && !Set.of("auto", "default", "flex").contains(String.valueOf(serviceTier))) {
            throw new IllegalArgumentException("unsupported Ark service_tier");
        }
    }

    private static String requireText(Map<String, Object> options, String key) {
        Object value = options.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Ark " + key + " must be frozen");
        }
        return text;
    }

    private static boolean booleanOption(Map<String, Object> options, String key, boolean fallback) {
        Object value = options.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Boolean booleanValue)) throw new IllegalArgumentException(key + " must be boolean");
        return booleanValue;
    }
}
