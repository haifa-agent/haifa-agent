package io.haifa.agent.model.core;

import io.haifa.agent.model.api.ModelAuthenticationMethod;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ProviderStatus;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Provider-owned static catalog facts, deliberately excluding deployment connection data. */
public record ModelCatalogProvider(
        ModelProviderId id,
        String version,
        String displayName,
        ProviderStatus status,
        Set<ModelAuthenticationMethod> authenticationMethods,
        List<ModelCatalogBinding> bindings) {
    public ModelCatalogProvider {
        id = Objects.requireNonNull(id, "id must not be null");
        version = requireText(version, "version");
        displayName = requireText(displayName, "displayName");
        status = Objects.requireNonNull(status, "status must not be null");
        authenticationMethods =
                Set.copyOf(Objects.requireNonNull(authenticationMethods, "authenticationMethods must not be null"));
        if (authenticationMethods.isEmpty()) {
            throw new IllegalArgumentException("authenticationMethods must not be empty");
        }
        bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings must not be null"));
        if (bindings.isEmpty()) throw new IllegalArgumentException("bindings must not be empty");
        HashSet<String> ids = new HashSet<>();
        for (ModelCatalogBinding binding : bindings) {
            Objects.requireNonNull(binding, "binding must not be null");
            if (!id.equals(binding.definition().providerId())) {
                throw new IllegalArgumentException("catalog binding belongs to another provider");
            }
            if (!ids.add(binding.definition().id().value())) {
                throw new IllegalArgumentException("duplicate binding id in provider: "
                        + binding.definition().id());
            }
        }
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
