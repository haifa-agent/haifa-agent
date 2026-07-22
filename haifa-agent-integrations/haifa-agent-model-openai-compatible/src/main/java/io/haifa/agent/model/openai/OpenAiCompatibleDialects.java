package io.haifa.agent.model.openai;

import io.haifa.agent.model.api.ModelProviderDefinition;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.util.Map;

/** Stable dialect identifiers and deterministic frozen-snapshot resolution. */
public final class OpenAiCompatibleDialects {
    public static final String DIALECT_ID = "dialect_id";
    public static final String DIALECT_VERSION = "dialect_version";
    public static final String DEEPSEEK = "deepseek-openai-chat";
    public static final String ALIYUN_BAILIAN = "aliyun-bailian-openai-chat";
    public static final String VOLCENGINE_ARK = "volcengine-ark-openai-chat";
    public static final String VERSION_1 = "1.0";

    private OpenAiCompatibleDialects() {}

    public static Map<String, Object> deepSeekOptions() {
        return Map.of(DIALECT_ID, DEEPSEEK, DIALECT_VERSION, VERSION_1);
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
}
