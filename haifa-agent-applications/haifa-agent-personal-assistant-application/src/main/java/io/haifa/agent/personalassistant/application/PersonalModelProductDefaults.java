package io.haifa.agent.personalassistant.application;

import io.haifa.agent.model.api.EffectiveModelParameters;
import io.haifa.agent.model.api.ModelBindingProfile;
import io.haifa.agent.model.api.ModelParameterResolutionRequest;
import io.haifa.agent.model.api.ModelReasoningEffort;
import io.haifa.agent.model.api.ModelReasoningPolicy;
import io.haifa.agent.model.core.DefaultModelParameterResolver;
import java.util.Optional;

/** PA-specific defaults and labels layered over the reusable binding profile. */
public final class PersonalModelProductDefaults {
    public static final String PREFERENCE_SCHEMA_VERSION = "1.0";

    private final DefaultModelParameterResolver resolver = new DefaultModelParameterResolver();

    public EffectiveModelParameters resolve(ModelBindingProfile profile, PersonalModelPreferences preferences) {
        ModelReasoningPolicy reasoning =
                switch (preferences.responseMode()) {
                    case RECOMMENDED, FAST -> ModelReasoningPolicy.disabled();
                    case DEEP ->
                        ModelReasoningPolicy.enabled(preferences.effort().orElse(ModelReasoningEffort.HIGH));
                };
        int requestedOutput =
                switch (preferences.responseLength()) {
                    case SHORT -> 2_048;
                    case RECOMMENDED, STANDARD -> 8_192;
                    case LONG -> 16_384;
                };
        int output = Math.max(profile.minimumOutputTokens(), Math.min(profile.maximumOutputTokens(), requestedOutput));
        return resolver.resolve(
                profile,
                new ModelParameterResolutionRequest(
                        profile.bindingId(), profile.version(), profile.digest(), reasoning, output));
    }

    public PersonalModelControls controls(
            ModelBindingProfile profile, String bindingId, java.util.List<String> styleBindings) {
        boolean reasoningVisible = false; // Phase 2 opens this only after continuation/recovery validation.
        return new PersonalModelControls(
                new PersonalModelControls.ResponseModeControl(
                        "responseMode",
                        true,
                        true,
                        java.util.List.of(PersonalResponseMode.RECOMMENDED),
                        PersonalResponseMode.RECOMMENDED,
                        "Uses the reviewed Personal Assistant default",
                        "Quality and latency are balanced by the service."),
                new PersonalModelControls.ReasoningEffortControl(
                        "reasoningEffort",
                        reasoningVisible,
                        true,
                        profile.allowedReasoningEfforts().stream().sorted().toList(),
                        profile.allowedReasoningEfforts().contains(ModelReasoningEffort.HIGH)
                                ? ModelReasoningEffort.HIGH
                                : profile.allowedReasoningEfforts().stream()
                                        .sorted()
                                        .findFirst()
                                        .orElse(null),
                        "Reasoning remains disabled until Phase 2 verification",
                        "Shown only when this exact binding safely supports selectable effort."),
                new PersonalModelControls.ResponseLengthControl(
                        "responseLength",
                        true,
                        false,
                        java.util.List.of(PersonalResponseLength.values()),
                        PersonalResponseLength.RECOMMENDED,
                        "Recommended length",
                        "Choose a response length without exposing raw token limits."),
                new PersonalModelControls.ApiStyleControl(
                        "apiStyle",
                        styleBindings.size() > 1,
                        styleBindings.size() == 1,
                        styleBindings,
                        bindingId,
                        "Recommended connection",
                        "Advanced connection style for the same provider model."));
    }

    public static PersonalModelPreferences preferences(
            PersonalResponseMode mode, Optional<ModelReasoningEffort> effort, PersonalResponseLength length) {
        return new PersonalModelPreferences(mode, effort, length);
    }
}
