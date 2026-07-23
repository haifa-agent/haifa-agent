package io.haifa.agent.model.openai;

import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelErrorCategory;
import io.haifa.agent.model.api.ModelProviderDefinition;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class AliyunBailianOpenAiChatDialect implements OpenAiCompatibleDialect {
    static final AliyunBailianOpenAiChatDialect INSTANCE = new AliyunBailianOpenAiChatDialect();
    private static final Pattern DNS_LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");

    private AliyunBailianOpenAiChatDialect() {}

    @Override
    public String id() {
        return OpenAiCompatibleDialects.ALIYUN_BAILIAN;
    }

    @Override
    public String version() {
        return OpenAiCompatibleDialects.VERSION_1;
    }

    @Override
    public void validateProvider(ModelProviderDefinition provider, boolean allowInsecureHttp) {
        validateEndpoint(provider.endpoint(), allowInsecureHttp, provider.options());
        provider.models()
                .forEach(model ->
                        validateProfile(model.options(), model.capabilities().contains(ModelCapability.REASONING)));
    }

    @Override
    public void validateSnapshot(ResolvedModelSnapshot snapshot, boolean allowInsecureHttp) {
        validateEndpoint(snapshot.endpoint(), allowInsecureHttp, snapshot.providerOptions());
        validateProfile(snapshot.invocationOptions(), snapshot.capabilities().contains(ModelCapability.REASONING));
    }

    @Override
    public void applyRequest(AgentChatRequest request, Map<String, Object> body) {
        Map<String, Object> options = request.model().invocationOptions();
        Object toolChoice = request.options().get("tool_choice");
        if (toolChoice != null
                && !"auto".equals(toolChoice)
                && !"none".equals(toolChoice)
                && !booleanOption(options, "supports_forced_tool_choice", false)) {
            throw new IllegalArgumentException("Bailian model profile does not support forced tool choice");
        }
        String profile = String.valueOf(options.getOrDefault("thinking_profile", "none"));
        boolean enabled = booleanOption(options, "thinking_enabled", "always".equals(profile));
        body.put("enable_thinking", enabled);
        if (enabled && options.containsKey("thinking_budget"))
            body.put("thinking_budget", options.get("thinking_budget"));
        if (enabled && options.containsKey("reasoning_effort"))
            body.put("reasoning_effort", options.get("reasoning_effort"));
        if (enabled && booleanOption(options, "preserve_thinking", false)) body.put("preserve_thinking", true);
        if (booleanOption(options, "tool_stream", false)) body.put("tool_stream", true);
    }

    @Override
    public ModelErrorCategory classifyError(int status, String providerCode, String safeDetail) {
        String normalized = (providerCode + " " + safeDetail).toLowerCase(Locale.ROOT);
        if (normalized.contains("invalidapikey") || normalized.contains("invalid_api_key")) {
            return ModelErrorCategory.AUTHENTICATION_FAILED;
        }
        if (normalized.contains("modelnotfound") || normalized.contains("model_not_found")) {
            return ModelErrorCategory.MODEL_NOT_FOUND;
        }
        if (normalized.contains("throttl") || normalized.contains("rate_limit")) {
            return ModelErrorCategory.RATE_LIMITED;
        }
        return OpenAiCompatibleDialect.super.classifyError(status, providerCode, safeDetail);
    }

    private static void validateEndpoint(
            java.net.URI endpoint, boolean allowInsecureHttp, Map<String, Object> options) {
        String workspaceId = requireDnsLabel(options, "workspace_id");
        String region = requireDnsLabel(options, "region");
        String expectedHost = workspaceId + "." + region + ".maas.aliyuncs.com";
        OpenAiCompatibleEndpointPolicy.validate(
                endpoint, allowInsecureHttp, Set.of(expectedHost), allowInsecureHttp ? null : "/compatible-mode/v1");
    }

    private static String requireDnsLabel(Map<String, Object> options, String name) {
        Object configured = options.get(name);
        if (!(configured instanceof String value) || !DNS_LABEL.matcher(value).matches()) {
            throw new IllegalArgumentException("Bailian provider " + name + " must be a valid frozen DNS label");
        }
        return value;
    }

    private static void validateProfile(Map<String, Object> options, boolean reasoningCapability) {
        String profile = String.valueOf(options.getOrDefault("thinking_profile", "none"));
        if (!Set.of("none", "hybrid", "always").contains(profile)) {
            throw new IllegalArgumentException("unsupported Bailian thinking profile: " + profile);
        }
        boolean enabled = booleanOption(options, "thinking_enabled", "always".equals(profile));
        if (enabled && (!reasoningCapability || "none".equals(profile))) {
            throw new IllegalArgumentException("Bailian model profile does not support thinking");
        }
        if (!enabled && "always".equals(profile)) {
            throw new IllegalArgumentException("Bailian thinking-only model cannot disable thinking");
        }
        positiveInteger(options, "thinking_budget");
        if (options.containsKey("thinking_budget") && !enabled) {
            throw new IllegalArgumentException("thinking_budget requires enabled thinking");
        }
        if (booleanOption(options, "preserve_thinking", false) && !enabled) {
            throw new IllegalArgumentException("preserve_thinking requires enabled thinking");
        }
        if (booleanOption(options, "requires_reasoning_continuation", false) && !enabled) {
            throw new IllegalArgumentException("reasoning continuation requires enabled thinking");
        }
        if (booleanOption(options, "tool_stream", false) && !booleanOption(options, "supports_tool_stream", false)) {
            throw new IllegalArgumentException("Bailian model profile does not support tool_stream");
        }
        Object effort = options.get("reasoning_effort");
        if (effort != null && !Set.of("low", "medium", "high").contains(String.valueOf(effort))) {
            throw new IllegalArgumentException("unsupported Bailian reasoning_effort");
        }
    }

    private static void positiveInteger(Map<String, Object> options, String key) {
        Object value = options.get(key);
        if (value != null
                && (!(value instanceof Number number)
                        || number.longValue() < 1
                        || number.longValue() > Integer.MAX_VALUE)) {
            throw new IllegalArgumentException(key + " must be a positive integer");
        }
    }

    private static boolean booleanOption(Map<String, Object> options, String key, boolean fallback) {
        Object value = options.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Boolean booleanValue)) throw new IllegalArgumentException(key + " must be boolean");
        return booleanValue;
    }
}
