package io.haifa.agent.model.openai;

import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelProviderDefinition;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * OpenCode Go subscription channel over the standard OpenAI Chat Completions contract.
 *
 * <p>The console exposes the Go models behind one documented OpenAI-compatible base URL, so the dialect only
 * freezes the endpoint and the reviewed model ids; no vendor request extension is added.
 */
final class OpenCodeGoOpenAiChatDialect implements OpenAiCompatibleDialect {
    static final OpenCodeGoOpenAiChatDialect INSTANCE = new OpenCodeGoOpenAiChatDialect();

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
        // The shared transport already emits the standard Chat Completions fields for this channel.
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
        if (capabilities.contains(ModelCapability.REASONING)) {
            throw new IllegalArgumentException("OpenCode Go reasoning profiles are not verified yet");
        }
    }

    private static String normalizedPath(URI endpoint) {
        String path = endpoint.getPath();
        if (path == null || path.isBlank() || "/".equals(path)) return "";
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return path;
    }
}
