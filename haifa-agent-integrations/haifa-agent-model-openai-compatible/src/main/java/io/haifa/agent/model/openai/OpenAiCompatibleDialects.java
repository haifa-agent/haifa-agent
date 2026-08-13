package io.haifa.agent.model.openai;

import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelProviderDefinition;
import io.haifa.agent.model.api.ModelReasoningMode;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Stable dialect identifiers and deterministic frozen-snapshot resolution. */
public final class OpenAiCompatibleDialects {
    public static final String ENDPOINT_HOST = "endpoint_host";
    public static final String STANDARD_IMPLEMENTATION_ID = "openai-chat-completions";
    public static final String DEEPSEEK = "deepseek-openai-chat";
    public static final String ALIYUN_BAILIAN = "aliyun-bailian-openai-chat";
    public static final String KIMI = "kimi-openai-chat";
    public static final String ZHIPU = "zhipu-openai-chat";
    public static final String VOLCENGINE_ARK = "volcengine-ark-openai-chat";
    public static final String VERSION_1 = "1.0";
    private static final String BAILIAN_PATH = "/compatible-mode/v1";
    private static final Pattern BAILIAN_HOST = Pattern.compile("^([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)\\."
            + "([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)\\.maas\\.aliyuncs\\.com$");

    private OpenAiCompatibleDialects() {}

    /** Freezes host-governed provider configuration without inferring protocol semantics from the provider id. */
    public static Map<String, Object> configuredOptions(String dialectId, URI endpoint) {
        String normalizedId = requireText(dialectId, "dialectId");
        URI configuredEndpoint = Objects.requireNonNull(endpoint, "endpoint must not be null");
        Map<String, Object> options = new LinkedHashMap<>();
        if (io.haifa.agent.model.api.ModelApiBindingDefinition.STANDARD_DIALECT.equals(normalizedId)) {
            String host = configuredEndpoint.getHost();
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("standard OpenAI Chat Completions endpoint must have a host");
            }
            options.put(ENDPOINT_HOST, host.toLowerCase(Locale.ROOT));
        }
        if (ALIYUN_BAILIAN.equals(normalizedId)) {
            freezeBailianEndpoint(configuredEndpoint, options);
        }
        return Map.copyOf(options);
    }

    /** Maps provider-neutral reasoning policy into dialect-owned frozen invocation options. */
    public static Map<String, Object> configuredInvocationOptions(String dialectId, ModelReasoningMode reasoningMode) {
        String normalizedId = requireText(dialectId, "dialectId");
        ModelReasoningMode mode = Objects.requireNonNull(reasoningMode, "reasoningMode must not be null");
        if (!ALIYUN_BAILIAN.equals(normalizedId)) return Map.of();
        return switch (mode) {
            case DISABLED -> Map.of("thinking_profile", "none", "thinking_enabled", false);
            case ENABLED ->
                Map.of(
                        "thinking_profile",
                        "always",
                        "thinking_enabled",
                        true,
                        "preserve_thinking",
                        true,
                        "requires_reasoning_continuation",
                        true);
            case ADAPTIVE ->
                Map.of(
                        "thinking_profile",
                        "hybrid",
                        "thinking_enabled",
                        true,
                        "preserve_thinking",
                        true,
                        "requires_reasoning_continuation",
                        true);
        };
    }

    private static void freezeBailianEndpoint(URI endpoint, Map<String, Object> options) {
        String host = endpoint.getHost();
        if (!"https".equalsIgnoreCase(endpoint.getScheme())
                || host == null
                || endpoint.getPort() != -1
                || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null
                || endpoint.getFragment() != null
                || !BAILIAN_PATH.equals(endpoint.getPath())) {
            throw new IllegalArgumentException("Bailian endpoint must be a workspace-scoped HTTPS compatible endpoint");
        }
        Matcher matcher = BAILIAN_HOST.matcher(host.toLowerCase(Locale.ROOT));
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Bailian endpoint host is not allowed");
        }
        options.put("workspace_id", matcher.group(1));
        options.put("region", matcher.group(2));
    }

    public static OpenAiCompatibleDialect resolve(ModelProviderDefinition provider) {
        return resolve(provider.binding(ModelApiStyles.OPENAI_CHAT_COMPLETIONS).dialect());
    }

    public static OpenAiCompatibleDialect resolve(ResolvedModelSnapshot snapshot) {
        return resolve(snapshot.dialect());
    }

    private static OpenAiCompatibleDialect resolve(String dialectId) {
        return switch (dialectId) {
            case io.haifa.agent.model.api.ModelApiBindingDefinition.STANDARD_DIALECT ->
                StandardOpenAiChatCompletionsDialect.INSTANCE;
            case DEEPSEEK -> DeepSeekOpenAiChatDialect.INSTANCE;
            case ALIYUN_BAILIAN -> AliyunBailianOpenAiChatDialect.INSTANCE;
            case KIMI -> KimiOpenAiChatDialect.INSTANCE;
            case ZHIPU -> ZhipuOpenAiChatDialect.INSTANCE;
            case VOLCENGINE_ARK -> VolcengineArkOpenAiChatDialect.INSTANCE;
            default -> throw new IllegalArgumentException("unsupported OpenAI-compatible dialect: " + dialectId);
        };
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
