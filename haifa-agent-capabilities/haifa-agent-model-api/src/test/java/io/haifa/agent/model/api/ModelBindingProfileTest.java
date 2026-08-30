package io.haifa.agent.model.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModelBindingProfileTest {

    @Test
    void standardChatBindingProfileProducesExpectedCanonicalStringAndDigest() {
        ModelBindingProfile profile = ModelBindingProfile.create(
                new ModelDefinitionId("aliyun-qwen3.7-max"),
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                "1.0",
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING),
                ModelReasoningBehavior.NONE,
                Set.of(ModelReasoningMode.DISABLED),
                Set.of(),
                OptionalLong.empty(),
                1,
                8192,
                false,
                ModelProfileStatus.VERIFIED,
                LocalDate.of(2026, 8, 13));

        assertThat(profile.canonicalString())
                .isEqualTo("model-binding-profile-v1|aliyun-qwen3.7-max|openai-chat-completions|1.0|"
                        + "[TEXT_CHAT, TOOL_CALLING]|NONE|[DISABLED]|[]|none|1|8192|false|VERIFIED|2026-08-13");
        assertThat(profile.digest()).startsWith("sha256:");
        assertThat(profile.selectable()).isTrue();
    }

    @Test
    void optionalReasoningBindingProfileProducesExpectedCanonicalStringAndDigest() {
        ModelBindingProfile profile = ModelBindingProfile.create(
                new ModelDefinitionId("deepseek-chat-flash"),
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                "1.0",
                Set.of(ModelCapability.REASONING, ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING),
                ModelReasoningBehavior.OPTIONAL,
                Set.of(ModelReasoningMode.DISABLED, ModelReasoningMode.ENABLED),
                Set.of(ModelReasoningEffort.HIGH),
                OptionalLong.empty(),
                1,
                8192,
                false,
                ModelProfileStatus.VERIFIED,
                LocalDate.of(2026, 8, 13));

        assertThat(profile.canonicalString())
                .isEqualTo(
                        "model-binding-profile-v1|deepseek-chat-flash|openai-chat-completions|1.0|"
                                + "[REASONING, TEXT_CHAT, TOOL_CALLING]|OPTIONAL|[DISABLED, ENABLED]|[HIGH]|none|1|8192|false|VERIFIED|2026-08-13");
        assertThat(profile.digest()).startsWith("sha256:");
        assertThat(profile.selectable()).isTrue();
    }

    @Test
    void responsesContinuationBindingProfileProducesExpectedCanonicalStringAndDigest() {
        ModelBindingProfile profile = ModelBindingProfile.create(
                new ModelDefinitionId("deepseek-responses-flash"),
                ModelApiStyles.OPENAI_RESPONSES,
                "1.0",
                Set.of(ModelCapability.REASONING, ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING),
                ModelReasoningBehavior.OPTIONAL,
                Set.of(ModelReasoningMode.DISABLED, ModelReasoningMode.ENABLED),
                Set.of(ModelReasoningEffort.HIGH),
                OptionalLong.empty(),
                1,
                8192,
                true,
                ModelProfileStatus.VERIFIED,
                LocalDate.of(2026, 8, 13));

        assertThat(profile.canonicalString())
                .isEqualTo(
                        "model-binding-profile-v1|deepseek-responses-flash|openai-responses|1.0|"
                                + "[REASONING, TEXT_CHAT, TOOL_CALLING]|OPTIONAL|[DISABLED, ENABLED]|[HIGH]|none|1|8192|true|VERIFIED|2026-08-13");
        assertThat(profile.digest()).startsWith("sha256:");
        assertThat(profile.selectable()).isTrue();
    }

    @Test
    void alwaysReasoningAnthropicBindingProfileProducesExpectedCanonicalStringAndDigest() {
        ModelBindingProfile profile = ModelBindingProfile.create(
                new ModelDefinitionId("claude-3-7-sonnet"),
                ModelApiStyles.ANTHROPIC_MESSAGES,
                "1.0",
                Set.of(ModelCapability.REASONING, ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING),
                ModelReasoningBehavior.ALWAYS,
                Set.of(ModelReasoningMode.ENABLED),
                Set.of(ModelReasoningEffort.LOW, ModelReasoningEffort.MEDIUM, ModelReasoningEffort.HIGH),
                OptionalLong.empty(),
                1,
                64000,
                false,
                ModelProfileStatus.VERIFIED,
                LocalDate.of(2026, 8, 30));

        assertThat(profile.canonicalString())
                .isEqualTo(
                        "model-binding-profile-v1|claude-3-7-sonnet|anthropic-messages|1.0|"
                                + "[REASONING, TEXT_CHAT, TOOL_CALLING]|ALWAYS|[ENABLED]|[HIGH, LOW, MEDIUM]|none|1|64000|false|VERIFIED|2026-08-30");
        assertThat(profile.digest()).startsWith("sha256:");
        assertThat(profile.selectable()).isTrue();
    }

    @Test
    void unverifiedBindingProfileProducesExpectedCanonicalStringAndSelectableFalse() {
        ModelBindingProfile profile = ModelBindingProfile.create(
                new ModelDefinitionId("unknown-model"),
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                "1.0",
                Set.of(ModelCapability.TEXT_CHAT),
                ModelReasoningBehavior.NONE,
                Set.of(ModelReasoningMode.DISABLED),
                Set.of(),
                OptionalLong.empty(),
                1,
                4096,
                false,
                ModelProfileStatus.UNVERIFIED,
                LocalDate.of(2026, 8, 30));

        assertThat(profile.canonicalString())
                .isEqualTo("model-binding-profile-v1|unknown-model|openai-chat-completions|1.0|"
                        + "[TEXT_CHAT]|NONE|[DISABLED]|[]|none|1|4096|false|UNVERIFIED|2026-08-30");
        assertThat(profile.digest()).startsWith("sha256:");
        assertThat(profile.selectable()).isFalse();
    }

    @Test
    void digestTamperingThrowsIllegalArgumentException() {
        ModelDefinitionId bindingId = new ModelDefinitionId("test-model");
        Set<ModelCapability> capabilities = Set.of(ModelCapability.TEXT_CHAT);
        Set<ModelReasoningMode> modes = Set.of(ModelReasoningMode.DISABLED);
        Set<ModelReasoningEffort> efforts = Set.of();
        LocalDate verifiedOn = LocalDate.of(2026, 8, 30);

        assertThatThrownBy(() -> new ModelBindingProfile(
                        bindingId,
                        ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                        "1.0",
                        capabilities,
                        ModelReasoningBehavior.NONE,
                        modes,
                        efforts,
                        OptionalLong.empty(),
                        1,
                        4096,
                        false,
                        ModelProfileStatus.VERIFIED,
                        verifiedOn,
                        "sha256:tampered-digest-value"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model profile digest does not match profile fields");
    }

    @Test
    void fieldChangeAltersDigest() {
        ModelDefinitionId bindingId = new ModelDefinitionId("test-model");
        Set<ModelCapability> capabilities = Set.of(ModelCapability.TEXT_CHAT);
        Set<ModelReasoningMode> modes = Set.of(ModelReasoningMode.DISABLED);
        Set<ModelReasoningEffort> efforts = Set.of();
        LocalDate verifiedOn = LocalDate.of(2026, 8, 30);

        ModelBindingProfile profile1 = ModelBindingProfile.create(
                bindingId,
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                "1.0",
                capabilities,
                ModelReasoningBehavior.NONE,
                modes,
                efforts,
                OptionalLong.empty(),
                1,
                4096,
                false,
                ModelProfileStatus.VERIFIED,
                verifiedOn);

        ModelBindingProfile profile2 = ModelBindingProfile.create(
                bindingId,
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                "1.0",
                capabilities,
                ModelReasoningBehavior.NONE,
                modes,
                efforts,
                OptionalLong.empty(),
                1,
                8192, // different maxOutputTokens
                false,
                ModelProfileStatus.VERIFIED,
                verifiedOn);

        assertThat(profile1.digest()).isNotEqualTo(profile2.digest());
    }

    @Test
    void reasoningValidationBoundaryConditions() {
        ModelDefinitionId bindingId = new ModelDefinitionId("test-model");
        LocalDate verifiedOn = LocalDate.of(2026, 8, 30);

        // Reasoning capability with NONE behavior fails
        assertThatThrownBy(() -> ModelBindingProfile.create(
                        bindingId,
                        ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                        "1.0",
                        Set.of(ModelCapability.REASONING, ModelCapability.TEXT_CHAT),
                        ModelReasoningBehavior.NONE,
                        Set.of(ModelReasoningMode.DISABLED),
                        Set.of(),
                        OptionalLong.empty(),
                        1,
                        4096,
                        false,
                        ModelProfileStatus.VERIFIED,
                        verifiedOn))
                .isInstanceOf(IllegalArgumentException.class);

        // Non-reasoning capability with reasoning extensions fails
        assertThatThrownBy(() -> ModelBindingProfile.create(
                        bindingId,
                        ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                        "1.0",
                        Set.of(ModelCapability.TEXT_CHAT),
                        ModelReasoningBehavior.NONE,
                        Set.of(ModelReasoningMode.DISABLED),
                        Set.of(ModelReasoningEffort.HIGH),
                        OptionalLong.empty(),
                        1,
                        4096,
                        false,
                        ModelProfileStatus.VERIFIED,
                        verifiedOn))
                .isInstanceOf(IllegalArgumentException.class);

        // ALWAYS reasoning without ENABLED mode fails
        assertThatThrownBy(() -> ModelBindingProfile.create(
                        bindingId,
                        ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                        "1.0",
                        Set.of(ModelCapability.REASONING, ModelCapability.TEXT_CHAT),
                        ModelReasoningBehavior.ALWAYS,
                        Set.of(ModelReasoningMode.DISABLED),
                        Set.of(ModelReasoningEffort.HIGH),
                        OptionalLong.empty(),
                        1,
                        4096,
                        false,
                        ModelProfileStatus.VERIFIED,
                        verifiedOn))
                .isInstanceOf(IllegalArgumentException.class);

        // OPTIONAL reasoning without DISABLED & ENABLED fails
        assertThatThrownBy(() -> ModelBindingProfile.create(
                        bindingId,
                        ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                        "1.0",
                        Set.of(ModelCapability.REASONING, ModelCapability.TEXT_CHAT),
                        ModelReasoningBehavior.OPTIONAL,
                        Set.of(ModelReasoningMode.ENABLED),
                        Set.of(ModelReasoningEffort.HIGH),
                        OptionalLong.empty(),
                        1,
                        4096,
                        false,
                        ModelProfileStatus.VERIFIED,
                        verifiedOn))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
