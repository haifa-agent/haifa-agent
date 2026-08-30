package io.haifa.agent.model.openai;

import io.haifa.agent.model.api.ApiStyleId;
import io.haifa.agent.model.api.ModelApiBindingDefinition;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelReasoningBehavior;
import io.haifa.agent.model.api.ModelReasoningEffort;
import io.haifa.agent.model.api.ModelReasoningMode;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.model.openai.anthropic.AnthropicMessagesDialects;
import io.haifa.agent.model.openai.responses.OpenAiResponsesDialects;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Authoritative, immutable registry of trusted model binding admissions for OpenAI-compatible providers.
 * Admissions are keyed by the exact 4-tuple: {@code (providerId, providerModelId, apiStyle, dialect)}.
 */
public final class OpenAiCompatibleBindingRegistry {
    public record AdmissionKey(String providerId, String providerModelId, ApiStyleId apiStyle, String dialect) {
        public AdmissionKey {
            Objects.requireNonNull(providerId, "providerId must not be null");
            Objects.requireNonNull(providerModelId, "providerModelId must not be null");
            Objects.requireNonNull(apiStyle, "apiStyle must not be null");
            Objects.requireNonNull(dialect, "dialect must not be null");
        }
    }

    public record AdmittedBinding(
            AdmissionKey key,
            ModelReasoningBehavior reasoningBehavior,
            Set<ModelReasoningMode> allowedReasoningModes,
            Set<ModelReasoningEffort> allowedReasoningEfforts,
            boolean toolReasoningContinuationRequired) {
        public AdmittedBinding {
            Objects.requireNonNull(key, "key must not be null");
            Objects.requireNonNull(reasoningBehavior, "reasoningBehavior must not be null");
            allowedReasoningModes = Set.copyOf(allowedReasoningModes);
            allowedReasoningEfforts = Set.copyOf(allowedReasoningEfforts);
        }
    }

    private static final Map<AdmissionKey, AdmittedBinding> ADMISSIONS = buildAdmissions();

    private OpenAiCompatibleBindingRegistry() {}

    public static Optional<AdmittedBinding> find(
            String providerId, String providerModelId, ApiStyleId apiStyle, String dialect) {
        if (providerId == null || providerModelId == null || apiStyle == null || dialect == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(ADMISSIONS.get(new AdmissionKey(providerId, providerModelId, apiStyle, dialect)));
    }

    public static Optional<AdmittedBinding> find(ResolvedModelSnapshot snapshot) {
        if (snapshot == null || snapshot.providerId() == null) {
            return Optional.empty();
        }
        return find(snapshot.providerId().value(), snapshot.providerModelId(), snapshot.apiStyle(), snapshot.dialect());
    }

    public static boolean isAdmitted(String providerId, String providerModelId, ApiStyleId apiStyle, String dialect) {
        return find(providerId, providerModelId, apiStyle, dialect).isPresent();
    }

    private static Map<AdmissionKey, AdmittedBinding> buildAdmissions() {
        Map<AdmissionKey, AdmittedBinding> map = new HashMap<>();

        // DeepSeek
        for (String model : Set.of("deepseek-v4-flash", "deepseek-v4-pro")) {
            register(
                    map,
                    "deepseek",
                    model,
                    ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                    OpenAiCompatibleDialects.DEEPSEEK,
                    ModelReasoningBehavior.OPTIONAL,
                    Set.of(ModelReasoningMode.DISABLED, ModelReasoningMode.ENABLED),
                    Set.of(ModelReasoningEffort.HIGH, ModelReasoningEffort.MAX),
                    true);
            register(
                    map,
                    "deepseek",
                    model,
                    ModelApiStyles.OPENAI_RESPONSES,
                    OpenAiResponsesDialects.DEEPSEEK,
                    ModelReasoningBehavior.ALWAYS,
                    Set.of(ModelReasoningMode.ENABLED),
                    Set.of(ModelReasoningEffort.HIGH),
                    true);
            register(
                    map,
                    "deepseek",
                    model,
                    ModelApiStyles.ANTHROPIC_MESSAGES,
                    AnthropicMessagesDialects.DEEPSEEK,
                    ModelReasoningBehavior.ALWAYS,
                    Set.of(ModelReasoningMode.ENABLED),
                    Set.of(ModelReasoningEffort.HIGH),
                    true);
        }

        // Alibaba Cloud Bailian - Chat Completions
        for (String model : Set.of(
                "qwen3.8-max-preview",
                "qwen3.7-max",
                "qwen3.7-max-2026-05-17",
                "qwen3.7-plus",
                "qwen3.7-flash",
                "qwen3-vl-plus")) {
            register(
                    map,
                    "aliyun-bailian",
                    model,
                    ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                    OpenAiCompatibleDialects.ALIYUN_BAILIAN,
                    ModelReasoningBehavior.OPTIONAL,
                    Set.of(ModelReasoningMode.DISABLED, ModelReasoningMode.ENABLED),
                    Set.of(ModelReasoningEffort.HIGH, ModelReasoningEffort.MAX),
                    true);
        }

        // Alibaba Cloud Bailian - Responses
        for (String model : Set.of("qwen3.8-max-preview", "qwen3.7-max", "qwen3.7-max-2026-05-17", "qwen3.7-plus")) {
            register(
                    map,
                    "aliyun-bailian",
                    model,
                    ModelApiStyles.OPENAI_RESPONSES,
                    OpenAiResponsesDialects.ALIYUN_BAILIAN,
                    ModelReasoningBehavior.ALWAYS,
                    Set.of(ModelReasoningMode.ENABLED),
                    Set.of(ModelReasoningEffort.HIGH),
                    false);
        }

        // Kimi / Moonshot
        register(
                map,
                "kimi",
                "kimi-k3",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                OpenAiCompatibleDialects.KIMI,
                ModelReasoningBehavior.ALWAYS,
                Set.of(ModelReasoningMode.ENABLED),
                Set.of(ModelReasoningEffort.LOW, ModelReasoningEffort.HIGH, ModelReasoningEffort.MAX),
                true);
        register(
                map,
                "kimi",
                "kimi-k2.7-code",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                OpenAiCompatibleDialects.KIMI,
                ModelReasoningBehavior.ALWAYS,
                Set.of(ModelReasoningMode.ENABLED),
                Set.of(ModelReasoningEffort.HIGH),
                true);
        register(
                map,
                "kimi",
                "kimi-k2.6",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                OpenAiCompatibleDialects.KIMI,
                ModelReasoningBehavior.OPTIONAL,
                Set.of(ModelReasoningMode.DISABLED, ModelReasoningMode.ENABLED),
                Set.of(ModelReasoningEffort.HIGH),
                true);

        // Zhipu AI - Chat Completions
        register(
                map,
                "zhipu",
                "glm-5.2",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                OpenAiCompatibleDialects.ZHIPU,
                ModelReasoningBehavior.ADAPTIVE,
                Set.of(ModelReasoningMode.DISABLED, ModelReasoningMode.ENABLED, ModelReasoningMode.ADAPTIVE),
                Set.of(ModelReasoningEffort.HIGH, ModelReasoningEffort.MAX),
                true);
        for (String model : Set.of("glm-5.1", "glm-5")) {
            register(
                    map,
                    "zhipu",
                    model,
                    ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                    OpenAiCompatibleDialects.ZHIPU,
                    ModelReasoningBehavior.ADAPTIVE,
                    Set.of(ModelReasoningMode.DISABLED, ModelReasoningMode.ENABLED, ModelReasoningMode.ADAPTIVE),
                    Set.of(ModelReasoningEffort.HIGH),
                    true);
        }
        register(
                map,
                "zhipu",
                "glm-4.7",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                OpenAiCompatibleDialects.ZHIPU,
                ModelReasoningBehavior.OPTIONAL,
                Set.of(ModelReasoningMode.DISABLED, ModelReasoningMode.ENABLED),
                Set.of(ModelReasoningEffort.HIGH),
                true);

        // Zhipu AI - Anthropic Messages
        register(
                map,
                "zhipu",
                "glm-5.2",
                ModelApiStyles.ANTHROPIC_MESSAGES,
                AnthropicMessagesDialects.ZHIPU,
                ModelReasoningBehavior.ADAPTIVE,
                Set.of(ModelReasoningMode.DISABLED, ModelReasoningMode.ENABLED, ModelReasoningMode.ADAPTIVE),
                Set.of(ModelReasoningEffort.HIGH, ModelReasoningEffort.MAX),
                false);

        // OpenAI Codex - Responses
        for (String model : Set.of("gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna")) {
            register(
                    map,
                    "openai-codex",
                    model,
                    ModelApiStyles.OPENAI_RESPONSES,
                    OpenAiResponsesDialects.OPENAI_CODEX,
                    ModelReasoningBehavior.ALWAYS,
                    Set.of(ModelReasoningMode.ENABLED),
                    Set.of(ModelReasoningEffort.HIGH),
                    false);
        }

        // SiliconFlow
        register(
                map,
                "siliconflow",
                "deepseek-ai/DeepSeek-V4-Flash",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                OpenAiCompatibleDialects.SILICONFLOW,
                ModelReasoningBehavior.OPTIONAL,
                Set.of(ModelReasoningMode.DISABLED, ModelReasoningMode.ENABLED),
                Set.of(ModelReasoningEffort.HIGH),
                false);

        // TokenRhythm
        register(
                map,
                "tokenrhythm",
                "deepseek-v4-flash",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                OpenAiCompatibleDialects.TOKENRHYTHM,
                ModelReasoningBehavior.OPTIONAL,
                Set.of(ModelReasoningMode.DISABLED, ModelReasoningMode.ENABLED),
                Set.of(ModelReasoningEffort.HIGH),
                false);

        // Personal Local Acceptance Test Fixture
        register(
                map,
                "personal-local",
                "personal-test",
                ModelApiStyles.DETERMINISTIC_CHAT,
                ModelApiBindingDefinition.STANDARD_DIALECT,
                ModelReasoningBehavior.NONE,
                Set.of(ModelReasoningMode.DISABLED),
                Set.of(),
                false);

        return Map.copyOf(map);
    }

    private static void register(
            Map<AdmissionKey, AdmittedBinding> map,
            String providerId,
            String providerModelId,
            ApiStyleId apiStyle,
            String dialect,
            ModelReasoningBehavior reasoningBehavior,
            Set<ModelReasoningMode> allowedReasoningModes,
            Set<ModelReasoningEffort> allowedReasoningEfforts,
            boolean toolReasoningContinuationRequired) {
        AdmissionKey key = new AdmissionKey(providerId, providerModelId, apiStyle, dialect);
        map.put(
                key,
                new AdmittedBinding(
                        key,
                        reasoningBehavior,
                        allowedReasoningModes,
                        allowedReasoningEfforts,
                        toolReasoningContinuationRequired));
    }
}
