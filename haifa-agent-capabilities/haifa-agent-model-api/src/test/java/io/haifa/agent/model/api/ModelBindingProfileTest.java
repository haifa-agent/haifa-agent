package io.haifa.agent.model.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModelBindingProfileTest {

    @Test
    void executionFactsAreTypedDigestCoveredAndExposeAConservativeInputBudget() {
        ModelBindingProfile profile = ModelBindingProfile.create(
                new ModelDefinitionId("streaming-reasoning-binding"),
                ModelApiStyles.OPENAI_RESPONSES,
                "2.0",
                Set.of(
                        ModelCapability.TEXT_CHAT,
                        ModelCapability.TOOL_CALLING,
                        ModelCapability.STRUCTURED_OUTPUT,
                        ModelCapability.REASONING),
                ModelReasoningBehavior.OPTIONAL,
                Set.of(ModelReasoningMode.DISABLED, ModelReasoningMode.ENABLED),
                Set.of(ModelReasoningEffort.HIGH),
                OptionalLong.of(16_384),
                new ModelExecutionLimits(131_072, 1_024, 8_192),
                true,
                new ModelStreamingProfile(
                        true, true, true, ModelPartialOutputFailureBehavior.NON_RETRYABLE),
                ModelProfileStatus.VERIFIED,
                LocalDate.of(2026, 8, 31));

        assertThat(profile.contextWindowTokens()).isEqualTo(131_072);
        assertThat(profile.executionLimits().effectiveInputBudgetTokens(8_192, 1_024)).isEqualTo(121_856);
        assertThat(profile.reasoning().maximumTokens()).hasValue(16_384);
        assertThat(profile.toolResponse())
                .isEqualTo(new ModelToolResponseProfile(true, true, true));
        assertThat(profile.streaming().reasoningStreaming()).isTrue();
        assertThat(profile.canonicalString()).contains("|131072|1024|8192|true|true|true|true|NON_RETRYABLE|");
    }

    @Test
    void streamingFactsCannotClaimUsageOrReasoningWithoutNativeStreaming() {
        assertThatThrownBy(() -> new ModelStreamingProfile(
                        false, true, false, ModelPartialOutputFailureBehavior.NON_RETRYABLE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires native streaming");
    }

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
                .isEqualTo("model-binding-profile-v2|aliyun-qwen3.7-max|openai-chat-completions|1.0|"
                        + "[TEXT_CHAT, TOOL_CALLING]|NONE|[DISABLED]|[]|none|8192|1|8192|false|false|false|false|NON_RETRYABLE|VERIFIED|2026-08-13");
        assertThat(profile.digest())
                .isEqualTo("sha256:a109e84db44a93f44c4e18cbb3867f25407a4c81ef8f79e7e85b7f94a7443b2a");
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
                        "model-binding-profile-v2|deepseek-chat-flash|openai-chat-completions|1.0|"
                                + "[REASONING, TEXT_CHAT, TOOL_CALLING]|OPTIONAL|[DISABLED, ENABLED]|[HIGH]|none|8192|1|8192|false|false|false|false|NON_RETRYABLE|VERIFIED|2026-08-13");
        assertThat(profile.digest())
                .isEqualTo("sha256:1244ccfdeda7974064927859ce95ab49b6326d371c3207b8f2a74868db784992");
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
                        "model-binding-profile-v2|deepseek-responses-flash|openai-responses|1.0|"
                                + "[REASONING, TEXT_CHAT, TOOL_CALLING]|OPTIONAL|[DISABLED, ENABLED]|[HIGH]|none|8192|1|8192|true|false|false|false|NON_RETRYABLE|VERIFIED|2026-08-13");
        assertThat(profile.digest())
                .isEqualTo("sha256:042e729370d65e3fcbef4188f9265a7cc75ec65d7741df5db17ac304483142bc");
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
                        "model-binding-profile-v2|claude-3-7-sonnet|anthropic-messages|1.0|"
                                + "[REASONING, TEXT_CHAT, TOOL_CALLING]|ALWAYS|[ENABLED]|[HIGH, LOW, MEDIUM]|none|64000|1|64000|false|false|false|false|NON_RETRYABLE|VERIFIED|2026-08-30");
        assertThat(profile.digest())
                .isEqualTo("sha256:c3290ed2f45610e6a49b584ee7d5241e9eaad3788069b5ca31ccad26dc74aaf9");
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
                .isEqualTo("model-binding-profile-v2|unknown-model|openai-chat-completions|1.0|"
                        + "[TEXT_CHAT]|NONE|[DISABLED]|[]|none|4096|1|4096|false|false|false|false|NON_RETRYABLE|UNVERIFIED|2026-08-30");
        assertThat(profile.digest())
                .isEqualTo("sha256:d39101d5560faef9a9ad4cda5f8e8a5fd983bef038ba3b7aac900773bec2b509");
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
    void canonicalFormRejectsAmbiguousDelimiterCharacters() {
        assertThatThrownBy(() -> ModelBindingProfile.create(
                        new ModelDefinitionId("model|other"),
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
                        ModelProfileStatus.VERIFIED,
                        LocalDate.of(2026, 8, 30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bindingId is invalid for the model profile canonical form");
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
