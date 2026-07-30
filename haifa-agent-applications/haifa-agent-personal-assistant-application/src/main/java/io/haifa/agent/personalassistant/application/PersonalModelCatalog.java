package io.haifa.agent.personalassistant.application;

import java.util.List;
import java.util.Optional;

public interface PersonalModelCatalog {
    String defaultModelId();

    List<PersonalModelOption> available();

    default Optional<PersonalModelOption> find(String modelId) {
        return available().stream().filter(value -> value.id().equals(modelId)).findFirst();
    }
}
