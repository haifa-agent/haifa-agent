package io.haifa.agent.model.openai.responses;

import io.haifa.agent.model.api.ModelApiBindingDefinition;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Validated Responses compatibility profiles. */
public final class OpenAiResponsesDialects {
    public static final String STANDARD = ModelApiBindingDefinition.STANDARD_DIALECT;
    public static final String DEEPSEEK = "deepseek-openai-responses";
    public static final String ALIYUN_BAILIAN = "aliyun-bailian-openai-responses";
    public static final String OPENAI_CODEX = "openai-codex-responses";
    static final String CODEX_ORIGINATOR_OPTION = "codex_originator";
    static final String CODEX_USER_AGENT_OPTION = "codex_user_agent";

    private OpenAiResponsesDialects() {}

    static Profile resolve(ResolvedModelSnapshot snapshot, boolean allowInsecureHttp) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        if (!ModelApiStyles.OPENAI_RESPONSES.equals(snapshot.apiStyle())
                || !ModelApiStyles.OPENAI_RESPONSES_ADAPTER.equals(snapshot.adapterType())) {
            throw new IllegalArgumentException("snapshot is not bound to the OpenAI Responses adapter");
        }
        Profile profile =
                switch (snapshot.dialect()) {
                    case STANDARD -> Profile.STANDARD;
                    case DEEPSEEK -> Profile.DEEPSEEK;
                    case ALIYUN_BAILIAN -> Profile.ALIYUN_BAILIAN;
                    case OPENAI_CODEX -> Profile.OPENAI_CODEX;
                    default ->
                        throw new IllegalArgumentException(
                                "unsupported OpenAI Responses dialect: " + snapshot.dialect());
                };
        validateEndpoint(snapshot.endpoint(), allowInsecureHttp, profile);
        if (profile == Profile.DEEPSEEK
                && !Set.of("deepseek-v4-flash", "deepseek-v4-pro").contains(snapshot.providerModelId())) {
            throw new IllegalArgumentException("DeepSeek Responses model profile is not verified");
        }
        if (profile == Profile.ALIYUN_BAILIAN
                && !Set.of("qwen3.8-max-preview", "qwen3.7-max", "qwen3.7-max-2026-05-17", "qwen3.7-plus")
                        .contains(snapshot.providerModelId())) {
            throw new IllegalArgumentException("Bailian Responses model profile is not verified");
        }
        if (profile == Profile.OPENAI_CODEX) validateCodexOptions(snapshot, isLoopback(snapshot.endpoint()));
        return profile;
    }

    private static void validateEndpoint(URI endpoint, boolean allowInsecureHttp, Profile profile) {
        URI value = Objects.requireNonNull(endpoint, "endpoint must not be null");
        String host = value.getHost();
        if (host == null
                || value.getRawUserInfo() != null
                || value.getRawQuery() != null
                || value.getRawFragment() != null) {
            throw new IllegalArgumentException("Responses endpoint must be a clean absolute network URI");
        }
        boolean loopback = isLoopback(value);
        if ("http".equalsIgnoreCase(value.getScheme())) {
            if (!allowInsecureHttp || !loopback) {
                throw new IllegalArgumentException("insecure Responses endpoint must be explicitly allowed loopback");
            }
        } else if (!"https".equalsIgnoreCase(value.getScheme())) {
            throw new IllegalArgumentException("Responses endpoint must use HTTPS");
        }
        if (profile == Profile.DEEPSEEK
                && !loopback
                && (!"https".equalsIgnoreCase(value.getScheme())
                        || !"api.deepseek.com".equalsIgnoreCase(host)
                        || !normalizedPath(value).isEmpty())) {
            throw new IllegalArgumentException("DeepSeek Responses endpoint must be https://api.deepseek.com");
        }
        if (profile == Profile.ALIYUN_BAILIAN && !loopback) {
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            boolean workspaceHost = normalizedHost.matches("[a-z0-9-]+\\.[a-z0-9-]+\\.maas\\.aliyuncs\\.com");
            if (!"https".equalsIgnoreCase(value.getScheme())
                    || !workspaceHost
                    || !"/compatible-mode/v1".equals(normalizedPath(value))) {
                throw new IllegalArgumentException("Bailian Responses endpoint must be workspace scoped");
            }
        }
        if (profile == Profile.OPENAI_CODEX) {
            String path = normalizedPath(value);
            if (loopback) {
                if (!"/backend-api/codex".equals(path)) {
                    throw new IllegalArgumentException("Codex Responses loopback endpoint must use /backend-api/codex");
                }
            } else if (!"https".equalsIgnoreCase(value.getScheme())
                    || !"chatgpt.com".equalsIgnoreCase(host)
                    || value.getPort() != -1
                    || !"/backend-api/codex".equals(path)) {
                throw new IllegalArgumentException(
                        "Codex Responses endpoint must be https://chatgpt.com/backend-api/codex");
            }
        }
    }

    private static void validateCodexOptions(ResolvedModelSnapshot snapshot, boolean loopback) {
        String originator = option(snapshot, CODEX_ORIGINATOR_OPTION);
        String userAgent = option(snapshot, CODEX_USER_AGENT_OPTION);
        if (!originator.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("Codex originator is invalid");
        }
        if (userAgent.length() > 128 || containsHeaderSeparator(userAgent)) {
            throw new IllegalArgumentException("Codex user agent is invalid");
        }
        if (!loopback && !snapshot.credentialRef().value().startsWith("model-auth://openai-codex/")) {
            throw new IllegalArgumentException("Codex Responses requires a Coding Auth credential reference");
        }
    }

    private static String option(ResolvedModelSnapshot snapshot, String name) {
        Object value = snapshot.providerOptions().get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Codex provider option is required: " + name);
        }
        return text.trim();
    }

    private static boolean containsHeaderSeparator(String value) {
        return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
    }

    private static boolean isLoopback(URI endpoint) {
        String host = endpoint.getHost();
        return host != null
                && Set.of("localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1").contains(host.toLowerCase(Locale.ROOT));
    }

    private static String normalizedPath(URI endpoint) {
        String path = endpoint.getPath();
        if (path == null || path.isBlank() || "/".equals(path)) return "";
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return path;
    }

    enum Profile {
        STANDARD,
        DEEPSEEK,
        ALIYUN_BAILIAN,
        OPENAI_CODEX
    }
}
