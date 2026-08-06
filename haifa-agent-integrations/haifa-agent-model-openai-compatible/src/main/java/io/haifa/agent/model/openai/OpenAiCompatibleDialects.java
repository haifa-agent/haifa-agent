package io.haifa.agent.model.openai;

import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelProviderDefinition;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Stable dialect identifiers and deterministic frozen-snapshot resolution. */
public final class OpenAiCompatibleDialects {
    public static final String ENDPOINT_HOST = "endpoint_host";
    public static final String STANDARD_IMPLEMENTATION_ID = "openai-chat-completions";
    public static final String DEEPSEEK = "deepseek-openai-chat";
    public static final String ALIYUN_BAILIAN = "aliyun-bailian-openai-chat";
    public static final String VOLCENGINE_ARK = "volcengine-ark-openai-chat";
    public static final String VERSION_1 = "1.0";

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
        return Map.copyOf(options);
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
