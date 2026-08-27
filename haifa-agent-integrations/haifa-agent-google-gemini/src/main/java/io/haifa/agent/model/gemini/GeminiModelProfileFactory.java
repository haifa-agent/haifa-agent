package io.haifa.agent.model.gemini;

import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelBindingProfile;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelProfileStatus;
import io.haifa.agent.model.api.ModelReasoningBehavior;
import io.haifa.agent.model.api.ModelReasoningEffort;
import io.haifa.agent.model.api.ModelReasoningMode;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.time.LocalDate;
import java.util.OptionalLong;
import java.util.Set;

/** Builds profiles only for governed Gemini standard or local dialect bindings. */
public final class GeminiModelProfileFactory {
    public static final String CURRENT_PROFILE_VERSION = "1.0";

    private GeminiModelProfileFactory() {}

    public static ModelBindingProfile fromSnapshot(ResolvedModelSnapshot snapshot, LocalDate verifiedOn) {
        boolean governed = ModelApiStyles.GOOGLE_GEMINI_GENERATE_CONTENT.equals(snapshot.apiStyle())
                && ((GeminiDialects.STANDARD.equals(snapshot.dialect())
                                && "google-gemini".equals(snapshot.providerId().value()))
                        || (GeminiDialects.CLIPROXYAPI_ANTIGRAVITY.equals(snapshot.dialect())
                                && "cliproxyapi-antigravity"
                                        .equals(snapshot.providerId().value()))
                        || (GeminiDialects.ANTIGRAVITY_DIRECT.equals(snapshot.dialect())
                                && "google-antigravity"
                                        .equals(snapshot.providerId().value())));
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
                1,
                snapshot.maxOutputTokens(),
                reasoning,
                governed ? ModelProfileStatus.VERIFIED : ModelProfileStatus.UNVERIFIED,
                verifiedOn);
    }
}
