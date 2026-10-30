package io.haifa.agent.model.openai;

import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelProviderDefinition;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * OpenCode Go subscription channel over the standard OpenAI Chat Completions contract.
 *
 * <p>The console exposes the Go models behind one documented OpenAI-compatible base URL, so the dialect only
 * freezes the endpoint and the reviewed model ids. The body stays standard, but the Go channel refuses to route a
 * request that omits its documented session header, so the dialect owns exactly two transport headers: a stable
 * {@code x-opencode-session} value and a product user agent.
 */
final class OpenCodeGoOpenAiChatDialect implements OpenAiCompatibleDialect {
    static final OpenCodeGoOpenAiChatDialect INSTANCE = new OpenCodeGoOpenAiChatDialect();

    /** Documented Go routing header; the channel answers HTTP 400 {@code missingsessionid} without it. */
    static final String SESSION_HEADER = "x-opencode-session";

    /** Stable product identity; the Go channel asks every client to name itself instead of the default JDK agent. */
    static final String USER_AGENT = "haifa-coding-agent/opencode-go";

    private static final Set<String> MODELS = Set.of("deepseek-v4-pro", "deepseek-v4.1-flash", "kimi-k3");
    private static final Set<String> HOSTS = Set.of("opencode.ai");
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "::1");
    private static final String REQUIRED_PATH = "/inference/go/openai/v1";

    private OpenCodeGoOpenAiChatDialect() {}

    @Override
    public String id() {
        return OpenAiCompatibleDialects.OPENCODE_GO;
    }

    @Override
    public String version() {
        return OpenAiCompatibleDialects.VERSION_1;
    }

    @Override
    public void validateProvider(ModelProviderDefinition provider, boolean allowInsecureHttp) {
        validateEndpoint(provider.endpoint(), allowInsecureHttp);
        provider.models().forEach(model -> validateModel(model.providerModelId(), model.capabilities()));
    }

    @Override
    public void validateSnapshot(ResolvedModelSnapshot snapshot, boolean allowInsecureHttp) {
        validateEndpoint(snapshot.endpoint(), allowInsecureHttp);
        validateModel(snapshot.providerModelId(), snapshot.capabilities());
    }

    @Override
    public void applyRequest(AgentChatRequest request, Map<String, Object> body) {
        String thinking = frozen(request, "thinking", "disabled");
        if (!"enabled".equals(thinking)) return;
        // The channel translates the DeepSeek thinking contract, which rejects sampling and forced tool selection.
        if (request.options().containsKey("temperature")
                || request.model().invocationOptions().containsKey("temperature")) {
            throw new IllegalArgumentException("OpenCode Go thinking cannot be combined with temperature");
        }
        Object toolChoice = body.get("tool_choice");
        if (toolChoice != null && !"auto".equals(toolChoice) && !"none".equals(toolChoice)) {
            throw new IllegalArgumentException("OpenCode Go thinking does not support forced tool_choice");
        }
        body.put("thinking", Map.of("type", "enabled"));
        body.put("reasoning_effort", frozen(request, "reasoning_effort", "high"));
    }

    /**
     * Adds the Go channel's two documented transport headers.
     *
     * <p>The session value is the conversation affinity: the Runtime freezes the root Run id, so every physical call
     * of one conversation and of its delegated children carries the same value for routing and prompt-cache
     * locality. It never carries prompt material.
     */
    @Override
    public void applyHeaders(HttpRequest.Builder builder, AgentChatRequest request) {
        builder.header(SESSION_HEADER, headerValue(request.sessionAffinity(), "session affinity"))
                .header("User-Agent", USER_AGENT);
    }

    /** The Go model list lives on the subscription root, not below the Chat Completions base path. */
    @Override
    public URI modelsUri(URI endpoint) {
        if (!REQUIRED_PATH.equals(normalizedPath(endpoint))) return OpenAiCompatibleDialect.super.modelsUri(endpoint);
        return URI.create(endpoint.getScheme() + "://" + endpoint.getRawAuthority() + "/inference/go/v1/models");
    }

    private static String headerValue(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(field + " contains invalid control characters");
        }
        return value.trim();
    }

    private static String frozen(AgentChatRequest request, String key, String fallback) {
        return String.valueOf(request.model()
                .invocationOptions()
                .getOrDefault(key, request.model().providerOptions().getOrDefault(key, fallback)));
    }

    private static void validateEndpoint(URI endpoint, boolean allowInsecureHttp) {
        String host = endpoint.getHost();
        String normalizedHost = host == null ? "" : host.toLowerCase(Locale.ROOT);
        boolean loopbackHttp = allowInsecureHttp
                && "http".equalsIgnoreCase(endpoint.getScheme())
                && LOOPBACK_HOSTS.contains(normalizedHost);
        if (loopbackHttp) return;
        if (!"https".equalsIgnoreCase(endpoint.getScheme())
                || endpoint.getPort() != -1
                || endpoint.getRawQuery() != null
                || endpoint.getRawFragment() != null
                || endpoint.getRawUserInfo() != null
                || !HOSTS.contains(normalizedHost)
                || !REQUIRED_PATH.equals(normalizedPath(endpoint))) {
            throw new IllegalArgumentException("OpenCode Go endpoint must be the official HTTPS Go channel");
        }
    }

    private static void validateModel(String providerModelId, Set<ModelCapability> capabilities) {
        if (!MODELS.contains(providerModelId)) {
            throw new IllegalArgumentException("OpenCode Go model binding is not verified");
        }
    }

    private static String normalizedPath(URI endpoint) {
        String path = endpoint.getPath();
        if (path == null || path.isBlank() || "/".equals(path)) return "";
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return path;
    }
}
