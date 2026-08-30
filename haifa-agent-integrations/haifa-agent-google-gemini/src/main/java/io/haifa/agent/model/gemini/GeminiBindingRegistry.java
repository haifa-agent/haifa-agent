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

    private static final Set<AdmissionKey> ADMISSIONS = buildAdmissions();

    private GeminiBindingRegistry() {}

    static boolean isAdmitted(String providerId, String providerModelId, ApiStyleId apiStyle, String dialect) {
        if (providerId == null || providerModelId == null || apiStyle == null || dialect == null) {
            return false;
        }
        return ADMISSIONS.contains(new AdmissionKey(providerId, providerModelId, apiStyle, dialect));
    }

    static boolean isAdmitted(ResolvedModelSnapshot snapshot) {
        if (snapshot == null || snapshot.providerId() == null) {
            return false;
        }
        return isAdmitted(
                snapshot.providerId().value(), snapshot.providerModelId(), snapshot.apiStyle(), snapshot.dialect());
    }

    static Set<AdmissionKey> admissions() {
        return ADMISSIONS;
    }

    private static Set<AdmissionKey> buildAdmissions() {
        return Set.of(
                new AdmissionKey(
                        "cliproxyapi-antigravity",
                        "gemini-3-flash",
                        ModelApiStyles.GOOGLE_GEMINI_GENERATE_CONTENT,
                        GeminiDialects.CLIPROXYAPI_ANTIGRAVITY),
                new AdmissionKey(
                        "google-antigravity",
                        "gemini-3-flash",
                        ModelApiStyles.GOOGLE_GEMINI_GENERATE_CONTENT,
                        GeminiDialects.ANTIGRAVITY_DIRECT));
    }
}
