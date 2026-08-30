package io.haifa.agent.model.openai.anthropic;

import io.haifa.agent.model.api.ModelApiBindingDefinition;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.model.openai.OpenAiCompatibleBindingRegistry;
import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Validated Anthropic Messages compatibility profiles. */
public final class AnthropicMessagesDialects {
    public static final String STANDARD = ModelApiBindingDefinition.STANDARD_DIALECT;
    public static final String DEEPSEEK = "deepseek-anthropic-messages";
    public static final String ZHIPU = "zhipu-anthropic-messages";

    private AnthropicMessagesDialects() {}

    static Profile resolve(ResolvedModelSnapshot snapshot, boolean allowInsecureHttp) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        if (!ModelApiStyles.ANTHROPIC_MESSAGES.equals(snapshot.apiStyle())
                || !ModelApiStyles.ANTHROPIC_MESSAGES_ADAPTER.equals(snapshot.adapterType())) {
            throw new IllegalArgumentException("snapshot is not bound to the Anthropic Messages adapter");
        }
        Profile profile =
                switch (snapshot.dialect()) {
                    case STANDARD -> Profile.STANDARD;
                    case DEEPSEEK -> Profile.DEEPSEEK;
                    case ZHIPU -> Profile.ZHIPU;
                    default ->
                        throw new IllegalArgumentException(
                                "unsupported Anthropic Messages dialect: " + snapshot.dialect());
                };
        validateEndpoint(snapshot.endpoint(), allowInsecureHttp, profile);
        if (profile == Profile.DEEPSEEK
                && !OpenAiCompatibleBindingRegistry.isAdmitted(
                        "deepseek", snapshot.providerModelId(), ModelApiStyles.ANTHROPIC_MESSAGES, DEEPSEEK)) {
            throw new IllegalArgumentException("DeepSeek Anthropic model profile is not verified");
        }
        if (profile == Profile.ZHIPU
                && !OpenAiCompatibleBindingRegistry.isAdmitted(
                        "zhipu", snapshot.providerModelId(), ModelApiStyles.ANTHROPIC_MESSAGES, ZHIPU)) {
            throw new IllegalArgumentException("Zhipu Anthropic model profile is not verified");
        }
        return profile;
    }

    private static void validateEndpoint(URI endpoint, boolean allowInsecureHttp, Profile profile) {
        URI value = Objects.requireNonNull(endpoint, "endpoint must not be null");
        String host = value.getHost();
        if (host == null
                || value.getRawUserInfo() != null
                || value.getRawQuery() != null
                || value.getRawFragment() != null) {
            throw new IllegalArgumentException("Anthropic Messages endpoint must be a clean absolute network URI");
        }
        boolean loopback =
                Set.of("localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1").contains(host.toLowerCase(Locale.ROOT));
        if ("http".equalsIgnoreCase(value.getScheme())) {
            if (!allowInsecureHttp || !loopback) {
                throw new IllegalArgumentException(
                        "insecure Anthropic Messages endpoint must be explicitly allowed loopback");
            }
        } else if (!"https".equalsIgnoreCase(value.getScheme())) {
            throw new IllegalArgumentException("Anthropic Messages endpoint must use HTTPS");
        }
        if (profile == Profile.DEEPSEEK
                && !loopback
                && (!"https".equalsIgnoreCase(value.getScheme())
                        || !"api.deepseek.com".equalsIgnoreCase(host)
                        || !"/anthropic".equals(normalizedPath(value)))) {
            throw new IllegalArgumentException(
                    "DeepSeek Anthropic endpoint must be https://api.deepseek.com/anthropic");
        }
        if (profile == Profile.ZHIPU
                && !loopback
                && (!"https".equalsIgnoreCase(value.getScheme())
                        || !"open.bigmodel.cn".equalsIgnoreCase(host)
                        || !"/api/anthropic".equals(normalizedPath(value)))) {
            throw new IllegalArgumentException(
                    "Zhipu Anthropic endpoint must be https://open.bigmodel.cn/api/anthropic");
        }
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
        ZHIPU
    }
}
