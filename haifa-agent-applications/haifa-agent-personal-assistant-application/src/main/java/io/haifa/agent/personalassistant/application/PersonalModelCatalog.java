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
