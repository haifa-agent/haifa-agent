package io.haifa.agent.model.core;

import io.haifa.agent.model.api.ModelApiBindingDefinition;
import io.haifa.agent.model.api.ModelBindingConsistencyValidator;
import io.haifa.agent.model.api.ModelBindingProfile;
import io.haifa.agent.model.api.ModelDefinition;
import java.util.Objects;

/** Immutable projection of one exact static catalog binding. */
public record ModelCatalogBinding(
        ModelDefinition definition, ModelApiBindingDefinition apiBinding, ModelBindingProfile profile) {
    public ModelCatalogBinding {
        definition = Objects.requireNonNull(definition, "definition must not be null");
        apiBinding = Objects.requireNonNull(apiBinding, "apiBinding must not be null");
        profile = Objects.requireNonNull(profile, "profile must not be null");
        if (!definition.style().equals(apiBinding.style())) {
            throw new IllegalArgumentException("catalog binding API style must match model definition");
        }
        ModelBindingConsistencyValidator.validate(definition, profile);
    }
}
