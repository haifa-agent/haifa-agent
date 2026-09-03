package io.haifa.agent.model.core;

import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelProviderId;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Product-owned connection and enablement data combined with a static {@link ModelCatalogManifest} at trusted
 * assembly time.
 *
 * <p>This deliberately contains no provider model metadata, dialect, capability, or invocation policy. Those
 * execution semantics are catalog-owned and remain frozen in the resolved snapshot.
 */
public final class ModelCatalogDeployment {
    private final List<Provider> providers;

    public ModelCatalogDeployment(List<Provider> providers) {
        this.providers = List.copyOf(Objects.requireNonNull(providers, "providers must not be null"));
        if (this.providers.isEmpty()) throw new IllegalArgumentException("deployment providers must not be empty");
        HashSet<ModelProviderId> ids = new HashSet<>();
        for (Provider provider : this.providers) {
            if (!ids.add(provider.id())) {
                throw new IllegalArgumentException("duplicate deployment provider id: " + provider.id());
            }
        }
    }

    public List<Provider> providers() {
        return providers;
    }

    /** One product deployment connection and its explicit selectable binding allowlist. */
    public record Provider(
            ModelProviderId id,
            URI endpoint,
            CredentialRef credentialRef,
            boolean nativeStreaming,
            boolean enabled,
            Set<ModelDefinitionId> allowedBindings,
            Map<ModelDefinitionId, URI> bindingEndpointOverrides) {
        public Provider {
            id = Objects.requireNonNull(id, "id must not be null");
            endpoint = requireAbsolute(endpoint, "endpoint");
            credentialRef = Objects.requireNonNull(credentialRef, "credentialRef must not be null");
            allowedBindings = Set.copyOf(Objects.requireNonNull(allowedBindings, "allowedBindings must not be null"));
            if (allowedBindings.isEmpty()) {
                throw new IllegalArgumentException("deployment provider allowedBindings must not be empty");
            }
            bindingEndpointOverrides = Map.copyOf(Objects.requireNonNull(
                    bindingEndpointOverrides, "bindingEndpointOverrides must not be null"));
            if (!allowedBindings.containsAll(bindingEndpointOverrides.keySet())) {
                throw new IllegalArgumentException("binding endpoint override must belong to the deployment allowlist");
            }
            bindingEndpointOverrides.forEach((bindingId, override) -> {
                Objects.requireNonNull(bindingId, "binding endpoint override id must not be null");
                requireAbsolute(override, "binding endpoint override");
            });
        }

        private static URI requireAbsolute(URI value, String field) {
            URI normalized = Objects.requireNonNull(value, field + " must not be null");
            if (!normalized.isAbsolute()) throw new IllegalArgumentException(field + " must be absolute");
            return normalized;
        }
    }
}
