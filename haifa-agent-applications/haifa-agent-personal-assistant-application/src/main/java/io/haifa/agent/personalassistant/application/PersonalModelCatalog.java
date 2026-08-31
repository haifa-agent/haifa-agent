package io.haifa.agent.personalassistant.application;

import io.haifa.agent.model.api.ModelBindingProfile;
import io.haifa.agent.personalassistant.application.mission.MissionModelBinding;
import java.util.List;
import java.util.Optional;

public interface PersonalModelCatalog {
    String defaultModelId();

    List<PersonalModelOption> available();

    Optional<MissionModelBinding> binding(String modelId);

    Optional<ModelBindingProfile> profile(String modelBindingId);

    PersonalResolvedModelSelection resolve(PersonalModelSelectionRequest request);

    /**
     * Computes whether a persisted selection still resolves against the current trusted Profile.
     *
     * <p>This is a pure read signal used by the ordinary PA HTTP projection. It never exposes Profile
     * version or digest, and it never mutates selection state. {@link #resolve} remains the authority
     * that rejects an incompatible selection at submit time.
     */
    default PersonalSelectionCompatibility selectionCompatibility(
            String bindingId, String preferenceSchemaVersion, PersonalModelPreferences preferences) {
        return available().stream()
                .filter(option -> option.id().equals(bindingId))
                .findFirst()
                .map(option -> !"AVAILABLE".equals(option.availability())
                        ? PersonalSelectionCompatibility.UNAVAILABLE
                        : !preferenceSchemaVersion.equals(option.preferenceSchemaVersion())
                                ? PersonalSelectionCompatibility.RESELECTION_REQUIRED
                                : compatiblePreferences(option, preferences)
                                        ? PersonalSelectionCompatibility.CURRENT
                                        : PersonalSelectionCompatibility.RESELECTION_REQUIRED)
                .orElse(PersonalSelectionCompatibility.UNAVAILABLE);
    }

    /** Returns the catalog option for an id without filtering availability or mutating selection state. */
    default Optional<PersonalModelOption> optionById(String modelId) {
        return available().stream()
                .filter(option -> option.id().equals(modelId))
                .findFirst();
    }

    /** Mirrors the preference checks enforced by {@link #resolve}; profile version/digest are not visible here. */
    static boolean compatiblePreferences(PersonalModelOption option, PersonalModelPreferences preferences) {
        if (option.controls().responseMode().readOnly()
                && preferences.responseMode()
                        != option.controls().responseMode().recommendedValue()) {
            return false;
        }
        if (!option.controls().reasoningEffort().visible()
                && preferences.effort().isPresent()) {
            return false;
        }
        if (!option.controls().responseMode().allowedValues().contains(preferences.responseMode())
                || !option.controls().responseLength().allowedValues().contains(preferences.responseLength())
                || preferences
                        .effort()
                        .filter(value -> !option.controls()
                                .reasoningEffort()
                                .allowedValues()
                                .contains(value))
                        .isPresent()) {
            return false;
        }
        if (option.controls().reasoningEffort().readOnly()
                && preferences.effort().isPresent()
                && !preferences
                        .effort()
                        .equals(Optional.ofNullable(
                                option.controls().reasoningEffort().recommendedValue()))) {
            return false;
        }
        if (option.controls().responseLength().readOnly()
                && preferences.responseLength()
                        != option.controls().responseLength().recommendedValue()) {
            return false;
        }
        return true;
    }

    List<PersonalResolvedModelSelection> runProfiles();

    default PersonalResolvedModelSelection defaultSelection() {
        PersonalModelOption option = find(defaultModelId()).orElseThrow();
        return resolve(new PersonalModelSelectionRequest(
                option.id(),
                option.preferenceSchemaVersion(),
                option.profileVersion(),
                option.profileDigest(),
                option.recommendedPreferences()));
    }

    default Optional<PersonalModelOption> find(String modelId) {
        return available().stream().filter(value -> value.id().equals(modelId)).findFirst();
    }
}
