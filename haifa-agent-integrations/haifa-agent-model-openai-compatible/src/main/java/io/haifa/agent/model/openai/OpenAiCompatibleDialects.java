package io.haifa.agent.model.openai;

import io.haifa.agent.model.api.ModelProviderDefinition;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Stable dialect identifiers and deterministic frozen-snapshot resolution. */
public final class OpenAiCompatibleDialects {
    public static final String DIALECT_ID = "dialect_id";
    public static final String DIALECT_VERSION = "dialect_version";
    public static final String NATIVE_STREAMING = "native_streaming";
    public static final String ENDPOINT_HOST = "endpoint_host";
    public static final String OPENAI_CHAT_COMPLETIONS = "openai-chat-completions";
    public static final String DEEPSEEK = "deepseek-openai-chat";
    public static final String ALIYUN_BAILIAN = "aliyun-bailian-openai-chat";
    public static final String VOLCENGINE_ARK = "volcengine-ark-openai-chat";
    public static final String VERSION_1 = "1.0";

    private OpenAiCompatibleDialects() {}

    public static Map<String, Object> deepSeekOptions() {
        return Map.of(DIALECT_ID, DEEPSEEK, DIALECT_VERSION, VERSION_1);
    }

    public static Map<String, Object> standardOpenAiChatCompletionsOptions() {
        return Map.of(DIALECT_ID, OPENAI_CHAT_COMPLETIONS, DIALECT_VERSION, VERSION_1);
    }

    public static Map<String, Object> standardOpenAiChatCompletionsOptions(boolean nativeStreaming) {
        return Map.of(
                DIALECT_ID, OPENAI_CHAT_COMPLETIONS, DIALECT_VERSION, VERSION_1, NATIVE_STREAMING, nativeStreaming);
    }

    /** Freezes host-governed provider configuration without inferring protocol semantics from the provider id. */
    public static Map<String, Object> configuredOptions(
            String dialectId, String dialectVersion, boolean nativeStreaming, URI endpoint) {
        String normalizedId = requireText(dialectId, "dialectId");
        String normalizedVersion = requireText(dialectVersion, "dialectVersion");
        URI configuredEndpoint = Objects.requireNonNull(endpoint, "endpoint must not be null");
        Map<String, Object> options = new LinkedHashMap<>();
        options.put(DIALECT_ID, normalizedId);
        options.put(DIALECT_VERSION, normalizedVersion);
        options.put(NATIVE_STREAMING, nativeStreaming);
        if (OPENAI_CHAT_COMPLETIONS.equals(normalizedId)) {
            String host = configuredEndpoint.getHost();
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("standard OpenAI Chat Completions endpoint must have a host");
            }
            options.put(ENDPOINT_HOST, host.toLowerCase(Locale.ROOT));
        }
        return Map.copyOf(options);
    }

    public static OpenAiCompatibleDialect resolve(ModelProviderDefinition provider) {
        return resolve(provider.id(), provider.options());
    }

    public static OpenAiCompatibleDialect resolve(ResolvedModelSnapshot snapshot) {
        return resolve(snapshot.providerId(), snapshot.providerOptions());
    }

    private static OpenAiCompatibleDialect resolve(ModelProviderId providerId, Map<String, Object> options) {
        Object configuredId = options.get(DIALECT_ID);
        String dialectId = configuredId == null ? legacyDialect(providerId) : String.valueOf(configuredId);
        String dialectVersion = String.valueOf(options.getOrDefault(DIALECT_VERSION, VERSION_1));
        if (!VERSION_1.equals(dialectVersion)) {
            throw new IllegalArgumentException("unsupported OpenAI-compatible dialect version: " + dialectVersion);
        }
        return switch (dialectId) {
            case OPENAI_CHAT_COMPLETIONS -> StandardOpenAiChatCompletionsDialect.INSTANCE;
            case DEEPSEEK -> DeepSeekOpenAiChatDialect.INSTANCE;
            case ALIYUN_BAILIAN -> AliyunBailianOpenAiChatDialect.INSTANCE;
            case VOLCENGINE_ARK -> VolcengineArkOpenAiChatDialect.INSTANCE;
            default -> throw new IllegalArgumentException("unsupported OpenAI-compatible dialect: " + dialectId);
        };
    }

    private static String legacyDialect(ModelProviderId providerId) {
        if ("deepseek".equals(providerId.value())) return DEEPSEEK;
        throw new IllegalArgumentException("frozen model snapshot is missing dialect_id");
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
