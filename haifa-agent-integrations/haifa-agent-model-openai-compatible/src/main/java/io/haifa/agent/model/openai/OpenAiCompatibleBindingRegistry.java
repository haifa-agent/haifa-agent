package io.haifa.agent.model.openai;

import io.haifa.agent.model.api.ApiStyleId;
import io.haifa.agent.model.api.ModelApiBindingDefinition;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelReasoningBehavior;
import io.haifa.agent.model.api.ModelReasoningEffort;
import io.haifa.agent.model.api.ModelReasoningMode;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Authoritative, immutable registry of trusted model binding admissions for OpenAI-compatible chat providers.
 * Admissions are keyed by the exact 4-tuple: {@code (providerId, providerModelId, apiStyle, dialect)}.
 */
final class OpenAiCompatibleBindingRegistry {
    record AdmissionKey(String providerId, String providerModelId, ApiStyleId apiStyle, String dialect) {
        AdmissionKey {
            Objects.requireNonNull(providerId, "providerId must not be null");
            Objects.requireNonNull(providerModelId, "providerModelId must not be null");
            Objects.requireNonNull(apiStyle, "apiStyle must not be null");
            Objects.requireNonNull(dialect, "dialect must not be null");
        }
    }

    record AdmittedBinding(
            AdmissionKey key,
            ModelReasoningBehavior reasoningBehavior,
            Set<ModelReasoningMode> allowedReasoningModes,
            Set<ModelReasoningEffort> allowedReasoningEfforts,
            boolean toolReasoningContinuationRequired,
            io.haifa.agent.model.api.ModelIoProfile ioProfile) {
        AdmittedBinding {
            Objects.requireNonNull(key, "key must not be null");
            Objects.requireNonNull(reasoningBehavior, "reasoningBehavior must not be null");
            allowedReasoningModes = Set.copyOf(allowedReasoningModes);
            allowedReasoningEfforts = Set.copyOf(allowedReasoningEfforts);
            ioProfile = Objects.requireNonNullElse(ioProfile, io.haifa.agent.model.api.ModelIoProfile.textOnly());
        }
    }

    private static final Map<AdmissionKey, AdmittedBinding> ADMISSIONS = buildAdmissions();

    private OpenAiCompatibleBindingRegistry() {}

    static Optional<AdmittedBinding> find(
            String providerId, String providerModelId, ApiStyleId apiStyle, String dialect) {
        if (providerId == null || providerModelId == null || apiStyle == null || dialect == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(ADMISSIONS.get(new AdmissionKey(providerId, providerModelId, apiStyle, dialect)));
    }

    static Optional<AdmittedBinding> find(ResolvedModelSnapshot snapshot) {
        if (snapshot == null || snapshot.providerId() == null) {
            return Optional.empty();
        }
        return find(snapshot.providerId().value(), snapshot.providerModelId(), snapshot.apiStyle(), snapshot.dialect());
    }

    static boolean isAdmitted(String providerId, String providerModelId, ApiStyleId apiStyle, String dialect) {
        return find(providerId, providerModelId, apiStyle, dialect).isPresent();
    }

    static Collection<AdmittedBinding> admissions() {
        return ADMISSIONS.values();
    }

    private static Map<AdmissionKey, AdmittedBinding> buildAdmissions() {
        Map<AdmissionKey, AdmittedBinding> map = new HashMap<>();

        // DeepSeek - Chat Completions
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
        }
        register(
                map,
                "deepseek",
                "deepseek-v4-flash-vision-exp",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                OpenAiCompatibleDialects.DEEPSEEK,
                ModelReasoningBehavior.NONE,
                Set.of(ModelReasoningMode.DISABLED),
                Set.of(),
                false,
                io.haifa.agent.model.api.ModelIoProfile.withImage(io.haifa.agent.model.api.ImageInputProfile.standard(
                        Set.of(
                                io.haifa.agent.model.api.ModelImageSource.UPLOAD,
                                io.haifa.agent.model.api.ModelImageSource.URL),
                        true)));

        // Alibaba Cloud Bailian - Chat Completions
        for (String model : Set.of(
                "qwen3.8-max-preview", "qwen3.7-max", "qwen3.7-max-2026-05-17", "qwen3.7-plus", "qwen3.7-flash")) {
            register(
                    map,
                    "aliyun-bailian",
                    model,
                    ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                    OpenAiCompatibleDialects.ALIYUN_BAILIAN,
                    ModelReasoningBehavior.OPTIONAL,
                    Set.of(ModelReasoningMode.DISABLED, ModelReasoningMode.ENABLED),
                    Set.of(ModelReasoningEffort.HIGH, ModelReasoningEffort.MAX),
                    true,
                    io.haifa.agent.model.api.ModelIoProfile.textOnly());
        }
        register(
                map,
                "aliyun-bailian",
                "qwen3-vl-plus",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                OpenAiCompatibleDialects.ALIYUN_BAILIAN,
                ModelReasoningBehavior.OPTIONAL,
                Set.of(ModelReasoningMode.DISABLED, ModelReasoningMode.ENABLED),
                Set.of(ModelReasoningEffort.HIGH, ModelReasoningEffort.MAX),
                true,
                io.haifa.agent.model.api.ModelIoProfile.withImage(io.haifa.agent.model.api.ImageInputProfile.standard(
                        Set.of(
                                io.haifa.agent.model.api.ModelImageSource.UPLOAD,
                                io.haifa.agent.model.api.ModelImageSource.URL),
                        true)));

        // Kimi / Moonshot - Chat Completions
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

        // SiliconFlow - Chat Completions
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

        // TokenRhythm - Chat Completions
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
                false,
                io.haifa.agent.model.api.ModelIoProfile.withImage(io.haifa.agent.model.api.ImageInputProfile.standard(
                        Set.of(
                                io.haifa.agent.model.api.ModelImageSource.UPLOAD,
                                io.haifa.agent.model.api.ModelImageSource.URL),
                        true)));

        return Map.copyOf(map);
    }

    static void register(
            Map<AdmissionKey, AdmittedBinding> map,
            String providerId,
            String providerModelId,
            ApiStyleId apiStyle,
            String dialect,
            ModelReasoningBehavior reasoningBehavior,
            Set<ModelReasoningMode> allowedReasoningModes,
            Set<ModelReasoningEffort> allowedReasoningEfforts,
            boolean toolReasoningContinuationRequired) {
        register(
                map,
                providerId,
                providerModelId,
                apiStyle,
                dialect,
                reasoningBehavior,
                allowedReasoningModes,
                allowedReasoningEfforts,
                toolReasoningContinuationRequired,
                io.haifa.agent.model.api.ModelIoProfile.textOnly());
    }

    static void register(
            Map<AdmissionKey, AdmittedBinding> map,
            String providerId,
            String providerModelId,
            ApiStyleId apiStyle,
            String dialect,
            ModelReasoningBehavior reasoningBehavior,
            Set<ModelReasoningMode> allowedReasoningModes,
            Set<ModelReasoningEffort> allowedReasoningEfforts,
            boolean toolReasoningContinuationRequired,
            io.haifa.agent.model.api.ModelIoProfile ioProfile) {
        AdmissionKey key = new AdmissionKey(providerId, providerModelId, apiStyle, dialect);
        AdmittedBinding binding = new AdmittedBinding(
                key,
                reasoningBehavior,
                allowedReasoningModes,
                allowedReasoningEfforts,
                toolReasoningContinuationRequired,
                ioProfile);
        AdmittedBinding existing = map.putIfAbsent(key, binding);
        if (existing != null) {
            throw new IllegalStateException("Duplicate model binding admission key: " + key);
        }
    }
}
