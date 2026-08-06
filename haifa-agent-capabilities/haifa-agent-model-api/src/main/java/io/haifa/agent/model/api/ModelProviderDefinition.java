package io.haifa.agent.model.api;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable provider connection definition and its governed ordered protocol/model list. */
public record ModelProviderDefinition(
        ModelProviderId id,
        String version,
        String displayName,
        URI endpoint,
        CredentialRef credentialRef,
        boolean nativeStreaming,
        ProviderStatus status,
        List<ModelApiBindingDefinition> apiBindings,
        List<ModelDefinition> models,
        Map<String, Object> options,
        Map<String, Object> metadata) {
    public ModelProviderDefinition {
        id = Objects.requireNonNull(id, "id must not be null");
        version = ModelValues.text(version, "version");
        displayName = ModelValues.text(displayName, "displayName");
        endpoint = ModelApiBindingDefinition.normalizeEndpoint(endpoint, "endpoint");
        credentialRef = Objects.requireNonNull(credentialRef, "credentialRef must not be null");
        status = Objects.requireNonNull(status, "status must not be null");
        apiBindings = List.copyOf(Objects.requireNonNull(apiBindings, "apiBindings must not be null"));
        if (apiBindings.isEmpty()) throw new IllegalArgumentException("apiBindings must not be empty");
        HashSet<ApiStyleId> styles = new HashSet<>();
        for (ModelApiBindingDefinition binding : apiBindings) {
            Objects.requireNonNull(binding, "apiBinding must not be null");
            if (!styles.add(binding.style())) {
                throw new IllegalArgumentException("duplicate API style binding: " + binding.style());
            }
        }
        models = List.copyOf(Objects.requireNonNull(models, "models must not be null"));
        if (models.isEmpty()) throw new IllegalArgumentException("models must not be empty");
        HashSet<ModelDefinitionId> ids = new HashSet<>();
        HashSet<String> providerModelBindings = new HashSet<>();
        for (ModelDefinition model : models) {
            Objects.requireNonNull(model, "model must not be null");
            if (!id.equals(model.providerId())) throw new IllegalArgumentException("model belongs to another provider");
            if (!ids.add(model.id())) throw new IllegalArgumentException("duplicate model id: " + model.id());
            String providerModelBinding = model.style().value() + "\n" + model.providerModelId();
            if (!providerModelBindings.add(providerModelBinding)) {
                throw new IllegalArgumentException(
                        "duplicate provider model id within API style: " + model.providerModelId());
            }
            if (!styles.contains(model.style())) {
                throw new IllegalArgumentException("model references an unbound API style: " + model.style());
            }
        }
        options = ModelValues.map(options, "options");
        metadata = ModelValues.map(metadata, "metadata");
    }

    public ModelApiBindingDefinition binding(ApiStyleId style) {
        return apiBindings.stream()
                .filter(binding -> binding.style().equals(style))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("provider has no API binding for style: " + style));
    }
}
