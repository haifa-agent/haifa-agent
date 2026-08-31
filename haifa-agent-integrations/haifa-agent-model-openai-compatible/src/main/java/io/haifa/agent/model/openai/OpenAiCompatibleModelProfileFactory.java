package io.haifa.agent.model.openai;

import io.haifa.agent.model.api.ModelApiStyles;
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

/** Builds provider-neutral profiles from capabilities already verified by this integration. */
public final class OpenAiCompatibleModelProfileFactory {
    public static final String CURRENT_PROFILE_VERSION = "2.0";

    private OpenAiCompatibleModelProfileFactory() {}

    public static ModelBindingProfile fromSnapshot(ResolvedModelSnapshot snapshot, LocalDate verifiedOn) {
        Optional<AdmittedBindingSpec> admission = findAdmission(snapshot);
        boolean reasoning = snapshot.capabilities().contains(ModelCapability.REASONING);

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
                    ModelIoProfile.textOnly(),
                    ModelProfileStatus.UNVERIFIED,
                    verifiedOn);
        }

        AdmittedBindingSpec binding = admission.get();
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
                    binding.ioProfile(),
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
                binding.ioProfile(),
                ModelProfileStatus.VERIFIED,
                verifiedOn);
    }

    private static Optional<AdmittedBindingSpec> findAdmission(ResolvedModelSnapshot snapshot) {
        if (ModelApiStyles.OPENAI_RESPONSES.equals(snapshot.apiStyle())) {
            return OpenAiResponsesBindingRegistry.find(snapshot)
                    .map(b -> new AdmittedBindingSpec(
                            b.reasoningBehavior(),
                            b.allowedReasoningModes(),
                            b.allowedReasoningEfforts(),
                            b.toolReasoningContinuationRequired(),
                            ModelIoProfile.textOnly()));
        }
        return OpenAiCompatibleBindingRegistry.find(snapshot)
                .map(b -> new AdmittedBindingSpec(
                        b.reasoningBehavior(),
                        b.allowedReasoningModes(),
                        b.allowedReasoningEfforts(),
                        b.toolReasoningContinuationRequired(),
                        b.ioProfile()));
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

    private record AdmittedBindingSpec(
            ModelReasoningBehavior reasoningBehavior,
            Set<ModelReasoningMode> allowedReasoningModes,
            Set<ModelReasoningEffort> allowedReasoningEfforts,
            boolean toolReasoningContinuationRequired,
            ModelIoProfile ioProfile) {}
}
