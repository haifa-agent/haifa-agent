package io.haifa.agent.model.openai;

import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelProviderDefinition;
import io.haifa.agent.model.api.ModelReasoningMode;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Exact Kimi API Key Chat contract for the reviewed K3 and K2.x profiles. */
final class KimiOpenAiChatDialect implements OpenAiCompatibleDialect {
    static final KimiOpenAiChatDialect INSTANCE = new KimiOpenAiChatDialect();
    private static final Set<String> MODELS = Set.of("kimi-k3", "kimi-k2.7-code", "kimi-k2.6");

    private KimiOpenAiChatDialect() {}

    @Override
    public String id() {
        return OpenAiCompatibleDialects.KIMI;
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
        String model = request.model().providerModelId();
        Map<String, Object> options = request.model().invocationOptions();
        ModelReasoningMode mode = reasoningMode(options);
        if (request.options().containsKey("temperature") || options.containsKey("temperature")) {
            throw new IllegalArgumentException("Kimi reasoning profiles do not accept temperature");
        }
        Object toolChoice = request.options().getOrDefault("tool_choice", options.get("tool_choice"));
        if (mode != ModelReasoningMode.DISABLED
                && toolChoice != null
                && !Set.of("auto", "none").contains(String.valueOf(toolChoice))) {
            throw new IllegalArgumentException("Kimi thinking supports only automatic or disabled tool choice");
        }
        if ("kimi-k3".equals(model)) {
            if (mode == ModelReasoningMode.DISABLED) {
                throw new IllegalArgumentException("Kimi K3 always reasons");
            }
            body.put("reasoning_effort", effort(options, Set.of("low", "high", "max"), "max"));
            return;
        }
        if ("kimi-k2.7-code".equals(model) && mode == ModelReasoningMode.DISABLED) {
            throw new IllegalArgumentException("Kimi K2.7 Code always reasons");
        }
        Map<String, Object> thinking = new LinkedHashMap<>();
        thinking.put("type", mode == ModelReasoningMode.DISABLED ? "disabled" : "enabled");
        if ("kimi-k2.7-code".equals(model)) {
            thinking.put("keep", "all");
        } else if (mode != ModelReasoningMode.DISABLED
                && request.messages().stream()
                        .anyMatch(message -> message.reasoning().isPresent())) {
            thinking.put("keep", "all");
        }
        body.put("thinking", Map.copyOf(thinking));
    }

    private static void validateEndpoint(URI endpoint, boolean allowInsecureHttp) {
        String host = endpoint.getHost();
        boolean loopback =
                host != null && Set.of("localhost", "127.0.0.1", "::1").contains(host.toLowerCase());
        if (allowInsecureHttp && loopback) return;
        if (!"https".equalsIgnoreCase(endpoint.getScheme())
                || host == null
                || !Set.of("api.moonshot.cn", "api.moonshot.ai").contains(host.toLowerCase())
                || !"/v1".equals(normalizedPath(endpoint))) {
            throw new IllegalArgumentException("Kimi endpoint must be an official HTTPS API Key endpoint");
        }
    }

    private static void validateProfile(String model, Map<String, Object> options, boolean reasoning) {
        if (!MODELS.contains(model) || !reasoning) {
            throw new IllegalArgumentException("Kimi model profile is not verified");
        }
        ModelReasoningMode mode = reasoningMode(options);
        if (("kimi-k3".equals(model) || "kimi-k2.7-code".equals(model)) && mode == ModelReasoningMode.DISABLED) {
            throw new IllegalArgumentException("Kimi always-thinking model cannot disable reasoning");
        }
        if ("kimi-k3".equals(model)) effort(options, Set.of("low", "high", "max"), "max");
        if (options.containsKey("temperature")) {
            throw new IllegalArgumentException("Kimi reasoning profiles do not accept temperature");
        }
    }

    private static ModelReasoningMode reasoningMode(Map<String, Object> options) {
        Object value = options.getOrDefault("thinking", "enabled");
        try {
            return ModelReasoningMode.valueOf(String.valueOf(value).toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unsupported Kimi reasoning mode", exception);
        }
    }

    private static String effort(Map<String, Object> options, Set<String> allowed, String fallback) {
        String value = String.valueOf(options.getOrDefault("reasoning_effort", fallback));
        if (!allowed.contains(value)) throw new IllegalArgumentException("unsupported Kimi reasoning effort");
        return value;
    }

    private static String normalizedPath(URI endpoint) {
        String path = endpoint.getPath();
        if (path == null || path.isBlank() || "/".equals(path)) return "";
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return path;
    }
}
