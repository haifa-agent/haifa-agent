package io.haifa.agent.model.openai;

import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelBindingProfile;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelProfileStatus;
import io.haifa.agent.model.api.ModelReasoningBehavior;
import io.haifa.agent.model.api.ModelReasoningEffort;
import io.haifa.agent.model.api.ModelReasoningMode;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.model.openai.anthropic.AnthropicMessagesDialects;
import io.haifa.agent.model.openai.responses.OpenAiResponsesDialects;
import java.time.LocalDate;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/** Builds provider-neutral profiles from capabilities already verified by this integration. */
public final class OpenAiCompatibleModelProfileFactory {
    public static final String CURRENT_PROFILE_VERSION = "1.0";

    private OpenAiCompatibleModelProfileFactory() {}

    public static ModelBindingProfile fromSnapshot(ResolvedModelSnapshot snapshot, LocalDate verifiedOn) {
        if (ModelApiStyles.ANTHROPIC_MESSAGES.equals(snapshot.apiStyle())) {
            return AnthropicMessagesDialects.profile(snapshot, verifiedOn);
        }
        if (ModelApiStyles.OPENAI_RESPONSES.equals(snapshot.apiStyle())) {
            return OpenAiResponsesDialects.profile(snapshot, verifiedOn);
        }

        Optional<OpenAiCompatibleBindingRegistry.AdmittedBinding> admission =
                OpenAiCompatibleBindingRegistry.find(snapshot);

        if (admission.isEmpty()) {
            boolean reasoning = snapshot.capabilities().contains(ModelCapability.REASONING);
            return ModelBindingProfile.create(
                    snapshot.modelId(),
                    snapshot.apiStyle(),
                    CURRENT_PROFILE_VERSION,
                    snapshot.capabilities(),
                    reasoning ? ModelReasoningBehavior.OPTIONAL : ModelReasoningBehavior.NONE,
                    reasoning
                            ? Set.of(ModelReasoningMode.DISABLED, ModelReasoningMode.ENABLED)
                            : Set.of(ModelReasoningMode.DISABLED),
                    reasoning ? Set.of(ModelReasoningEffort.HIGH) : Set.of(),
                    OptionalLong.empty(),
                    1,
                    snapshot.maxOutputTokens(),
                    false,
                    ModelProfileStatus.UNVERIFIED,
                    verifiedOn);
        }

        OpenAiCompatibleBindingRegistry.AdmittedBinding binding = admission.get();
        boolean reasoning = snapshot.capabilities().contains(ModelCapability.REASONING);

        if (!reasoning) {
            return ModelBindingProfile.create(
                    snapshot.modelId(),
                    snapshot.apiStyle(),
                    CURRENT_PROFILE_VERSION,
                    snapshot.capabilities(),
                    ModelReasoningBehavior.NONE,
                    Set.of(ModelReasoningMode.DISABLED),
                    Set.of(),
                    OptionalLong.empty(),
                    1,
                    snapshot.maxOutputTokens(),
                    false,
                    ModelProfileStatus.VERIFIED,
                    verifiedOn);
        }

        return ModelBindingProfile.create(
                snapshot.modelId(),
                snapshot.apiStyle(),
                CURRENT_PROFILE_VERSION,
                snapshot.capabilities(),
                binding.reasoningBehavior(),
                binding.allowedReasoningModes(),
                binding.allowedReasoningEfforts(),
                OptionalLong.empty(),
                1,
                snapshot.maxOutputTokens(),
                binding.toolReasoningContinuationRequired(),
                ModelProfileStatus.VERIFIED,
                verifiedOn);
    }
}
