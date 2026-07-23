package io.haifa.agent.application.project.tool.web;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class WebFetchProviderRegistry {
    private final Map<WebProviderId, WebFetchProvider> providers;

    public WebFetchProviderRegistry(Collection<? extends WebFetchProvider> providers) {
        Objects.requireNonNull(providers, "providers");
        Map<WebProviderId, WebFetchProvider> resolved = new LinkedHashMap<>();
        providers.stream()
                .sorted(Comparator.comparing(provider -> provider.descriptor().id()))
                .forEach(provider -> {
                    if (!provider.descriptor().capabilities().fetch()) {
                        throw new IllegalArgumentException("fetch provider descriptor does not support fetch");
                    }
                    WebFetchProvider previous =
                            resolved.putIfAbsent(provider.descriptor().id(), provider);
                    if (previous != null) {
                        throw new IllegalArgumentException("duplicate web fetch provider "
                                + provider.descriptor().id().value());
                    }
                });
        this.providers = Map.copyOf(resolved);
    }

    public WebFetchProvider require(WebProviderId id) {
        WebFetchProvider provider = providers.get(Objects.requireNonNull(id, "id"));
        if (provider == null) {
            throw new WebProviderException(
                    WebFailureCode.WEB_PROVIDER_NOT_CONFIGURED,
                    WebDispatchState.NOT_DISPATCHED,
                    "configured web fetch provider is unavailable");
        }
        return provider;
    }

    public List<WebFetchProvider> providers() {
        return providers.values().stream()
                .sorted(Comparator.comparing(provider -> provider.descriptor().id()))
                .toList();
    }
}
