package io.haifa.agent.model.gemini;

import io.haifa.agent.model.api.ApiStyleId;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.util.Objects;
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

    record AdmittedBinding(AdmissionKey key, io.haifa.agent.model.api.ModelIoProfile ioProfile) {
        AdmittedBinding {
            Objects.requireNonNull(key, "key must not be null");
            ioProfile = Objects.requireNonNullElse(ioProfile, io.haifa.agent.model.api.ModelIoProfile.textOnly());
        }
    }

    private static final java.util.Map<AdmissionKey, AdmittedBinding> ADMISSIONS = buildAdmissions();

    private GeminiBindingRegistry() {}

    static java.util.Optional<AdmittedBinding> find(
            String providerId, String providerModelId, ApiStyleId apiStyle, String dialect) {
        if (providerId == null || providerModelId == null || apiStyle == null || dialect == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.ofNullable(
                ADMISSIONS.get(new AdmissionKey(providerId, providerModelId, apiStyle, dialect)));
    }

    static java.util.Optional<AdmittedBinding> find(ResolvedModelSnapshot snapshot) {
        if (snapshot == null || snapshot.providerId() == null) {
            return java.util.Optional.empty();
        }
        return find(snapshot.providerId().value(), snapshot.providerModelId(), snapshot.apiStyle(), snapshot.dialect());
    }

    static boolean isAdmitted(String providerId, String providerModelId, ApiStyleId apiStyle, String dialect) {
        return find(providerId, providerModelId, apiStyle, dialect).isPresent();
    }

    static boolean isAdmitted(ResolvedModelSnapshot snapshot) {
        return find(snapshot).isPresent();
    }

    static java.util.Collection<AdmittedBinding> admissions() {
        return ADMISSIONS.values();
    }

    private static java.util.Map<AdmissionKey, AdmittedBinding> buildAdmissions() {
        var geminiIoProfile =
                io.haifa.agent.model.api.ModelIoProfile.withImage(io.haifa.agent.model.api.ImageInputProfile.gemini(
                        Set.of(io.haifa.agent.model.api.ModelImageSource.UPLOAD)));
        var keyCliproxy = new AdmissionKey(
                "cliproxyapi-antigravity",
                "gemini-3-flash",
                ModelApiStyles.GOOGLE_GEMINI_GENERATE_CONTENT,
                GeminiDialects.CLIPROXYAPI_ANTIGRAVITY);
        var keyDirect = new AdmissionKey(
                "google-antigravity",
                "gemini-3-flash",
                ModelApiStyles.GOOGLE_GEMINI_GENERATE_CONTENT,
                GeminiDialects.ANTIGRAVITY_DIRECT);
        return java.util.Map.of(
                keyCliproxy, new AdmittedBinding(keyCliproxy, geminiIoProfile),
                keyDirect, new AdmittedBinding(keyDirect, geminiIoProfile));
    }
}
