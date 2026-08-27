package io.haifa.agent.model.openai;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleModelProfileFactoryTest {
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
                "deepseek-ai/DeepSeek-V4-Flash",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                OpenAiCompatibleDialects.TOKENRHYTHM,
                "https://api.tokenrhythm.com/v1");
        ResolvedModelSnapshot differentModel = snapshot(
                "tokenrhythm",
                "tokenrhythm-other",
                "deepseek-ai/DeepSeek-V4-Pro",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                OpenAiCompatibleDialects.TOKENRHYTHM,
                "https://api.tokenrhythm.com/v1");

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

    private static io.haifa.agent.model.api.ModelBindingProfile profile(ResolvedModelSnapshot snapshot) {
        return OpenAiCompatibleModelProfileFactory.fromSnapshot(snapshot, LocalDate.of(2026, 8, 13));
    }

    private static ResolvedModelSnapshot snapshot(
            String provider,
            String binding,
            String providerModel,
            io.haifa.agent.model.api.ApiStyleId style,
            String dialect,
            String endpoint) {
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
