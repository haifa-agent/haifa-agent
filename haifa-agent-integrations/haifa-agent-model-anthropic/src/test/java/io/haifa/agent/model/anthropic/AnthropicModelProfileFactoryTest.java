package io.haifa.agent.model.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.model.api.ApiStyleId;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelBindingProfile;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelProfileStatus;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ModelReasoningBehavior;
import io.haifa.agent.model.api.ModelReasoningEffort;
import io.haifa.agent.model.api.ModelReasoningMode;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.net.URI;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AnthropicModelProfileFactoryTest {
    private static final LocalDate VERIFIED_ON = LocalDate.of(2026, 8, 30);

    @ParameterizedTest
    @CsvSource({
        "deepseek, deepseek-v4-flash, anthropic-messages, deepseek-anthropic-messages",
        "deepseek, deepseek-v4-pro, anthropic-messages, deepseek-anthropic-messages"
    })
    void admitsDeepSeekAnthropicMessagesProfiles(String provider, String model, String style, String dialect) {
        ResolvedModelSnapshot snapshot = snapshot(
                provider,
                model,
                style,
                dialect,
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING, ModelCapability.REASONING));

        ModelBindingProfile profile = AnthropicModelProfileFactory.fromSnapshot(snapshot, VERIFIED_ON);

        assertThat(profile.status()).isEqualTo(ModelProfileStatus.VERIFIED);
        assertThat(profile.selectable()).isTrue();
        assertThat(profile.version()).isEqualTo(AnthropicModelProfileFactory.CURRENT_PROFILE_VERSION);
        assertThat(profile.reasoningBehavior()).isEqualTo(ModelReasoningBehavior.ALWAYS);
        assertThat(profile.allowedReasoningModes()).containsExactly(ModelReasoningMode.ENABLED);
        assertThat(profile.allowedReasoningEfforts()).containsExactly(ModelReasoningEffort.HIGH);
        assertThat(profile.toolReasoningContinuationRequired()).isTrue();
    }

    @Test
    void admitsZhipuAnthropicMessagesProfile() {
        ResolvedModelSnapshot snapshot = snapshot(
                "zhipu",
                "glm-5.2",
                ModelApiStyles.ANTHROPIC_MESSAGES.value(),
                AnthropicMessagesDialects.ZHIPU,
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING, ModelCapability.REASONING));

        ModelBindingProfile profile = AnthropicModelProfileFactory.fromSnapshot(snapshot, VERIFIED_ON);

        assertThat(profile.status()).isEqualTo(ModelProfileStatus.VERIFIED);
        assertThat(profile.selectable()).isTrue();
        assertThat(profile.reasoningBehavior()).isEqualTo(ModelReasoningBehavior.ADAPTIVE);
        assertThat(profile.allowedReasoningModes())
                .containsExactlyInAnyOrder(
                        ModelReasoningMode.DISABLED, ModelReasoningMode.ENABLED, ModelReasoningMode.ADAPTIVE);
        assertThat(profile.allowedReasoningEfforts())
                .containsExactlyInAnyOrder(ModelReasoningEffort.HIGH, ModelReasoningEffort.MAX);
        assertThat(profile.toolReasoningContinuationRequired()).isFalse();
    }

    @Test
    void nonReasoningSnapshotWithAdmittedKeyReturnsVerifiedNonReasoningProfile() {
        ResolvedModelSnapshot snapshot = snapshot(
                "deepseek",
                "deepseek-v4-flash",
                ModelApiStyles.ANTHROPIC_MESSAGES.value(),
                AnthropicMessagesDialects.DEEPSEEK,
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING));

        ModelBindingProfile profile = AnthropicModelProfileFactory.fromSnapshot(snapshot, VERIFIED_ON);

        assertThat(profile.status()).isEqualTo(ModelProfileStatus.VERIFIED);
        assertThat(profile.selectable()).isTrue();
        assertThat(profile.reasoningBehavior()).isEqualTo(ModelReasoningBehavior.NONE);
        assertThat(profile.allowedReasoningModes()).containsExactly(ModelReasoningMode.DISABLED);
        assertThat(profile.allowedReasoningEfforts()).isEmpty();
        assertThat(profile.toolReasoningContinuationRequired()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
        "unknown-provider, deepseek-v4-flash, anthropic-messages, deepseek-anthropic-messages",
        "deepseek, unknown-model, anthropic-messages, deepseek-anthropic-messages",
        "deepseek, deepseek-v4-flash, openai-chat-completions, deepseek-anthropic-messages",
        "deepseek, deepseek-v4-flash, anthropic-messages, standard",
        "deepseek, deepseek-v4-flash, anthropic-messages, zhipu-anthropic-messages",
        "zhipu, glm-5, anthropic-messages, zhipu-anthropic-messages",
        "zhipu, glm-5.2, anthropic-messages, deepseek-anthropic-messages",
        "aliyun-bailian, qwen3.7-max, anthropic-messages, standard"
    })
    void rejectsUnadmitted4TupleMutations(String provider, String model, String style, String dialect) {
        ResolvedModelSnapshot snapshot = snapshot(
                provider,
                model,
                style,
                dialect,
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING, ModelCapability.REASONING));

        ModelBindingProfile profile = AnthropicModelProfileFactory.fromSnapshot(snapshot, VERIFIED_ON);

        assertThat(profile.status()).isEqualTo(ModelProfileStatus.UNVERIFIED);
        assertThat(profile.selectable()).isFalse();
    }

    @Test
    void duplicateRegistrationThrowsIllegalStateException() {
        Map<AnthropicMessagesBindingRegistry.AdmissionKey, AnthropicMessagesBindingRegistry.AdmittedBinding> map =
                new HashMap<>();
        AnthropicMessagesBindingRegistry.register(
                map,
                "deepseek",
                "deepseek-v4-flash",
                ModelApiStyles.ANTHROPIC_MESSAGES,
                AnthropicMessagesDialects.DEEPSEEK,
                ModelReasoningBehavior.ALWAYS,
                Set.of(ModelReasoningMode.ENABLED),
                Set.of(ModelReasoningEffort.HIGH),
                true);

        assertThatThrownBy(() -> AnthropicMessagesBindingRegistry.register(
                        map,
                        "deepseek",
                        "deepseek-v4-flash",
                        ModelApiStyles.ANTHROPIC_MESSAGES,
                        AnthropicMessagesDialects.DEEPSEEK,
                        ModelReasoningBehavior.ALWAYS,
                        Set.of(ModelReasoningMode.ENABLED),
                        Set.of(ModelReasoningEffort.HIGH),
                        true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate model binding admission key");
    }

    private static ResolvedModelSnapshot snapshot(
            String providerId,
            String providerModelId,
            String apiStyle,
            String dialect,
            Set<ModelCapability> capabilities) {
        return ResolvedModelSnapshot.create(
                new ModelProviderId(providerId),
                "1.0.0",
                new ModelDefinitionId(providerId + "-" + providerModelId),
                "1.0.0",
                providerModelId,
                AnthropicMessagesModel.ADAPTER_TYPE,
                AnthropicMessagesModel.ADAPTER_VERSION,
                new ApiStyleId(apiStyle),
                dialect,
                URI.create("https://api." + providerId + ".com/anthropic"),
                new CredentialRef("env://TEST_KEY"),
                true,
                capabilities,
                1_048_576,
                8_192,
                Map.of(),
                Map.of());
    }
}
