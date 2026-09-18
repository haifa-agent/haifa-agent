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
                new ModelStreamingProfile(true, true, true, ModelPartialOutputFailureBehavior.NON_RETRYABLE),
                ModelIoProfile.textOnly(),
                ModelProfileStatus.VERIFIED,
                LocalDate.of(2026, 8, 31));

        assertThat(profile.contextWindowTokens()).isEqualTo(131_072);
        assertThat(profile.executionLimits().effectiveInputBudgetTokens(8_192, 1_024))
                .isEqualTo(121_856);
        assertThat(profile.reasoning().maximumTokens()).hasValue(16_384);
        assertThat(profile.toolResponse()).isEqualTo(new ModelToolResponseProfile(true, true, true));
        assertThat(profile.streaming().reasoningStreaming()).isTrue();
        assertThat(profile.ioProfile().inputModalities()).containsExactly(ModelInputModality.TEXT);
        assertThat(profile.ioProfile().outputModalities()).containsExactly(ModelOutputModality.TEXT);
        assertThat(profile.imageInput()).isEmpty();
        assertThat(profile.canonicalString())
                .contains("|131072|1024|8192|true|true|true|true|NON_RETRYABLE|[TEXT]|[TEXT]|[]|[]|0|0|0|0|false|[]|");
    }

    @Test
    void streamingFactsCannotClaimUsageOrReasoningWithoutNativeStreaming() {
        assertThatThrownBy(() ->
                        new ModelStreamingProfile(false, true, false, ModelPartialOutputFailureBehavior.NON_RETRYABLE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires native streaming");
    }

    @Test
    void nonReasoningBindingCannotClaimReasoningStreaming() {
        assertThatThrownBy(() -> ModelBindingProfile.create(
                        new ModelDefinitionId("non-reasoning-streaming-binding"),
                        ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                        "2.0",
                        Set.of(ModelCapability.TEXT_CHAT),
                        ModelReasoningBehavior.NONE,
                        Set.of(ModelReasoningMode.DISABLED),
                        Set.of(),
                        OptionalLong.empty(),
                        new ModelExecutionLimits(32_768, 1_024, 8_192),
                        false,
                        new ModelStreamingProfile(true, false, true, ModelPartialOutputFailureBehavior.NON_RETRYABLE),
                        ModelIoProfile.textOnly(),
                        ModelProfileStatus.VERIFIED,
                        LocalDate.of(2026, 8, 31)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reasoning streaming requires reasoning capability");
    }

    @Test
    void reasoningProfileRejectsNonPositiveMaximumTokens() {
        assertThatThrownBy(() -> new ModelReasoningProfile(
                        ModelReasoningBehavior.OPTIONAL,
                        Set.of(ModelReasoningMode.DISABLED, ModelReasoningMode.ENABLED),
                        Set.of(ModelReasoningEffort.HIGH),
                        OptionalLong.of(0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximumTokens must be positive");
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
                .isEqualTo(
                        "model-binding-profile-v3|aliyun-qwen3.7-max|openai-chat-completions|1.0|"
                                + "[TEXT_CHAT, TOOL_CALLING]|NONE|[DISABLED]|[]|none|8192|1|8192|false|false|false|false|NON_RETRYABLE|[TEXT]|[TEXT]|[]|[]|0|0|0|0|false|[]|VERIFIED|2026-08-13");
        assertThat(profile.digest())
                .isEqualTo(ModelBindingProfile.digest(
                        profile.bindingId(),
                        profile.apiStyle(),
                        profile.version(),
                        profile.capabilities(),
                        profile.reasoningBehavior(),
                        profile.allowedReasoningModes(),
                        profile.allowedReasoningEfforts(),
                        profile.maximumReasoningTokens(),
                        profile.executionLimits(),
                        profile.toolReasoningContinuationRequired(),
                        profile.streaming(),
                        profile.ioProfile(),
                        profile.status(),
                        profile.lastVerifiedOn()));
        assertThat(profile.selectable()).isTrue();
    }

    @Test
    void multimodalImageBindingProfileProducesExpectedCanonicalStringAndDigest() {
        ImageInputProfile imageInput =
                ImageInputProfile.standard(Set.of(ModelImageSource.UPLOAD, ModelImageSource.URL), true);
        ModelIoProfile ioProfile = ModelIoProfile.withImage(imageInput);

        ModelBindingProfile profile = ModelBindingProfile.create(
                new ModelDefinitionId("qwen3-vl-plus"),
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                "2.0",
                Set.of(
                        ModelCapability.TEXT_CHAT,
                        ModelCapability.TOOL_CALLING,
                        ModelCapability.IMAGE_UPLOAD_INPUT,
                        ModelCapability.IMAGE_URL_INPUT),
                ModelReasoningBehavior.NONE,
                Set.of(ModelReasoningMode.DISABLED),
                Set.of(),
                OptionalLong.empty(),
                new ModelExecutionLimits(131_072, 1, 8_192),
                false,
                new ModelStreamingProfile(true, true, false, ModelPartialOutputFailureBehavior.NON_RETRYABLE),
                ioProfile,
                ModelProfileStatus.VERIFIED,
                LocalDate.of(2026, 8, 31));

        assertThat(profile.ioProfile().inputModalities())
                .containsExactlyInAnyOrder(ModelInputModality.TEXT, ModelInputModality.IMAGE);
        assertThat(profile.imageInput()).isPresent();
        assertThat(profile.imageInput().get().allowedSources())
                .containsExactlyInAnyOrder(ModelImageSource.UPLOAD, ModelImageSource.URL);
        assertThat(profile.imageInput().get().supportedMediaTypes())
                .containsExactlyInAnyOrder("image/png", "image/jpeg", "image/webp", "image/gif");
        assertThat(profile.imageInput().get().maxImagesPerRequest()).isEqualTo(4);
        assertThat(profile.imageInput().get().maxBytesPerItem()).isEqualTo(10485760L);
        assertThat(profile.imageInput().get().maxTotalBytes()).isEqualTo(20971520L);
        assertThat(profile.imageInput().get().maxUrlCharacters()).isEqualTo(2048);
        assertThat(profile.imageInput().get().detailSupported()).isTrue();
        assertThat(profile.imageInput().get().allowedDetails())
                .containsExactlyInAnyOrder(ModelImageDetail.AUTO, ModelImageDetail.LOW, ModelImageDetail.HIGH);

        assertThat(profile.canonicalString())
                .isEqualTo(
                        "model-binding-profile-v3|qwen3-vl-plus|openai-chat-completions|2.0|"
                                + "[IMAGE_UPLOAD_INPUT, IMAGE_URL_INPUT, TEXT_CHAT, TOOL_CALLING]|NONE|[DISABLED]|[]|none|131072|1|8192|false|true|true|false|NON_RETRYABLE|"
                                + "[IMAGE, TEXT]|[TEXT]|[UPLOAD, URL]|[9:image/gif,10:image/jpeg,9:image/png,10:image/webp]|4|10485760|20971520|2048|true|[AUTO, HIGH, LOW]|VERIFIED|2026-08-31");
        assertThat(profile.selectable()).isTrue();
    }

    @Test
    void geminiUploadOnlyImageBindingProfileProducesExpectedCanonicalString() {
        ImageInputProfile imageInput = ImageInputProfile.gemini(Set.of(ModelImageSource.UPLOAD));
        ModelIoProfile ioProfile = ModelIoProfile.withImage(imageInput);

        ModelBindingProfile profile = ModelBindingProfile.create(
                new ModelDefinitionId("gemini-3.7-flash"),
                ModelApiStyles.GOOGLE_GEMINI_GENERATE_CONTENT,
                "2.0",
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING, ModelCapability.IMAGE_UPLOAD_INPUT),
                ModelReasoningBehavior.NONE,
                Set.of(ModelReasoningMode.DISABLED),
                Set.of(),
                OptionalLong.empty(),
                new ModelExecutionLimits(1_048_576, 1, 65_536),
                false,
                new ModelStreamingProfile(true, true, false, ModelPartialOutputFailureBehavior.NON_RETRYABLE),
                ioProfile,
                ModelProfileStatus.VERIFIED,
                LocalDate.of(2026, 8, 31));

        assertThat(profile.canonicalString())
                .isEqualTo(
                        "model-binding-profile-v3|gemini-3.7-flash|google-gemini-generate-content|2.0|"
                                + "[IMAGE_UPLOAD_INPUT, TEXT_CHAT, TOOL_CALLING]|NONE|[DISABLED]|[]|none|1048576|1|65536|false|true|true|false|NON_RETRYABLE|"
                                + "[IMAGE, TEXT]|[TEXT]|[UPLOAD]|[15:application/pdf,9:image/gif,10:image/jpeg,9:image/png,10:image/webp]|4|10485760|12582912|2048|false|[]|VERIFIED|2026-08-31");
    }

    @Test
    void canonicalDigestIsCollisionFreeForMediaTypes() {
        ImageInputProfile profile1 = new ImageInputProfile(
                Set.of(ModelImageSource.UPLOAD),
                Set.of("image/png", "image/jpeg"),
                4,
                1024,
                2048,
                2048,
                false,
                Set.of());
        ImageInputProfile profile2 = new ImageInputProfile(
                Set.of(ModelImageSource.UPLOAD),
                Set.of("image/png", "image/webp"),
                4,
                1024,
                2048,
                2048,
                false,
                Set.of());

        ModelBindingProfile binding1 = ModelBindingProfile.create(
                new ModelDefinitionId("binding-1"),
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                "2.0",
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.IMAGE_UPLOAD_INPUT),
                ModelReasoningBehavior.NONE,
                Set.of(ModelReasoningMode.DISABLED),
                Set.of(),
                OptionalLong.empty(),
                new ModelExecutionLimits(32768, 1, 4096),
                false,
                ModelStreamingProfile.disabled(),
                ModelIoProfile.withImage(profile1),
                ModelProfileStatus.VERIFIED,
                LocalDate.of(2026, 8, 31));

        ModelBindingProfile binding2 = ModelBindingProfile.create(
                new ModelDefinitionId("binding-1"),
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                "2.0",
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.IMAGE_UPLOAD_INPUT),
                ModelReasoningBehavior.NONE,
                Set.of(ModelReasoningMode.DISABLED),
                Set.of(),
                OptionalLong.empty(),
                new ModelExecutionLimits(32768, 1, 4096),
                false,
                ModelStreamingProfile.disabled(),
                ModelIoProfile.withImage(profile2),
                ModelProfileStatus.VERIFIED,
                LocalDate.of(2026, 8, 31));

        assertThat(binding1.canonicalString()).isNotEqualTo(binding2.canonicalString());
        assertThat(binding1.digest()).isNotEqualTo(binding2.digest());
    }

    @Test
    void imageInputProfileValidationRules() {
        // Empty sources fails
        assertThatThrownBy(() ->
                        new ImageInputProfile(Set.of(), Set.of("image/png"), 4, 1024, 2048, 2048, false, Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowedSources must not be empty");

        // Empty media types fails
        assertThatThrownBy(() -> new ImageInputProfile(
                        Set.of(ModelImageSource.UPLOAD), Set.of(), 4, 1024, 2048, 2048, false, Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("supportedMediaTypes must not be empty");

        // detailSupported=true but empty details fails
        assertThatThrownBy(() -> new ImageInputProfile(
                        Set.of(ModelImageSource.UPLOAD), Set.of("image/png"), 4, 1024, 2048, 2048, true, Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("detailSupported requires at least one allowed detail level");

        // detailSupported=false with non-empty details fails
        assertThatThrownBy(() -> new ImageInputProfile(
                        Set.of(ModelImageSource.UPLOAD),
                        Set.of("image/png"),
                        4,
                        1024,
                        2048,
                        2048,
                        false,
                        Set.of(ModelImageDetail.AUTO)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("detailSupported=false must have empty allowed details");

        // invalid bounds (maxTotalBytes < maxBytesPerItem) fails
        assertThatThrownBy(() -> new ImageInputProfile(
                        Set.of(ModelImageSource.UPLOAD), Set.of("image/png"), 4, 2048, 1024, 2048, false, Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid image input profile bounds");
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
                        new ModelExecutionLimits(4096, 1, 4096),
                        ModelStreamingProfile.disabled(),
                        ModelIoProfile.textOnly(),
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
}
