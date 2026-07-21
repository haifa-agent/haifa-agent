package io.haifa.agent.model.core;

import io.haifa.agent.model.api.ModelDefinition;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelProviderDefinition;
import io.haifa.agent.model.api.ModelProviderId;
import java.util.List;
import java.util.Optional;

/** Immutable lookup view over configured providers and models. */
public interface ModelCatalog {
    List<ModelProviderDefinition> providers();

    Optional<ModelProviderDefinition> provider(ModelProviderId id);

    Optional<ModelDefinition> model(ModelDefinitionId id);
}
