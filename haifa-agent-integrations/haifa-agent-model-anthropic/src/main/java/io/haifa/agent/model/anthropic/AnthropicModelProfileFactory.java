package io.haifa.agent.model.anthropic;

import io.haifa.agent.model.api.ModelBindingProfile;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelExecutionLimits;
import io.haifa.agent.model.api.ModelIoProfile;
import io.haifa.agent.model.api.ModelPartialOutputFailureBehavior;
import io.haifa.agent.model.api.ModelProfileStatus;
import io.haifa.agent.model.api.ModelReasoningBehavior;
import io.haifa.agent.model.api.ModelReasoningEffort;
import io.haifa.agent.model.api.ModelReasoningMode;
import io.haifa.agent.model.api.ModelStreamingProfile;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.time.LocalDate;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/** Builds provider-neutral profiles from capabilities already verified by Anthropic Messages integration. */
public final class AnthropicModelProfileFactory {
    public static final String CURRENT_PROFILE_VERSION = "2.0";

    private AnthropicModelProfileFactory() {}

    public static ModelBindingProfile fromSnapshot(ResolvedModelSnapshot snapshot, LocalDate verifiedOn) {
        Optional<AnthropicMessagesBindingRegistry.AdmittedBinding> admission =
                AnthropicMessagesBindingRegistry.find(snapshot);
        boolean reasoning = snapshot.capabilities().contains(ModelCapability.REASONING);
        ModelIoProfile fallbackIoProfile =
                snapshot.imageInput().map(ModelIoProfile::withImage).orElseGet(ModelIoProfile::textOnly);

        if (admission.isEmpty()) {
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
                    limits(snapshot),
                    false,
                    streaming(snapshot),
                    fallbackIoProfile,
                    ModelProfileStatus.UNVERIFIED,
                    verifiedOn);
        }

        AnthropicMessagesBindingRegistry.AdmittedBinding binding = admission.get();
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
                    limits(snapshot),
                    false,
                    streaming(snapshot),
                    ModelIoProfile.textOnly(),
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
                limits(snapshot),
                binding.toolReasoningContinuationRequired(),
                streaming(snapshot),
                ModelIoProfile.textOnly(),
                ModelProfileStatus.VERIFIED,
                verifiedOn);
    }

    private static ModelExecutionLimits limits(ResolvedModelSnapshot snapshot) {
        return new ModelExecutionLimits(snapshot.contextWindow(), 1, snapshot.maxOutputTokens());
    }

    private static ModelStreamingProfile streaming(ResolvedModelSnapshot snapshot) {
        return new ModelStreamingProfile(
                snapshot.nativeStreaming(),
                snapshot.nativeStreaming(),
                snapshot.nativeStreaming() && snapshot.capabilities().contains(ModelCapability.REASONING),
                ModelPartialOutputFailureBehavior.NON_RETRYABLE);
    }
}
