package io.haifa.agent.model.anthropic;

import io.haifa.agent.model.api.ApiStyleId;
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
 * Authoritative, immutable registry of trusted model binding admissions for Anthropic Messages integration.
 * Admissions are keyed by the exact 4-tuple: {@code (providerId, providerModelId, apiStyle, dialect)}.
 */
final class AnthropicMessagesBindingRegistry {
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
            boolean toolReasoningContinuationRequired) {
        AdmittedBinding {
            Objects.requireNonNull(key, "key must not be null");
            Objects.requireNonNull(reasoningBehavior, "reasoningBehavior must not be null");
            allowedReasoningModes = Set.copyOf(allowedReasoningModes);
            allowedReasoningEfforts = Set.copyOf(allowedReasoningEfforts);
        }
    }

    private static final Map<AdmissionKey, AdmittedBinding> ADMISSIONS = buildAdmissions();

    private AnthropicMessagesBindingRegistry() {}

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

        // DeepSeek Anthropic Messages
        for (String model : Set.of("deepseek-v4-flash", "deepseek-v4-pro")) {
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

        // Zhipu Anthropic Messages
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
        AdmissionKey key = new AdmissionKey(providerId, providerModelId, apiStyle, dialect);
        AdmittedBinding binding = new AdmittedBinding(
                key,
                reasoningBehavior,
                allowedReasoningModes,
                allowedReasoningEfforts,
                toolReasoningContinuationRequired);
        AdmittedBinding existing = map.putIfAbsent(key, binding);
        if (existing != null) {
            throw new IllegalStateException("Duplicate model binding admission key: " + key);
        }
    }
}
