package io.haifa.agent.personalassistant.application;

import io.haifa.agent.model.api.EffectiveModelParameters;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelBindingProfile;
import io.haifa.agent.model.api.ModelParameterResolutionRequest;
import io.haifa.agent.model.api.ModelReasoningBehavior;
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
                    case RECOMMENDED -> recommendedReasoning(profile);
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
        int output = Math.max(
                profile.executionLimits().minimumOutputTokens(),
                Math.min(profile.executionLimits().maximumOutputTokens(), requestedOutput));
        return resolver.resolve(
                profile,
                new ModelParameterResolutionRequest(
                        profile.bindingId(), profile.version(), profile.digest(), reasoning, output));
    }

    public PersonalModelControls controls(
            ModelBindingProfile profile, java.util.List<String> styleBindings, String recommendedBindingId) {
        boolean reasoningSwitchable = profile.toolReasoningContinuationRequired()
                && profile.allowedReasoningModes()
                        .containsAll(java.util.Set.of(ModelReasoningMode.DISABLED, ModelReasoningMode.ENABLED))
                && (ModelApiStyles.OPENAI_CHAT_COMPLETIONS.equals(profile.apiStyle())
                        || ModelApiStyles.ANTHROPIC_MESSAGES.equals(profile.apiStyle()));
        boolean alwaysReasoning = profile.reasoningBehavior() == ModelReasoningBehavior.ALWAYS
                && profile.toolReasoningContinuationRequired();
        boolean effortSelectable =
                profile.allowedReasoningEfforts().size() > 1 && (reasoningSwitchable || alwaysReasoning);
        java.util.List<PersonalResponseMode> responseModes = reasoningSwitchable
                ? java.util.List.of(
                        PersonalResponseMode.RECOMMENDED, PersonalResponseMode.FAST, PersonalResponseMode.DEEP)
                : alwaysReasoning && effortSelectable
                        ? java.util.List.of(PersonalResponseMode.RECOMMENDED, PersonalResponseMode.DEEP)
                        : java.util.List.of(PersonalResponseMode.RECOMMENDED);
        return new PersonalModelControls(
                new PersonalModelControls.ResponseModeControl(
                        "responseMode",
                        true,
                        responseModes.size() == 1,
                        responseModes,
                        PersonalResponseMode.RECOMMENDED,
                        reasoningSwitchable || alwaysReasoning
                                ? "Thinking on · High"
                                : "Uses the reviewed connection default",
                        reasoningSwitchable
                                ? "Choose faster direct answers or deeper reasoning when the task needs it."
                                : alwaysReasoning
                                        ? "This model always reasons; Deep mode exposes its verified effort levels."
                                        : "This connection does not expose a verified reasoning switch."),
                new PersonalModelControls.ReasoningEffortControl(
                        "reasoningEffort",
                        effortSelectable,
                        !effortSelectable,
                        profile.allowedReasoningEfforts().stream().sorted().toList(),
                        profile.allowedReasoningEfforts().contains(ModelReasoningEffort.HIGH)
                                ? ModelReasoningEffort.HIGH
                                : profile.allowedReasoningEfforts().stream()
                                        .sorted()
                                        .findFirst()
                                        .orElse(null),
                        effortSelectable ? "High reasoning effort" : "Reasoning adjustment unavailable",
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

    private static ModelReasoningPolicy recommendedReasoning(ModelBindingProfile profile) {
        if (profile.reasoningBehavior() == ModelReasoningBehavior.ADAPTIVE
                && profile.allowedReasoningModes().contains(ModelReasoningMode.ADAPTIVE)) {
            return new ModelReasoningPolicy(
                    ModelReasoningMode.ADAPTIVE,
                    Optional.of(recommendedEffort(profile)),
                    java.util.OptionalLong.empty());
        }
        return profile.allowedReasoningModes().contains(ModelReasoningMode.ENABLED)
                ? ModelReasoningPolicy.enabled(recommendedEffort(profile))
                : ModelReasoningPolicy.disabled();
    }
}
