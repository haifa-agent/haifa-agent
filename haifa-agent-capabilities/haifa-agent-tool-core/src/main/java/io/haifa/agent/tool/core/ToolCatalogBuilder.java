package io.haifa.agent.tool.core;

import io.haifa.agent.tool.api.FrozenToolBinding;
import io.haifa.agent.tool.api.ToolAlias;
import io.haifa.agent.tool.api.ToolCoordinate;
import io.haifa.agent.tool.api.ToolDefinition;
import io.haifa.agent.tool.api.ToolProvider;
import io.haifa.agent.tool.api.ToolProviderId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ToolCatalogBuilder {
    private final ToolDefinitionCanonicalizer canonicalizer = new ToolDefinitionCanonicalizer();
    private final ToolDefinitionValidator validator = new ToolDefinitionValidator();
    private final Map<ToolAlias, Registration> registrations = new LinkedHashMap<>();
    private boolean frozen;

    public ToolCatalogBuilder register(
            ToolAlias alias, ToolDefinition definition, String providerBindingReference, ToolProvider provider) {
        if (frozen) throw new IllegalStateException("tool catalog builder is frozen");
        Objects.requireNonNull(alias, "alias");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(provider, "provider");
        if (!provider.id().equals(definition.providerId())) {
            throw new IllegalArgumentException("provider id does not match tool definition");
        }
        validator.validate(definition);
        Registration previous = registrations.putIfAbsent(
                alias, new Registration(alias, definition, providerBindingReference, provider));
        if (previous != null) {
            throw new IllegalArgumentException("duplicate tool alias " + alias.value());
        }
        return this;
    }

    public DefaultToolCatalog freeze() {
        if (frozen) throw new IllegalStateException("tool catalog builder is already frozen");
        frozen = true;
        List<ResolvedRegistration> resolved = registrations.values().stream()
                .map(registration -> new ResolvedRegistration(
                        registration,
                        new ToolCoordinate(
                                registration.definition().name(),
                                registration.definition().version(),
                                registration.definition().providerId(),
                                canonicalizer.hash(registration.definition()))))
                .sorted(Comparator.comparing(item -> item.registration().alias()))
                .toList();
        long distinctCoordinates = resolved.stream()
                .map(ResolvedRegistration::coordinate)
                .distinct()
                .count();
        if (distinctCoordinates != resolved.size()) {
            throw new IllegalArgumentException("duplicate tool coordinate");
        }
        Map<ToolProviderId, ToolProvider> providers = new LinkedHashMap<>();
        for (Registration registration : registrations.values()) {
            ToolProvider existing =
                    providers.putIfAbsent(registration.provider().id(), registration.provider());
            if (existing != null && existing != registration.provider()) {
                throw new IllegalArgumentException("ambiguous tool provider "
                        + registration.provider().id().value());
            }
        }
        String digestSource = resolved.stream()
                .map(item -> item.registration().alias().value() + "="
                        + item.coordinate().externalForm())
                .reduce("", (left, right) -> left + "\n" + right);
        String digest = ToolDefinitionCanonicalizer.sha256(digestSource);
        List<FrozenToolBinding> bindings = new ArrayList<>();
        for (ResolvedRegistration item : resolved) {
            bindings.add(new FrozenToolBinding(
                    item.registration().alias(),
                    item.coordinate(),
                    item.registration().definition(),
                    item.registration().providerBindingReference(),
                    digest));
        }
        return new DefaultToolCatalog(digest, bindings, providers);
    }

    private record Registration(
            ToolAlias alias, ToolDefinition definition, String providerBindingReference, ToolProvider provider) {}

    private record ResolvedRegistration(Registration registration, ToolCoordinate coordinate) {}
}
