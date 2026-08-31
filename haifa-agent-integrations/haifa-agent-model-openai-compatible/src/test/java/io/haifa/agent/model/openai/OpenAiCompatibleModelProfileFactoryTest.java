package io.haifa.agent.model.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.model.api.ApiStyleId;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelApiBindingDefinition;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelProfileStatus;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ModelReasoningBehavior;
import io.haifa.agent.model.api.ModelReasoningEffort;
import io.haifa.agent.model.api.ModelReasoningMode;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.model.openai.responses.OpenAiResponsesDialects;
import java.net.URI;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleModelProfileFactoryTest {
    @Test
    void verifiesEverySingleAdmittedBindingAcrossAllOpenAiCompatibleRegistries() {
        var openAiChatAdmissions = OpenAiCompatibleBindingRegistry.admissions();
        assertThat(openAiChatAdmissions).hasSize(18);
        for (var admission : openAiChatAdmissions) {
            verifyAdmittedBinding(
                    admission.key().providerId(),
                    admission.key().providerModelId(),
                    admission.key().apiStyle(),
                    admission.key().dialect(),
                    admission.reasoningBehavior(),
                    admission.allowedReasoningModes(),
                    admission.allowedReasoningEfforts(),
                    admission.toolReasoningContinuationRequired());
        }

        var responsesAdmissions = OpenAiResponsesBindingRegistry.admissions();
        assertThat(responsesAdmissions).hasSize(9);
        for (var admission : responsesAdmissions) {
            verifyAdmittedBinding(
                    admission.key().providerId(),
                    admission.key().providerModelId(),
                    admission.key().apiStyle(),
                    admission.key().dialect(),
                    admission.reasoningBehavior(),
                    admission.allowedReasoningModes(),
                    admission.allowedReasoningEfforts(),
                    admission.toolReasoningContinuationRequired());
        }
    }

    @Test
    void duplicateRegistrationThrowsIllegalStateExceptionAcrossRegistries() {
        // Chat registry
        Map<OpenAiCompatibleBindingRegistry.AdmissionKey, OpenAiCompatibleBindingRegistry.AdmittedBinding> chatMap =
                new HashMap<>();
        OpenAiCompatibleBindingRegistry.register(
                chatMap,
                "deepseek",
                "deepseek-chat",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                OpenAiCompatibleDialects.DEEPSEEK,
                ModelReasoningBehavior.OPTIONAL,
                Set.of(ModelReasoningMode.DISABLED),
                Set.of(),
                false);
        assertThatThrownBy(() -> OpenAiCompatibleBindingRegistry.register(
                        chatMap,
                        "deepseek",
                        "deepseek-chat",
                        ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                        OpenAiCompatibleDialects.DEEPSEEK,
                        ModelReasoningBehavior.ALWAYS,
                        Set.of(ModelReasoningMode.ENABLED),
                        Set.of(ModelReasoningEffort.HIGH),
                        true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate model binding admission key");

        // Responses registry
        Map<OpenAiResponsesBindingRegistry.AdmissionKey, OpenAiResponsesBindingRegistry.AdmittedBinding> responsesMap =
                new HashMap<>();
        OpenAiResponsesBindingRegistry.register(
                responsesMap,
                "deepseek",
                "deepseek-v4-flash",
                ModelApiStyles.OPENAI_RESPONSES,
                OpenAiResponsesDialects.DEEPSEEK,
                ModelReasoningBehavior.ALWAYS,
                Set.of(ModelReasoningMode.ENABLED),
                Set.of(ModelReasoningEffort.HIGH),
                true);
        assertThatThrownBy(() -> OpenAiResponsesBindingRegistry.register(
                        responsesMap,
                        "deepseek",
                        "deepseek-v4-flash",
                        ModelApiStyles.OPENAI_RESPONSES,
                        OpenAiResponsesDialects.DEEPSEEK,
                        ModelReasoningBehavior.OPTIONAL,
                        Set.of(ModelReasoningMode.DISABLED),
                        Set.of(),
                        false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate model binding admission key");
    }

    private void verifyAdmittedBinding(
            String providerId,
            String providerModelId,
            ApiStyleId apiStyle,
            String dialect,
            ModelReasoningBehavior expectedReasoningBehavior,
            Set<ModelReasoningMode> expectedModes,
            Set<ModelReasoningEffort> expectedEfforts,
            boolean expectedContinuationRequired) {
        // 1. Reasoning-enabled snapshot (if the model admits reasoning)
        if (expectedReasoningBehavior != ModelReasoningBehavior.NONE) {
            ResolvedModelSnapshot reasoningSnapshot = snapshot(
                    providerId,
                    providerModelId + "-reasoning",
                    providerModelId,
                    apiStyle,
                    dialect,
                    "https://api.example.com/v1");
            var reasoningProfile = profile(reasoningSnapshot);

            assertThat(reasoningProfile.status())
                    .as(
                            "Reasoning profile for %s/%s/%s/%s must be VERIFIED",
                            providerId, providerModelId, apiStyle, dialect)
                    .isEqualTo(ModelProfileStatus.VERIFIED);
            assertThat(reasoningProfile.selectable()).isTrue();
            assertThat(reasoningProfile.reasoningBehavior()).isEqualTo(expectedReasoningBehavior);
            assertThat(reasoningProfile.allowedReasoningModes()).isEqualTo(expectedModes);
            assertThat(reasoningProfile.allowedReasoningEfforts()).isEqualTo(expectedEfforts);
            assertThat(reasoningProfile.toolReasoningContinuationRequired()).isEqualTo(expectedContinuationRequired);
        }

        // 2. Non-reasoning snapshot
        ResolvedModelSnapshot nonReasoningSnapshot = ResolvedModelSnapshot.create(
                new ModelProviderId(providerId),
                "1",
                new ModelDefinitionId(providerModelId + "-non-reasoning"),
                "1",
                providerModelId,
                ModelApiStyles.adapterType(apiStyle),
                "1",
                apiStyle,
                dialect,
                URI.create("https://api.example.com/v1"),
                new CredentialRef("env://TEST_API_KEY"),
                true,
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING),
                1_048_576,
                131_072,
                Map.of(),
                Map.of());
        var nonReasoningProfile = profile(nonReasoningSnapshot);

        assertThat(nonReasoningProfile.status())
                .as(
                        "Non-reasoning profile for %s/%s/%s/%s must be VERIFIED",
                        providerId, providerModelId, apiStyle, dialect)
                .isEqualTo(ModelProfileStatus.VERIFIED);
        assertThat(nonReasoningProfile.selectable()).isTrue();
        assertThat(nonReasoningProfile.reasoningBehavior()).isEqualTo(ModelReasoningBehavior.NONE);
        assertThat(nonReasoningProfile.allowedReasoningModes()).containsExactly(ModelReasoningMode.DISABLED);
        assertThat(nonReasoningProfile.allowedReasoningEfforts()).isEmpty();
        assertThat(nonReasoningProfile.toolReasoningContinuationRequired()).isFalse();
    }

    @Test
    void verifiesOnlyTheReviewedSiliconFlowV4FlashChatBinding() {
        ResolvedModelSnapshot reviewed = snapshot(
                "siliconflow",
                "siliconflow-v4-flash",
                "deepseek-ai/DeepSeek-V4-Flash",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                OpenAiCompatibleDialects.SILICONFLOW,
                "https://api.siliconflow.cn/v1");
        ResolvedModelSnapshot differentModel = snapshot(
                "siliconflow",
                "siliconflow-other",
                "deepseek-ai/DeepSeek-V4-Pro",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                OpenAiCompatibleDialects.SILICONFLOW,
                "https://api.siliconflow.cn/v1");

        assertThat(profile(reviewed).status()).isEqualTo(ModelProfileStatus.VERIFIED);
        assertThat(profile(reviewed).selectable()).isTrue();
        assertThat(profile(differentModel).status()).isEqualTo(ModelProfileStatus.UNVERIFIED);
        assertThat(profile(differentModel).selectable()).isFalse();
    }

    @Test
    void verifiesOnlyTheReviewedTokenRhythmV4FlashChatBinding() {
        ResolvedModelSnapshot reviewed = snapshot(
                "tokenrhythm",
                "tokenrhythm-v4-flash",
                "deepseek-v4-flash",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                OpenAiCompatibleDialects.TOKENRHYTHM,
                "https://tokenrhythm.studio/v1");
        ResolvedModelSnapshot differentModel = snapshot(
                "tokenrhythm",
                "tokenrhythm-other",
                "deepseek-v4-pro",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                OpenAiCompatibleDialects.TOKENRHYTHM,
                "https://tokenrhythm.studio/v1");

        assertThat(profile(reviewed).status()).isEqualTo(ModelProfileStatus.VERIFIED);
        assertThat(profile(reviewed).selectable()).isTrue();
        assertThat(profile(differentModel).status()).isEqualTo(ModelProfileStatus.UNVERIFIED);
        assertThat(profile(differentModel).selectable()).isFalse();
    }

    @Test
    void describesOnlyCapabilitiesVerifiedByCurrentDeepSeekDialect() {
        ResolvedModelSnapshot snapshot = ResolvedModelSnapshot.create(
                new ModelProviderId("deepseek"),
                "1",
                new ModelDefinitionId("deepseek-v4-flash-chat"),
                "1",
                "deepseek-v4-flash",
                ModelApiStyles.OPENAI_CHAT_ADAPTER,
                "1",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                OpenAiCompatibleDialects.DEEPSEEK,
                URI.create("https://api.deepseek.com"),
                new CredentialRef("env://DEEPSEEK_API_KEY"),
                true,
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING, ModelCapability.REASONING),
                65536,
                16384,
                Map.of(),
                Map.of("thinking", "disabled"));

        var profile = OpenAiCompatibleModelProfileFactory.fromSnapshot(snapshot, LocalDate.of(2026, 8, 13));

        assertThat(profile.bindingId()).isEqualTo(snapshot.modelId());
        assertThat(profile.allowedReasoningModes())
                .containsExactlyInAnyOrder(ModelReasoningMode.DISABLED, ModelReasoningMode.ENABLED);
        assertThat(profile.allowedReasoningEfforts())
                .containsExactlyInAnyOrder(ModelReasoningEffort.HIGH, ModelReasoningEffort.MAX);
        assertThat(profile.toolReasoningContinuationRequired()).isTrue();
        assertThat(profile.executionLimits().contextWindowTokens()).isEqualTo(snapshot.contextWindow());
        assertThat(profile.streaming().usageStreaming()).isTrue();
        assertThat(profile.streaming().reasoningStreaming()).isTrue();
        assertThat(profile.digest()).startsWith("sha256:");
    }

    @Test
    void doesNotTrustAnUnknownBindingOnlyBecauseTheProviderIdIsDeepSeek() {
        ResolvedModelSnapshot snapshot = ResolvedModelSnapshot.create(
                new ModelProviderId("deepseek"),
                "1",
                new ModelDefinitionId("deepseek-unknown"),
                "1",
                "future-model",
                ModelApiStyles.OPENAI_CHAT_ADAPTER,
                "1",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                ModelApiBindingDefinition.STANDARD_DIALECT,
                URI.create("https://api.deepseek.com"),
                new CredentialRef("env://DEEPSEEK_API_KEY"),
                true,
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.REASONING),
                65536,
                8192,
                Map.of(),
                Map.of());

        var profile = OpenAiCompatibleModelProfileFactory.fromSnapshot(snapshot, LocalDate.of(2026, 8, 13));

        assertThat(profile.status()).isEqualTo(ModelProfileStatus.UNVERIFIED);
        assertThat(profile.selectable()).isFalse();
    }

    @Test
    void verifiesDeepSeekV4ProResponsesAsAnExactReviewedBinding() {
        ResolvedModelSnapshot snapshot = ResolvedModelSnapshot.create(
                new ModelProviderId("deepseek"),
                "1",
                new ModelDefinitionId("deepseek-v4-pro-responses"),
                "1",
                "deepseek-v4-pro",
                ModelApiStyles.OPENAI_RESPONSES_ADAPTER,
                "1",
                ModelApiStyles.OPENAI_RESPONSES,
                OpenAiResponsesDialects.DEEPSEEK,
                URI.create("https://api.deepseek.com"),
                new CredentialRef("env://DEEPSEEK_API_KEY"),
                true,
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING, ModelCapability.REASONING),
                1_048_576,
                393_216,
                Map.of(),
                Map.of("thinking", "disabled"));

        var profile = OpenAiCompatibleModelProfileFactory.fromSnapshot(snapshot, LocalDate.of(2026, 8, 13));

        assertThat(profile.status()).isEqualTo(ModelProfileStatus.VERIFIED);
        assertThat(profile.selectable()).isTrue();
        assertThat(profile.reasoningBehavior()).isEqualTo(ModelReasoningBehavior.ALWAYS);
        assertThat(profile.allowedReasoningModes()).containsExactly(ModelReasoningMode.ENABLED);
        assertThat(profile.allowedReasoningEfforts()).containsExactly(ModelReasoningEffort.HIGH);
        assertThat(profile.toolReasoningContinuationRequired()).isTrue();
    }

    @Test
    void modelsKimiAlwaysAndSwitchableThinkingWithoutPretendingTheyAreTheSame() {
        var k3 = profile(snapshot(
                "kimi",
                "kimi-k3-chat",
                "kimi-k3",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                OpenAiCompatibleDialects.KIMI,
                "https://api.moonshot.ai/v1"));
        var k26 = profile(snapshot(
                "kimi",
                "kimi-k2-6-chat",
                "kimi-k2.6",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                OpenAiCompatibleDialects.KIMI,
                "https://api.moonshot.ai/v1"));

        assertThat(k3.reasoningBehavior()).isEqualTo(ModelReasoningBehavior.ALWAYS);
        assertThat(k3.allowedReasoningModes()).containsExactly(ModelReasoningMode.ENABLED);
        assertThat(k3.allowedReasoningEfforts())
                .containsExactlyInAnyOrder(
                        ModelReasoningEffort.LOW, ModelReasoningEffort.HIGH, ModelReasoningEffort.MAX);
        assertThat(k26.reasoningBehavior()).isEqualTo(ModelReasoningBehavior.OPTIONAL);
        assertThat(k26.allowedReasoningModes())
                .containsExactlyInAnyOrder(ModelReasoningMode.DISABLED, ModelReasoningMode.ENABLED);
        assertThat(k26.allowedReasoningEfforts()).containsExactly(ModelReasoningEffort.HIGH);
    }

    @Test
    void verifiesOnlyReviewedBailianResponsesAndZhipuChatProfiles() {
        var bailian = profile(snapshot(
                "aliyun-bailian",
                "qwen-plus-responses",
                "qwen3.7-plus",
                ModelApiStyles.OPENAI_RESPONSES,
                OpenAiResponsesDialects.ALIYUN_BAILIAN,
                "https://workspace-123.cn-beijing.maas.aliyuncs.com/compatible-mode/v1"));
        var glm = profile(snapshot(
                "zhipu",
                "glm-5-2-chat",
                "glm-5.2",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                OpenAiCompatibleDialects.ZHIPU,
                "https://open.bigmodel.cn/api/paas/v4"));

        assertThat(bailian.selectable()).isTrue();
        assertThat(bailian.reasoningBehavior()).isEqualTo(ModelReasoningBehavior.ALWAYS);
        assertThat(bailian.allowedReasoningModes()).containsExactly(ModelReasoningMode.ENABLED);
        assertThat(bailian.allowedReasoningEfforts()).containsExactly(ModelReasoningEffort.HIGH);
        assertThat(bailian.toolReasoningContinuationRequired()).isFalse();
        assertThat(glm.reasoningBehavior()).isEqualTo(ModelReasoningBehavior.ADAPTIVE);
        assertThat(glm.allowedReasoningModes())
                .containsExactlyInAnyOrder(
                        ModelReasoningMode.DISABLED, ModelReasoningMode.ENABLED, ModelReasoningMode.ADAPTIVE);
        assertThat(glm.allowedReasoningEfforts())
                .containsExactlyInAnyOrder(ModelReasoningEffort.HIGH, ModelReasoningEffort.MAX);
    }

    @Test
    void verifiesOnlyTheReviewedCodexResponsesModelsAsReadOnlyReasoningBindings() {
        var reviewed = profile(snapshot(
                "openai-codex",
                "gpt-5-6-sol",
                "gpt-5.6-sol",
                ModelApiStyles.OPENAI_RESPONSES,
                OpenAiResponsesDialects.OPENAI_CODEX,
                "https://chatgpt.com/backend-api/codex"));
        var unknown = profile(snapshot(
                "openai-codex",
                "future-codex",
                "future-codex",
                ModelApiStyles.OPENAI_RESPONSES,
                OpenAiResponsesDialects.OPENAI_CODEX,
                "https://chatgpt.com/backend-api/codex"));

        assertThat(reviewed.status()).isEqualTo(ModelProfileStatus.VERIFIED);
        assertThat(reviewed.selectable()).isTrue();
        assertThat(reviewed.reasoningBehavior()).isEqualTo(ModelReasoningBehavior.ALWAYS);
        assertThat(reviewed.allowedReasoningModes()).containsExactly(ModelReasoningMode.ENABLED);
        assertThat(reviewed.allowedReasoningEfforts()).containsExactly(ModelReasoningEffort.HIGH);
        assertThat(reviewed.toolReasoningContinuationRequired()).isFalse();
        assertThat(unknown.status()).isEqualTo(ModelProfileStatus.UNVERIFIED);
        assertThat(unknown.selectable()).isFalse();
    }

    @Test
    void rejectsUnregisteredDeterministicChatOnlyBecauseOfApiStyle() {
        ResolvedModelSnapshot snapshot = snapshot(
                "unregistered-provider",
                "custom-test-binding",
                "custom-model",
                ModelApiStyles.DETERMINISTIC_CHAT,
                ModelApiBindingDefinition.STANDARD_DIALECT,
                "http://127.0.0.1:20999");

        var profile = profile(snapshot);

        assertThat(profile.status()).isEqualTo(ModelProfileStatus.UNVERIFIED);
        assertThat(profile.selectable()).isFalse();
    }

    @Test
    void rejectsUnregisteredStandardDialectNonReasoningModel() {
        ResolvedModelSnapshot arbitrary = ResolvedModelSnapshot.create(
                new ModelProviderId("third-party-openai"),
                "1",
                new ModelDefinitionId("third-party-chat"),
                "1",
                "vendor-chat-model",
                ModelApiStyles.OPENAI_CHAT_ADAPTER,
                "1",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                ModelApiBindingDefinition.STANDARD_DIALECT,
                URI.create("https://gateway.example.com/v1"),
                new CredentialRef("env://THIRD_PARTY_API_KEY"),
                true,
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING),
                131072,
                8192,
                Map.of(),
                Map.of());

        var profile = profile(arbitrary);

        assertThat(profile.status()).isEqualTo(ModelProfileStatus.UNVERIFIED);
        assertThat(profile.selectable()).isFalse();

        ResolvedModelSnapshot openAiLuna = ResolvedModelSnapshot.create(
                new ModelProviderId("openai"),
                "1",
                new ModelDefinitionId("openai-gpt-5.6-luna"),
                "1",
                "gpt-5.6-luna",
                ModelApiStyles.OPENAI_CHAT_ADAPTER,
                "1",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                ModelApiBindingDefinition.STANDARD_DIALECT,
                URI.create("http://localhost:30000/v1"),
                new CredentialRef("env://OPENAI_API_KEY"),
                false,
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING),
                131072,
                8192,
                Map.of(),
                Map.of());

        var lunaProfile = profile(openAiLuna);

        assertThat(lunaProfile.status()).isEqualTo(ModelProfileStatus.UNVERIFIED);
        assertThat(lunaProfile.selectable()).isFalse();
    }

    @Test
    void verifiesExactPersonalLocalOfflineAcceptanceFixture() {
        ResolvedModelSnapshot fixture = ResolvedModelSnapshot.create(
                new ModelProviderId("personal-local"),
                "1",
                new ModelDefinitionId("personal-test"),
                "1",
                "personal-test",
                ModelApiStyles.adapterType(ModelApiStyles.DETERMINISTIC_CHAT),
                "1",
                ModelApiStyles.DETERMINISTIC_CHAT,
                ModelApiBindingDefinition.STANDARD_DIALECT,
                URI.create("http://127.0.0.1:20999"),
                new CredentialRef("env://UNUSED"),
                false,
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING),
                16384,
                1024,
                Map.of(),
                Map.of());

        var profile = profile(fixture);

        assertThat(profile.status()).isEqualTo(ModelProfileStatus.VERIFIED);
        assertThat(profile.selectable()).isTrue();
        assertThat(profile.reasoningBehavior()).isEqualTo(ModelReasoningBehavior.NONE);
    }

    @Test
    void doesNotVerifyMutatedFourTupleDimensionsForVerifiedBindings() {
        // DeepSeek chat completion: providerId mutated
        assertThat(profile(snapshot(
                                "deepseek-fake",
                                "deepseek-v4-flash",
                                "deepseek-v4-flash",
                                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                                OpenAiCompatibleDialects.DEEPSEEK,
                                "https://api.deepseek.com"))
                        .status())
                .isEqualTo(ModelProfileStatus.UNVERIFIED);

        // DeepSeek chat completion: providerModelId mutated
        assertThat(profile(snapshot(
                                "deepseek",
                                "deepseek-unknown",
                                "deepseek-v4-unknown",
                                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                                OpenAiCompatibleDialects.DEEPSEEK,
                                "https://api.deepseek.com"))
                        .status())
                .isEqualTo(ModelProfileStatus.UNVERIFIED);

        // DeepSeek chat completion: apiStyle mutated
        assertThat(profile(snapshot(
                                "deepseek",
                                "deepseek-v4-flash",
                                "deepseek-v4-flash",
                                ModelApiStyles.GOOGLE_GEMINI_GENERATE_CONTENT,
                                OpenAiCompatibleDialects.DEEPSEEK,
                                "https://api.deepseek.com"))
                        .status())
                .isEqualTo(ModelProfileStatus.UNVERIFIED);

        // DeepSeek chat completion: dialect mutated
        assertThat(profile(snapshot(
                                "deepseek",
                                "deepseek-v4-flash",
                                "deepseek-v4-flash",
                                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                                OpenAiCompatibleDialects.KIMI,
                                "https://api.deepseek.com"))
                        .status())
                .isEqualTo(ModelProfileStatus.UNVERIFIED);

        // Bailian chat completion: dialect mutated to standard
        assertThat(profile(snapshot(
                                "aliyun-bailian",
                                "qwen3.7-max",
                                "qwen3.7-max",
                                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                                ModelApiBindingDefinition.STANDARD_DIALECT,
                                "https://example.com"))
                        .status())
                .isEqualTo(ModelProfileStatus.UNVERIFIED);
    }

    @Test
    void rejectsDuplicateAdmissionKeyRegistrationInRegistries() {
        Map<OpenAiCompatibleBindingRegistry.AdmissionKey, OpenAiCompatibleBindingRegistry.AdmittedBinding> openAiMap =
                new java.util.HashMap<>();
        OpenAiCompatibleBindingRegistry.register(
                openAiMap,
                "provider-test",
                "model-test",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                OpenAiCompatibleDialects.DEEPSEEK,
                ModelReasoningBehavior.ALWAYS,
                Set.of(ModelReasoningMode.ENABLED),
                Set.of(ModelReasoningEffort.HIGH),
                false);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> OpenAiCompatibleBindingRegistry.register(
                        openAiMap,
                        "provider-test",
                        "model-test",
                        ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                        OpenAiCompatibleDialects.DEEPSEEK,
                        ModelReasoningBehavior.OPTIONAL,
                        Set.of(ModelReasoningMode.DISABLED),
                        Set.of(),
                        true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate model binding admission key");
    }

    private static io.haifa.agent.model.api.ModelBindingProfile profile(ResolvedModelSnapshot snapshot) {
        return OpenAiCompatibleModelProfileFactory.fromSnapshot(snapshot, LocalDate.of(2026, 8, 13));
    }

    private static ResolvedModelSnapshot snapshot(
            String provider, String binding, String providerModel, ApiStyleId style, String dialect, String endpoint) {
        return ResolvedModelSnapshot.create(
                new ModelProviderId(provider),
                "1",
                new ModelDefinitionId(binding),
                "1",
                providerModel,
                ModelApiStyles.adapterType(style),
                "1",
                style,
                dialect,
                URI.create(endpoint),
                new CredentialRef("env://TEST_API_KEY"),
                true,
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING, ModelCapability.REASONING),
                1_048_576,
                131_072,
                Map.of(),
                Map.of("thinking", "enabled"));
    }
}
