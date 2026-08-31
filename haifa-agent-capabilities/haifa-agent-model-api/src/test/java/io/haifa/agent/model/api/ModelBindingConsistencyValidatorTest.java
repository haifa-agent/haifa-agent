package io.haifa.agent.model.api;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModelBindingConsistencyValidatorTest {

    private static final LocalDate VERIFIED_ON = LocalDate.of(2026, 8, 30);

    @Test
    void validStandardDefinitionAndProfilePassValidation() {
        ModelDefinition definition = createDefinition(
                "qwen3.7-max",
                "aliyun-bailian",
                "qwen3.7-max",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING),
                131072,
                8192);

        ModelBindingProfile profile = ModelBindingProfile.create(
                new ModelDefinitionId("qwen3.7-max"),
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                "1.0",
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING),
                ModelReasoningBehavior.NONE,
                Set.of(ModelReasoningMode.DISABLED),
                Set.of(),
                OptionalLong.empty(),
                new ModelExecutionLimits(131072, 1, 8192),
                false,
                new ModelStreamingProfile(true, false, false, ModelPartialOutputFailureBehavior.NON_RETRYABLE),
                ModelProfileStatus.VERIFIED,
                VERIFIED_ON);

        assertThatCode(() -> ModelBindingConsistencyValidator.validate(definition, profile))
                .doesNotThrowAnyException();
    }

    @Test
    void validReasoningDefinitionAndProfilePassValidation() {
        ModelDefinition definition = createDefinition(
                "deepseek-chat-flash",
                "deepseek",
                "deepseek-v4-flash",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                Set.of(ModelCapability.REASONING, ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING),
                65536,
                8192);

        ModelBindingProfile profile = ModelBindingProfile.create(
                new ModelDefinitionId("deepseek-chat-flash"),
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                "1.0",
                Set.of(ModelCapability.REASONING, ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING),
                ModelReasoningBehavior.OPTIONAL,
                Set.of(ModelReasoningMode.DISABLED, ModelReasoningMode.ENABLED),
                Set.of(ModelReasoningEffort.HIGH),
                OptionalLong.empty(),
                new ModelExecutionLimits(65536, 1, 8192),
                false,
                new ModelStreamingProfile(true, false, false, ModelPartialOutputFailureBehavior.NON_RETRYABLE),
                ModelProfileStatus.VERIFIED,
                VERIFIED_ON);

        assertThatCode(() -> ModelBindingConsistencyValidator.validate(definition, profile))
                .doesNotThrowAnyException();
    }

    @Test
    void rule1BindingIdMismatchThrowsIllegalArgumentException() {
        ModelDefinition definition = createDefinition(
                "model-a",
                "provider-1",
                "model-a",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                Set.of(ModelCapability.TEXT_CHAT),
                8192,
                4096);

        ModelBindingProfile profile = ModelBindingProfile.create(
                new ModelDefinitionId("model-b"),
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
                VERIFIED_ON);

        assertThatThrownBy(() -> ModelBindingConsistencyValidator.validate(definition, profile))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("binding ID mismatch");
    }

    @Test
    void rule2ApiStyleMismatchThrowsIllegalArgumentException() {
        ModelDefinition definition = createDefinition(
                "model-a",
                "provider-1",
                "model-a",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                Set.of(ModelCapability.TEXT_CHAT),
                8192,
                4096);

        ModelBindingProfile profile = ModelBindingProfile.create(
                new ModelDefinitionId("model-a"),
                ModelApiStyles.OPENAI_RESPONSES,
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
                VERIFIED_ON);

        assertThatThrownBy(() -> ModelBindingConsistencyValidator.validate(definition, profile))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("API style mismatch");
    }

    @Test
    void rule3CapabilitiesMismatchThrowsIllegalArgumentException() {
        ModelDefinition definition = createDefinition(
                "model-a",
                "provider-1",
                "model-a",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                Set.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING),
                8192,
                4096);

        ModelBindingProfile profile = ModelBindingProfile.create(
                new ModelDefinitionId("model-a"),
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
                VERIFIED_ON);

        assertThatThrownBy(() -> ModelBindingConsistencyValidator.validate(definition, profile))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("capabilities mismatch");
    }

    @Test
    void rule4MaxOutputTokensMismatchThrowsIllegalArgumentException() {
        ModelDefinition definition = createDefinition(
                "model-a",
                "provider-1",
                "model-a",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                Set.of(ModelCapability.TEXT_CHAT),
                8192,
                4096);

        ModelBindingProfile profile = ModelBindingProfile.create(
                new ModelDefinitionId("model-a"),
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                "1.0",
                Set.of(ModelCapability.TEXT_CHAT),
                ModelReasoningBehavior.NONE,
                Set.of(ModelReasoningMode.DISABLED),
                Set.of(),
                OptionalLong.empty(),
                new ModelExecutionLimits(8192, 1, 2048),
                false,
                ModelStreamingProfile.disabled(),
                ModelProfileStatus.VERIFIED,
                VERIFIED_ON);

        assertThatThrownBy(() -> ModelBindingConsistencyValidator.validate(definition, profile))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxOutputTokens mismatch");
    }

    @Test
    void rule6MaxOutputTokensExceedsContextWindowThrowsIllegalArgumentException() {
        ModelDefinition definition = createDefinition(
                "model-a",
                "provider-1",
                "model-a",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                Set.of(ModelCapability.TEXT_CHAT),
                4096, // context window is 4096
                4096);

        ModelBindingProfile profile = ModelBindingProfile.create(
                new ModelDefinitionId("model-a"),
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                "1.0",
                Set.of(ModelCapability.TEXT_CHAT),
                ModelReasoningBehavior.NONE,
                Set.of(ModelReasoningMode.DISABLED),
                Set.of(),
                OptionalLong.empty(),
                1,
                8192, // profile max output is 8192 > 4096
                false,
                ModelProfileStatus.VERIFIED,
                VERIFIED_ON);

        assertThatThrownBy(() -> ModelBindingConsistencyValidator.validate(definition, profile))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rule7ReasoningContradictionThrowsIllegalArgumentException() {
        // Definition has REASONING, Profile has NONE
        ModelDefinition defWithReasoning = createDefinition(
                "model-a",
                "provider-1",
                "model-a",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                Set.of(ModelCapability.REASONING, ModelCapability.TEXT_CHAT),
                8192,
                4096);

        ModelBindingProfile profileWithoutReasoning = ModelBindingProfile.create(
                new ModelDefinitionId("model-a"),
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                "1.0",
                Set.of(ModelCapability.TEXT_CHAT),
                ModelReasoningBehavior.NONE,
                Set.of(ModelReasoningMode.DISABLED),
                Set.of(),
                OptionalLong.empty(),
                new ModelExecutionLimits(8192, 1, 4096),
                false,
                new ModelStreamingProfile(true, false, false, ModelPartialOutputFailureBehavior.NON_RETRYABLE),
                ModelProfileStatus.VERIFIED,
                VERIFIED_ON);

        assertThatThrownBy(() -> ModelBindingConsistencyValidator.validate(defWithReasoning, profileWithoutReasoning))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void providerNativeStreamingMustMatchTheExactBindingProfile() {
        ModelDefinition definition = createDefinition(
                "model-a",
                "provider-1",
                "model-a",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                Set.of(ModelCapability.TEXT_CHAT),
                8192,
                4096);
        ModelProviderDefinition provider = new ModelProviderDefinition(
                new ModelProviderId("provider-1"),
                "1.0",
                "Provider",
                URI.create("https://api.example.com"),
                new CredentialRef("env://TEST_KEY"),
                false,
                ProviderStatus.ACTIVE,
                List.of(new ModelApiBindingDefinition(ModelApiStyles.OPENAI_CHAT_COMPLETIONS, "standard")),
                List.of(definition),
                Map.of(),
                Map.of());
        ModelBindingProfile profile = ModelBindingProfile.create(
                definition.id(),
                definition.style(),
                "1.0",
                definition.capabilities(),
                ModelReasoningBehavior.NONE,
                Set.of(ModelReasoningMode.DISABLED),
                Set.of(),
                OptionalLong.empty(),
                new ModelExecutionLimits(8192, 1, 4096),
                false,
                new ModelStreamingProfile(true, true, false, ModelPartialOutputFailureBehavior.NON_RETRYABLE),
                ModelProfileStatus.VERIFIED,
                VERIFIED_ON);

        assertThatThrownBy(() -> ModelBindingConsistencyValidator.validate(provider, definition, profile))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nativeStreaming mismatch");
    }

    @Test
    void rule8ProviderValidationAndValidateAll() {
        ModelProviderId providerId = new ModelProviderId("provider-1");
        ModelDefinition model1 = createDefinition(
                "model-1",
                "provider-1",
                "model-1",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                Set.of(ModelCapability.TEXT_CHAT),
                8192,
                4096);
        ModelDefinition model2 = createDefinition(
                "model-2",
                "provider-1",
                "model-2",
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                Set.of(ModelCapability.TEXT_CHAT),
                8192,
                4096);

        ModelProviderDefinition provider = new ModelProviderDefinition(
                providerId,
                "v1",
                "Provider 1",
                URI.create("https://api.provider.com"),
                new CredentialRef("env://KEY"),
                true,
                ProviderStatus.ACTIVE,
                List.of(new ModelApiBindingDefinition(ModelApiStyles.OPENAI_CHAT_COMPLETIONS, "standard")),
                List.of(model1, model2),
                Map.of(),
                Map.of());

        ModelBindingProfile profile1 = ModelBindingProfile.create(
                new ModelDefinitionId("model-1"),
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                "1.0",
                Set.of(ModelCapability.TEXT_CHAT),
                ModelReasoningBehavior.NONE,
                Set.of(ModelReasoningMode.DISABLED),
                Set.of(),
                OptionalLong.empty(),
                new ModelExecutionLimits(8192, 1, 4096),
                false,
                new ModelStreamingProfile(true, false, false, ModelPartialOutputFailureBehavior.NON_RETRYABLE),
                ModelProfileStatus.VERIFIED,
                VERIFIED_ON);

        ModelBindingProfile profile2 = ModelBindingProfile.create(
                new ModelDefinitionId("model-2"),
                ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                "1.0",
                Set.of(ModelCapability.TEXT_CHAT),
                ModelReasoningBehavior.NONE,
                Set.of(ModelReasoningMode.DISABLED),
                Set.of(),
                OptionalLong.empty(),
                new ModelExecutionLimits(8192, 1, 4096),
                false,
                new ModelStreamingProfile(true, false, false, ModelPartialOutputFailureBehavior.NON_RETRYABLE),
                ModelProfileStatus.VERIFIED,
                VERIFIED_ON);

        // Success case with validateAll
        assertThatCode(() -> ModelBindingConsistencyValidator.validateAll(
                        provider, Map.of("model-1", profile1, "model-2", profile2)))
                .doesNotThrowAnyException();

        // Mismatched provider ID
        ModelProviderDefinition wrongProvider = new ModelProviderDefinition(
                new ModelProviderId("wrong-provider"),
                "v1",
                "Wrong",
                URI.create("https://api.wrong.com"),
                new CredentialRef("env://KEY"),
                true,
                ProviderStatus.ACTIVE,
                List.of(new ModelApiBindingDefinition(ModelApiStyles.OPENAI_CHAT_COMPLETIONS, "standard")),
                List.of(createDefinition(
                        "model-1",
                        "wrong-provider",
                        "model-1",
                        ModelApiStyles.OPENAI_CHAT_COMPLETIONS,
                        Set.of(ModelCapability.TEXT_CHAT),
                        8192,
                        4096)),
                Map.of(),
                Map.of());

        assertThatThrownBy(() -> ModelBindingConsistencyValidator.validate(wrongProvider, model1, profile1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("belongs to provider");

        // Missing profile in validateAll
        assertThatThrownBy(() -> ModelBindingConsistencyValidator.validateAll(provider, Map.of("model-1", profile1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing authoritative profile");
    }

    private static ModelDefinition createDefinition(
            String id,
            String providerId,
            String providerModelId,
            ApiStyleId style,
            Set<ModelCapability> capabilities,
            int contextWindow,
            int maxOutputTokens) {
        return new ModelDefinition(
                new ModelDefinitionId(id),
                "1.0",
                new ModelProviderId(providerId),
                providerModelId,
                id,
                ModelStatus.ACTIVE,
                capabilities,
                contextWindow,
                maxOutputTokens,
                Map.of(),
                Map.of(),
                style);
    }
}
