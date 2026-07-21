package io.haifa.agent.model.api;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable provider connection definition and its governed ordered model list. */
public record ModelProviderDefinition(
        ModelProviderId id,
        String version,
        String displayName,
        String adapterType,
        URI endpoint,
        CredentialRef credentialRef,
        ProviderStatus status,
        List<ModelDefinition> models,
        Map<String, Object> options,
        Map<String, Object> metadata) {
    public ModelProviderDefinition {
        id = Objects.requireNonNull(id, "id must not be null");
        version = ModelValues.text(version, "version");
        displayName = ModelValues.text(displayName, "displayName");
        adapterType = ModelValues.text(adapterType, "adapterType");
        endpoint = Objects.requireNonNull(endpoint, "endpoint must not be null");
        if (!endpoint.isAbsolute() || endpoint.getHost() == null) {
            throw new IllegalArgumentException("endpoint must be an absolute network URI");
        }
        credentialRef = Objects.requireNonNull(credentialRef, "credentialRef must not be null");
        status = Objects.requireNonNull(status, "status must not be null");
        models = List.copyOf(Objects.requireNonNull(models, "models must not be null"));
        if (models.isEmpty()) throw new IllegalArgumentException("models must not be empty");
        HashSet<ModelDefinitionId> ids = new HashSet<>();
        HashSet<String> providerIds = new HashSet<>();
        for (ModelDefinition model : models) {
            Objects.requireNonNull(model, "model must not be null");
            if (!id.equals(model.providerId())) throw new IllegalArgumentException("model belongs to another provider");
            if (!ids.add(model.id())) throw new IllegalArgumentException("duplicate model id: " + model.id());
            if (!providerIds.add(model.providerModelId())) {
                throw new IllegalArgumentException("duplicate provider model id: " + model.providerModelId());
            }
        }
        options = ModelValues.map(options, "options");
        metadata = ModelValues.map(metadata, "metadata");
    }
}
