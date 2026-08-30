package io.haifa.agent.model.gemini;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelProfileStatus;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.net.URI;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GeminiModelProfileFactoryTest {
    @Test
    void verifiesGovernedLocalDialectAndRequiresProtectedContinuation() {
        var profile = GeminiModelProfileFactory.fromSnapshot(
                snapshot("cliproxyapi-antigravity", GeminiDialects.CLIPROXYAPI_ANTIGRAVITY), LocalDate.of(2026, 8, 24));
        assertThat(profile.status()).isEqualTo(ModelProfileStatus.VERIFIED);
        assertThat(profile.toolReasoningContinuationRequired()).isTrue();
        assertThat(profile.selectable()).isTrue();
    }

    @Test
    void verifiesGovernedAntigravityDirectDialect() {
        var profile = GeminiModelProfileFactory.fromSnapshot(
                snapshot("google-antigravity", GeminiDialects.ANTIGRAVITY_DIRECT), LocalDate.of(2026, 8, 27));

        assertThat(profile.status()).isEqualTo(ModelProfileStatus.VERIFIED);
        assertThat(profile.toolReasoningContinuationRequired()).isTrue();
        assertThat(profile.selectable()).isTrue();
    }

    @Test
    void rejectsStandardDialectWithoutIndependentLiveEvidence() {
        var profile = GeminiModelProfileFactory.fromSnapshot(
                snapshot("google-gemini", GeminiDialects.STANDARD, "gemini-3-flash"), LocalDate.of(2026, 8, 27));

        assertThat(profile.status()).isEqualTo(ModelProfileStatus.UNVERIFIED);
        assertThat(profile.selectable()).isFalse();
    }

    @Test
    void doesNotVerifyUnknownProviderModelIdAcrossAllGovernedDialects() {
        for (var entry : Map.of(
                        "google-gemini", GeminiDialects.STANDARD,
                        "cliproxyapi-antigravity", GeminiDialects.CLIPROXYAPI_ANTIGRAVITY,
                        "google-antigravity", GeminiDialects.ANTIGRAVITY_DIRECT)
                .entrySet()) {
            for (String unknownModel : Set.of("gemini-1.5-pro", "future-gemini", "gemini-test", "gemini-2.5-flash")) {
                var profile = GeminiModelProfileFactory.fromSnapshot(
                        snapshot(entry.getKey(), entry.getValue(), unknownModel), LocalDate.of(2026, 8, 24));
                assertThat(profile.status())
                        .as("Provider %s with model %s must fail closed", entry.getKey(), unknownModel)
                        .isEqualTo(ModelProfileStatus.UNVERIFIED);
                assertThat(profile.selectable()).isFalse();
            }
        }
    }

    @Test
    void doesNotVerifyMismatchedProviderDialectOrStyle() {
        var mismatchedDialect = GeminiModelProfileFactory.fromSnapshot(
                snapshot("google-gemini", GeminiDialects.CLIPROXYAPI_ANTIGRAVITY, "gemini-3-flash"),
                LocalDate.of(2026, 8, 24));
        assertThat(mismatchedDialect.status()).isEqualTo(ModelProfileStatus.UNVERIFIED);
        assertThat(mismatchedDialect.selectable()).isFalse();

        var mismatchedProvider = GeminiModelProfileFactory.fromSnapshot(
                snapshot("cliproxyapi-antigravity", GeminiDialects.STANDARD, "gemini-3-flash"),
                LocalDate.of(2026, 8, 24));
        assertThat(mismatchedProvider.status()).isEqualTo(ModelProfileStatus.UNVERIFIED);
        assertThat(mismatchedProvider.selectable()).isFalse();

        var mismatchedStyle = ResolvedModelSnapshot.create(
                new ModelProviderId("google-gemini"),
                "1",
                new ModelDefinitionId("gemini"),
                "1",
                "gemini-3-flash",
                ModelApiStyles.OPENAI_CHAT_ADAPTER,
                "1",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                GeminiDialects.STANDARD,
                URI.create("https://generativelanguage.googleapis.com/v1beta"),
                new CredentialRef("env://GEMINI_API_KEY"),
                true,
                EnumSet.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING),
                131072,
                8192,
                Map.of(),
                Map.of());
        var styleProfile = GeminiModelProfileFactory.fromSnapshot(mismatchedStyle, LocalDate.of(2026, 8, 24));
        assertThat(styleProfile.status()).isEqualTo(ModelProfileStatus.UNVERIFIED);
        assertThat(styleProfile.selectable()).isFalse();
    }

    private static ResolvedModelSnapshot snapshot(String provider, String dialect) {
        return snapshot(provider, dialect, "gemini-3-flash");
    }

    private static ResolvedModelSnapshot snapshot(String provider, String dialect, String providerModelId) {
        return ResolvedModelSnapshot.create(
                new ModelProviderId(provider),
                "1",
                new ModelDefinitionId("gemini"),
                "1",
                providerModelId,
                ModelApiStyles.GOOGLE_GEMINI_ADAPTER,
                GeminiGenerateContentModel.ADAPTER_VERSION,
                ModelApiStyles.GOOGLE_GEMINI_GENERATE_CONTENT,
                dialect,
                URI.create("http://127.0.0.1:8317/v1beta"),
                new CredentialRef(GeminiGenerateContentModel.CLIPROXY_CREDENTIAL_REF),
                true,
                EnumSet.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING, ModelCapability.REASONING),
                131072,
                8192,
                Map.of(),
                Map.of());
    }
}
