package io.haifa.agent.model.gemini;

import io.haifa.agent.model.api.ImageInputProfile;
import io.haifa.agent.model.api.ModelBindingProfile;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelExecutionLimits;
import io.haifa.agent.model.api.ModelImageSource;
import io.haifa.agent.model.api.ModelIoProfile;
import io.haifa.agent.model.api.ModelPartialOutputFailureBehavior;
import io.haifa.agent.model.api.ModelProfileStatus;
import io.haifa.agent.model.api.ModelReasoningBehavior;
import io.haifa.agent.model.api.ModelReasoningEffort;
import io.haifa.agent.model.api.ModelReasoningMode;
import io.haifa.agent.model.api.ModelStreamingProfile;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.time.LocalDate;
import java.util.OptionalLong;
import java.util.Set;

/** Builds profiles only for governed Gemini standard or local dialect bindings. */
public final class GeminiModelProfileFactory {
    public static final String CURRENT_PROFILE_VERSION = "2.0";

    private GeminiModelProfileFactory() {}

    public static ModelBindingProfile fromSnapshot(ResolvedModelSnapshot snapshot, LocalDate verifiedOn) {
        boolean governed = GeminiBindingRegistry.isAdmitted(snapshot);
        boolean reasoning = snapshot.capabilities().contains(ModelCapability.REASONING);
        return ModelBindingProfile.create(
                snapshot.modelId(),
                snapshot.apiStyle(),
                CURRENT_PROFILE_VERSION,
                snapshot.capabilities(),
                reasoning ? ModelReasoningBehavior.ALWAYS : ModelReasoningBehavior.NONE,
                reasoning ? Set.of(ModelReasoningMode.ENABLED) : Set.of(ModelReasoningMode.DISABLED),
                reasoning ? Set.of(ModelReasoningEffort.HIGH) : Set.of(),
                OptionalLong.empty(),
                new ModelExecutionLimits(snapshot.contextWindow(), 1, snapshot.maxOutputTokens()),
                reasoning,
                new ModelStreamingProfile(
                        snapshot.nativeStreaming(),
                        snapshot.nativeStreaming(),
                        false,
                        ModelPartialOutputFailureBehavior.NON_RETRYABLE),
                ioProfile(snapshot),
                governed ? ModelProfileStatus.VERIFIED : ModelProfileStatus.UNVERIFIED,
                verifiedOn);
    }

    private static ModelIoProfile ioProfile(ResolvedModelSnapshot snapshot) {
        if (snapshot.capabilities().contains(ModelCapability.IMAGE_UPLOAD_INPUT)) {
            return ModelIoProfile.withImage(ImageInputProfile.standard(Set.of(ModelImageSource.UPLOAD), false));
        }
        return ModelIoProfile.textOnly();
    }
}
