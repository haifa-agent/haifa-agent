package io.haifa.agent.model.openai;

import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelProviderDefinition;
import io.haifa.agent.model.api.ModelReasoningMode;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.net.URI;
import java.util.Map;
import java.util.Set;

/** Reviewed Zhipu general OpenAI Chat contract. */
final class ZhipuOpenAiChatDialect implements OpenAiCompatibleDialect {
    static final ZhipuOpenAiChatDialect INSTANCE = new ZhipuOpenAiChatDialect();
    private static final Set<String> MODELS = Set.of("glm-5.2", "glm-5.1", "glm-5", "glm-4.7");

    private ZhipuOpenAiChatDialect() {}

    @Override
    public String id() {
        return OpenAiCompatibleDialects.ZHIPU;
    }

    @Override
    public String version() {
        return OpenAiCompatibleDialects.VERSION_1;
    }

    @Override
    public void validateProvider(ModelProviderDefinition provider, boolean allowInsecureHttp) {
        validateEndpoint(provider.endpoint(), allowInsecureHttp);
        provider.models()
                .forEach(model -> validateProfile(
                        model.providerModelId(),
                        model.options(),
                        model.capabilities().contains(ModelCapability.REASONING)));
    }

    @Override
    public void validateSnapshot(ResolvedModelSnapshot snapshot, boolean allowInsecureHttp) {
        validateEndpoint(snapshot.endpoint(), allowInsecureHttp);
        validateProfile(
                snapshot.providerModelId(),
                snapshot.invocationOptions(),
                snapshot.capabilities().contains(ModelCapability.REASONING));
    }

    @Override
    public void applyRequest(AgentChatRequest request, Map<String, Object> body) {
        Map<String, Object> options = request.model().invocationOptions();
        ModelReasoningMode mode = reasoningMode(options);
        body.put("thinking", Map.of("type", mode == ModelReasoningMode.DISABLED ? "disabled" : "enabled"));
        if ("glm-5.2".equals(request.model().providerModelId()) && mode != ModelReasoningMode.DISABLED) {
            body.put("reasoning_effort", effectiveEffort(options.getOrDefault("reasoning_effort", "high")));
        }
        boolean sample = booleanOption(options, "do_sample", false);
        body.put("do_sample", sample);
        if (!sample && (request.options().containsKey("temperature") || options.containsKey("temperature"))) {
            throw new IllegalArgumentException("Zhipu temperature is ignored when do_sample is false");
        }
        if (options.containsKey("clear_thinking")) {
            body.put("clear_thinking", booleanOption(options, "clear_thinking", true));
        }
    }

    private static void validateEndpoint(URI endpoint, boolean allowInsecureHttp) {
        String host = endpoint.getHost();
        boolean loopback =
                host != null && Set.of("localhost", "127.0.0.1", "::1").contains(host.toLowerCase());
        if (allowInsecureHttp && loopback) return;
        if (!"https".equalsIgnoreCase(endpoint.getScheme())
                || !"open.bigmodel.cn".equalsIgnoreCase(host)
                || !"/api/paas/v4".equals(normalizedPath(endpoint))) {
            throw new IllegalArgumentException("Zhipu Chat endpoint must use the general API endpoint");
        }
    }

    private static void validateProfile(String model, Map<String, Object> options, boolean reasoning) {
        if (!MODELS.contains(model) || !reasoning) {
            throw new IllegalArgumentException("Zhipu model profile is not verified");
        }
        reasoningMode(options);
        boolean sample = booleanOption(options, "do_sample", false);
        if (!sample && options.containsKey("temperature")) {
            throw new IllegalArgumentException("Zhipu deterministic profile cannot configure temperature");
        }
        if ("glm-5.2".equals(model) && options.containsKey("reasoning_effort")) {
            effectiveEffort(options.get("reasoning_effort"));
        }
    }

    private static ModelReasoningMode reasoningMode(Map<String, Object> options) {
        Object value = options.getOrDefault("thinking", "adaptive");
        try {
            return ModelReasoningMode.valueOf(String.valueOf(value).toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unsupported Zhipu reasoning mode", exception);
        }
    }

    private static String effectiveEffort(Object configured) {
        return switch (String.valueOf(configured)) {
            case "low", "medium", "high" -> "high";
            case "max" -> "max";
            default -> throw new IllegalArgumentException("unsupported Zhipu effective reasoning effort");
        };
    }

    private static boolean booleanOption(Map<String, Object> options, String key, boolean fallback) {
        Object value = options.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Boolean booleanValue)) throw new IllegalArgumentException(key + " must be boolean");
        return booleanValue;
    }

    private static String normalizedPath(URI endpoint) {
        String path = endpoint.getPath();
        if (path == null || path.isBlank() || "/".equals(path)) return "";
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return path;
    }
}
