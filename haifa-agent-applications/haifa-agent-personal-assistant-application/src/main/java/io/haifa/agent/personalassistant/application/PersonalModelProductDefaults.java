package io.haifa.agent.personalassistant.application;

import io.haifa.agent.model.api.EffectiveModelParameters;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelBindingProfile;
import io.haifa.agent.model.api.ModelParameterResolutionRequest;
import io.haifa.agent.model.api.ModelReasoningEffort;
import io.haifa.agent.model.api.ModelReasoningMode;
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
                    case RECOMMENDED ->
                        profile.allowedReasoningModes().contains(ModelReasoningMode.ENABLED)
                                ? ModelReasoningPolicy.enabled(recommendedEffort(profile))
                                : ModelReasoningPolicy.disabled();
                    case FAST -> ModelReasoningPolicy.disabled();
                    case DEEP ->
                        ModelReasoningPolicy.enabled(preferences.effort().orElseGet(() -> recommendedEffort(profile)));
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
            ModelBindingProfile profile, java.util.List<String> styleBindings, String recommendedBindingId) {
        boolean reasoningSelectable = profile.toolReasoningContinuationRequired()
                && profile.allowedReasoningModes()
                        .containsAll(java.util.Set.of(ModelReasoningMode.DISABLED, ModelReasoningMode.ENABLED))
                && (ModelApiStyles.OPENAI_CHAT_COMPLETIONS.equals(profile.apiStyle())
                        || ModelApiStyles.ANTHROPIC_MESSAGES.equals(profile.apiStyle()));
        java.util.List<PersonalResponseMode> responseModes = reasoningSelectable
                ? java.util.List.of(
                        PersonalResponseMode.RECOMMENDED, PersonalResponseMode.FAST, PersonalResponseMode.DEEP)
                : java.util.List.of(PersonalResponseMode.RECOMMENDED);
        return new PersonalModelControls(
                new PersonalModelControls.ResponseModeControl(
                        "responseMode",
                        true,
                        !reasoningSelectable,
                        responseModes,
                        PersonalResponseMode.RECOMMENDED,
                        reasoningSelectable ? "Thinking on · High" : "Uses the reviewed connection default",
                        reasoningSelectable
                                ? "Choose faster direct answers or deeper reasoning when the task needs it."
                                : "This connection does not expose a verified reasoning switch."),
                new PersonalModelControls.ReasoningEffortControl(
                        "reasoningEffort",
                        reasoningSelectable,
                        !reasoningSelectable,
                        profile.allowedReasoningEfforts().stream().sorted().toList(),
                        profile.allowedReasoningEfforts().contains(ModelReasoningEffort.HIGH)
                                ? ModelReasoningEffort.HIGH
                                : profile.allowedReasoningEfforts().stream()
                                        .sorted()
                                        .findFirst()
                                        .orElse(null),
                        reasoningSelectable ? "High reasoning effort" : "Reasoning adjustment unavailable",
                        "Available only in Deep mode for this exact verified connection."),
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
                        recommendedBindingId,
                        "Recommended connection",
                        "Advanced connection style for the same provider model."));
    }

    public static PersonalModelPreferences preferences(
            PersonalResponseMode mode, Optional<ModelReasoningEffort> effort, PersonalResponseLength length) {
        return new PersonalModelPreferences(mode, effort, length);
    }

    private static ModelReasoningEffort recommendedEffort(ModelBindingProfile profile) {
        return profile.allowedReasoningEfforts().contains(ModelReasoningEffort.HIGH)
                ? ModelReasoningEffort.HIGH
                : profile.allowedReasoningEfforts().stream()
                        .sorted()
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("reasoning effort is unavailable"));
    }
}
