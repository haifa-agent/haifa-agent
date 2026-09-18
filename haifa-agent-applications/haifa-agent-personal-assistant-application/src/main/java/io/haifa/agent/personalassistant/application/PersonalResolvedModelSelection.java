package io.haifa.agent.personalassistant.application;

import io.haifa.agent.model.api.EffectiveModelParameters;
import java.util.Objects;

public record PersonalResolvedModelSelection(
        PersonalModelOption option,
        PersonalModelPreferences preferences,
        EffectiveModelParameters effectiveParameters,
        String runProfileId) {
    public PersonalResolvedModelSelection {
        Objects.requireNonNull(option);
        Objects.requireNonNull(preferences);
        Objects.requireNonNull(effectiveParameters);
        if (runProfileId == null || runProfileId.isBlank()) {
            throw new IllegalArgumentException("runProfileId must not be blank");
        }
    }
}
