package io.haifa.agent.model.gemini;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelProfileStatus;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ModelReasoningBehavior;
import io.haifa.agent.model.api.ModelReasoningMode;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.net.URI;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GeminiModelProfileFactoryTest {
    @Test
    void verifiesAllRegisteredGovernedGeminiAdmissions() {
        var admissions = GeminiBindingRegistry.admissions();
        assertThat(admissions).hasSize(2);

        for (var admission : admissions) {
            ResolvedModelSnapshot reasoningSnapshot = ResolvedModelSnapshot.create(
                    new ModelProviderId(admission.key().providerId()),
                    "1",
                    new ModelDefinitionId("gemini-test"),
                    "1",
                    admission.key().providerModelId(),
                    ModelApiStyles.GOOGLE_GEMINI_ADAPTER,
                    GeminiGenerateContentModel.ADAPTER_VERSION,
                    admission.key().apiStyle(),
                    admission.key().dialect(),
                    URI.create("https://daily-cloudcode-pa.googleapis.com/v1internal"),
                    new CredentialRef("model-auth://google-antigravity/default"),
                    true,
                    EnumSet.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING, ModelCapability.REASONING),
                    131072,
                    8192,
                    Map.of(),
                    Map.of());

            var reasoningProfile = GeminiModelProfileFactory.fromSnapshot(reasoningSnapshot, LocalDate.of(2026, 8, 24));
            assertThat(reasoningProfile.status())
                    .as("Admitted Gemini binding %s must be VERIFIED", admission)
                    .isEqualTo(ModelProfileStatus.VERIFIED);
            assertThat(reasoningProfile.selectable()).isTrue();
            assertThat(reasoningProfile.reasoningBehavior()).isEqualTo(ModelReasoningBehavior.OPTIONAL);
            assertThat(reasoningProfile.allowedReasoningModes()).contains(ModelReasoningMode.ENABLED);
            assertThat(reasoningProfile.toolReasoningContinuationRequired()).isTrue();
            assertThat(reasoningProfile.executionLimits().contextWindowTokens())
                    .isEqualTo(reasoningSnapshot.contextWindow());
            assertThat(reasoningProfile.streaming().usageStreaming()).isTrue();
            assertThat(reasoningProfile.streaming().reasoningStreaming()).isFalse();
            assertThat(reasoningProfile.imageInput()).isPresent();
            assertThat(reasoningProfile.imageInput().get().maxTotalBytes()).isEqualTo(12 * 1024 * 1024L);

            ResolvedModelSnapshot nonReasoningSnapshot = ResolvedModelSnapshot.create(
                    new ModelProviderId(admission.key().providerId()),
                    "1",
                    new ModelDefinitionId("gemini-test-non-reasoning"),
                    "1",
                    admission.key().providerModelId(),
                    ModelApiStyles.GOOGLE_GEMINI_ADAPTER,
                    GeminiGenerateContentModel.ADAPTER_VERSION,
                    admission.key().apiStyle(),
                    admission.key().dialect(),
                    URI.create("https://daily-cloudcode-pa.googleapis.com/v1internal"),
                    new CredentialRef("model-auth://google-antigravity/default"),
                    true,
                    EnumSet.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING),
                    131072,
                    8192,
                    Map.of(),
                    Map.of());

            var nonReasoningProfile =
                    GeminiModelProfileFactory.fromSnapshot(nonReasoningSnapshot, LocalDate.of(2026, 8, 24));
            assertThat(nonReasoningProfile.status())
                    .as("Admitted Gemini binding without reasoning %s must be VERIFIED", admission)
                    .isEqualTo(ModelProfileStatus.VERIFIED);
            assertThat(nonReasoningProfile.selectable()).isTrue();
            assertThat(nonReasoningProfile.reasoningBehavior()).isEqualTo(ModelReasoningBehavior.NONE);
            assertThat(nonReasoningProfile.allowedReasoningModes()).containsExactly(ModelReasoningMode.DISABLED);
            assertThat(nonReasoningProfile.toolReasoningContinuationRequired()).isFalse();
            assertThat(nonReasoningProfile.imageInput()).isPresent();
            assertThat(nonReasoningProfile.imageInput().get().maxTotalBytes()).isEqualTo(12 * 1024 * 1024L);
        }
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
                snapshot("google-gemini", GeminiDialects.STANDARD, "gemini-3.7-flash"), LocalDate.of(2026, 8, 27));

        assertThat(profile.status()).isEqualTo(ModelProfileStatus.UNVERIFIED);
        assertThat(profile.selectable()).isFalse();
    }

    @Test
    void doesNotVerifyUnknownProviderModelIdAcrossAllGovernedDialects() {
        for (var entry : Map.of(
                        "google-gemini", GeminiDialects.STANDARD,
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
                snapshot("google-gemini", GeminiDialects.ANTIGRAVITY_DIRECT, "gemini-3.7-flash"),
                LocalDate.of(2026, 8, 24));
        assertThat(mismatchedDialect.status()).isEqualTo(ModelProfileStatus.UNVERIFIED);
        assertThat(mismatchedDialect.selectable()).isFalse();

        var mismatchedProvider = GeminiModelProfileFactory.fromSnapshot(
                snapshot("google-antigravity", GeminiDialects.STANDARD, "gemini-3.7-flash"), LocalDate.of(2026, 8, 24));
        assertThat(mismatchedProvider.status()).isEqualTo(ModelProfileStatus.UNVERIFIED);
        assertThat(mismatchedProvider.selectable()).isFalse();

        var mismatchedStyle = ResolvedModelSnapshot.create(
                new ModelProviderId("google-gemini"),
                "1",
                new ModelDefinitionId("gemini"),
                "1",
                "gemini-3.7-flash",
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
        return snapshot(provider, dialect, "gemini-3.7-flash");
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
                URI.create("https://daily-cloudcode-pa.googleapis.com/v1internal"),
                new CredentialRef("model-auth://google-antigravity/default"),
                true,
                EnumSet.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING, ModelCapability.REASONING),
                131072,
                8192,
                Map.of(),
                Map.of());
    }
}
