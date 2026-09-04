package io.haifa.agent.model.core;

import io.haifa.agent.model.api.ModelApiBindingDefinition;
import io.haifa.agent.model.api.ModelBindingConsistencyValidator;
import io.haifa.agent.model.api.ModelDefinition;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelProviderDefinition;
import io.haifa.agent.model.api.ModelProviderId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Validated, immutable catalog manifest projected from explicitly listed YAML resources. */
public final class ModelCatalogManifest {
    private final List<ModelCatalogProvider> providers;
    private final Map<ModelProviderId, ModelCatalogProvider> providersById;
    private final Map<ModelDefinitionId, ModelCatalogBinding> bindingsById;
    private final String digest;

    public ModelCatalogManifest(List<ModelCatalogProvider> providers) {
        this.providers = List.copyOf(Objects.requireNonNull(providers, "providers must not be null"));
        if (this.providers.isEmpty()) throw new IllegalArgumentException("catalog providers must not be empty");
        LinkedHashMap<ModelProviderId, ModelCatalogProvider> providerIndex = new LinkedHashMap<>();
        LinkedHashMap<ModelDefinitionId, ModelCatalogBinding> bindingIndex = new LinkedHashMap<>();
        for (ModelCatalogProvider provider : this.providers) {
            Objects.requireNonNull(provider, "catalog provider must not be null");
            if (providerIndex.putIfAbsent(provider.id(), provider) != null) {
                throw new IllegalArgumentException("duplicate provider id: " + provider.id());
            }
            for (ModelCatalogBinding binding : provider.bindings()) {
                if (bindingIndex.putIfAbsent(binding.definition().id(), binding) != null) {
                    throw new IllegalArgumentException(
                            "duplicate binding id: " + binding.definition().id());
                }
            }
        }
        providersById = Map.copyOf(providerIndex);
        bindingsById = Map.copyOf(bindingIndex);
        digest = digest(this.providers);
    }

    public List<ModelCatalogProvider> providers() {
        return providers;
    }

    public Optional<ModelCatalogProvider> provider(String providerId) {
        return Optional.ofNullable(providersById.get(new ModelProviderId(providerId)));
    }

    public Optional<ModelCatalogBinding> binding(String bindingId) {
        return Optional.ofNullable(bindingsById.get(new ModelDefinitionId(bindingId)));
    }

    public String digest() {
        return digest;
    }

    /**
     * Combines this immutable catalog with product deployment connection data. The operation is fail-closed: a
     * deployment cannot add bindings, alter dialects, or select a binding owned by another provider.
     */
    public ModelCatalogProjection project(ModelCatalogDeployment deployment) {
        Objects.requireNonNull(deployment, "deployment must not be null");
        List<ModelProviderDefinition> projectedProviders = new ArrayList<>();
        List<ModelCatalogBinding> projectedBindings = new ArrayList<>();
        for (ModelCatalogDeployment.Provider configured : deployment.providers()) {
            if (!configured.enabled()) continue;
            ModelCatalogProvider catalogProvider = providersById.get(configured.id());
            if (catalogProvider == null) {
                throw new IllegalArgumentException(
                        "deployment provider is not registered in the catalog: " + configured.id());
            }
            List<ModelCatalogBinding> selected = catalogProvider.bindings().stream()
                    .filter(binding -> configured
                            .allowedBindings()
                            .contains(binding.definition().id()))
                    .toList();
            if (selected.size() != configured.allowedBindings().size()) {
                ModelDefinitionId unknown = configured.allowedBindings().stream()
                        .filter(bindingId -> selected.stream()
                                .noneMatch(binding -> binding.definition().id().equals(bindingId)))
                        .findFirst()
                        .orElseThrow();
                throw new IllegalArgumentException(
                        "deployment binding does not belong to provider: " + unknown + "/" + configured.id());
            }
            List<ModelApiBindingDefinition> apiBindings = selected.stream()
                    .map(binding -> new ModelApiBindingDefinition(
                            binding.apiBinding().style(),
                            binding.apiBinding().dialect(),
                            configured
                                    .bindingEndpointOverrides()
                                    .get(binding.definition().id())))
                    .distinct()
                    .toList();
            List<ModelDefinition> definitions =
                    selected.stream().map(ModelCatalogBinding::definition).toList();
            ModelProviderDefinition provider = new ModelProviderDefinition(
                    catalogProvider.id(),
                    catalogProvider.version(),
                    catalogProvider.displayName(),
                    configured.endpoint(),
                    configured.credentialRef(),
                    configured.nativeStreaming(),
                    catalogProvider.status(),
                    apiBindings,
                    definitions,
                    Map.of(),
                    Map.of());
            ModelBindingConsistencyValidator.validateAll(
                    provider,
                    selected.stream()
                            .collect(java.util.stream.Collectors.toUnmodifiableMap(
                                    binding -> binding.definition().id().value(), ModelCatalogBinding::profile)));
            projectedProviders.add(provider);
            projectedBindings.addAll(selected);
        }
        if (projectedProviders.isEmpty()) {
            throw new IllegalArgumentException("deployment must enable at least one catalog provider");
        }
        return new ModelCatalogProjection(projectedProviders, projectedBindings);
    }

    private static String digest(List<ModelCatalogProvider> providers) {
        String canonical = providers.stream()
                .sorted(Comparator.comparing(provider -> provider.id().value()))
                .map(ModelCatalogManifest::canonicalProvider)
                .reduce((left, right) -> left + "\n" + right)
                .orElseThrow();
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    private static String canonicalProvider(ModelCatalogProvider provider) {
        String methods = provider.authenticationMethods().stream()
                .map(Enum::name)
                .sorted()
                .toList()
                .toString();
        String bindings = provider.bindings().stream()
                .sorted(Comparator.comparing(
                        binding -> binding.definition().id().value()))
                .map(binding -> String.join(
                        "|",
                        encode(binding.definition().id().value()),
                        encode(binding.definition().version()),
                        encode(binding.definition().providerModelId()),
                        binding.definition().status().name(),
                        encode(binding.apiBinding().style().value()),
                        encode(binding.apiBinding().dialect()),
                        encode(binding.profile().canonicalString())))
                .reduce((left, right) -> left + ";" + right)
                .orElseThrow();
        return String.join(
                "|",
                encode(provider.id().value()),
                encode(provider.version()),
                provider.status().name(),
                methods,
                bindings);
    }

    private static String encode(String value) {
        return value.length() + ":" + value;
    }
}
