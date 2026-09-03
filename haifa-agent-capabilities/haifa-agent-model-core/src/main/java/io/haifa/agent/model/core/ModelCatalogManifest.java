package io.haifa.agent.model.core;

import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelProviderId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
