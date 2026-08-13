package io.haifa.agent.personalassistant.application;

import io.haifa.agent.personalassistant.application.mission.MissionModelBinding;
import java.util.List;
import java.util.Optional;

public interface PersonalModelCatalog {
    String defaultModelId();

    List<PersonalModelOption> available();

    Optional<MissionModelBinding> binding(String modelId);

    default Optional<PersonalModelOption> find(String modelId) {
        return available().stream().filter(value -> value.id().equals(modelId)).findFirst();
    }
}
