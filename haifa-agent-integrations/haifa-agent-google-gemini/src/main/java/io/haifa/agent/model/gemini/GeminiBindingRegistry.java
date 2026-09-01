package io.haifa.agent.model.gemini;

import io.haifa.agent.model.api.ApiStyleId;
import io.haifa.agent.model.api.ImageInputProfile;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelImageSource;
import io.haifa.agent.model.api.ModelIoProfile;
import io.haifa.agent.model.api.ModelReasoningBehavior;
import io.haifa.agent.model.api.ModelReasoningEffort;
import io.haifa.agent.model.api.ModelReasoningMode;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Authoritative, immutable registry of trusted model binding admissions for Google Gemini integration.
 * Admissions are keyed by the exact 4-tuple: {@code (providerId, providerModelId, apiStyle, dialect)}.
 */
final class GeminiBindingRegistry {
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
            ModelIoProfile ioProfile) {
        AdmittedBinding {
            Objects.requireNonNull(key, "key must not be null");
            reasoningBehavior = Objects.requireNonNullElse(reasoningBehavior, ModelReasoningBehavior.NONE);
            allowedReasoningModes =
                    Objects.requireNonNullElse(allowedReasoningModes, Set.of(ModelReasoningMode.DISABLED));
            allowedReasoningEfforts = Objects.requireNonNullElse(allowedReasoningEfforts, Set.of());
            ioProfile = Objects.requireNonNullElse(ioProfile, ModelIoProfile.textOnly());
        }
    }

    private static final Map<AdmissionKey, AdmittedBinding> ADMISSIONS = buildAdmissions();

    private GeminiBindingRegistry() {}

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

    static boolean isAdmitted(ResolvedModelSnapshot snapshot) {
        return find(snapshot).isPresent();
    }

    static Collection<AdmittedBinding> admissions() {
        return ADMISSIONS.values();
    }

    private static Map<AdmissionKey, AdmittedBinding> buildAdmissions() {
        var geminiIoProfile = ModelIoProfile.withImage(ImageInputProfile.gemini(Set.of(ModelImageSource.UPLOAD)));

        // gemini-3.6-flash (supports thinking, high effort)
        var direct36Key = new AdmissionKey(
                "google-antigravity",
                "gemini-3.6-flash",
                ModelApiStyles.GOOGLE_GEMINI_GENERATE_CONTENT,
                GeminiDialects.ANTIGRAVITY_DIRECT);

        // gemini-3.7-flash (supports hybrid thinking, low/medium/high effort)
        var direct37Key = new AdmissionKey(
                "google-antigravity",
                "gemini-3.7-flash",
                ModelApiStyles.GOOGLE_GEMINI_GENERATE_CONTENT,
                GeminiDialects.ANTIGRAVITY_DIRECT);

        var efforts37 = Set.of(ModelReasoningEffort.LOW, ModelReasoningEffort.MEDIUM, ModelReasoningEffort.HIGH);
        var modes = Set.of(ModelReasoningMode.DISABLED, ModelReasoningMode.ENABLED);

        return Map.of(
                direct36Key,
                        new AdmittedBinding(
                                direct36Key,
                                ModelReasoningBehavior.OPTIONAL,
                                modes,
                                Set.of(ModelReasoningEffort.HIGH),
                                geminiIoProfile),
                direct37Key,
                        new AdmittedBinding(
                                direct37Key, ModelReasoningBehavior.OPTIONAL, modes, efforts37, geminiIoProfile));
    }
}
