package io.haifa.agent.application.project.tool.web;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class WebSearchProviderRegistry {
    private final Map<WebProviderId, WebSearchProvider> providers;

    public WebSearchProviderRegistry(Collection<? extends WebSearchProvider> providers) {
        Objects.requireNonNull(providers, "providers");
        Map<WebProviderId, WebSearchProvider> resolved = new LinkedHashMap<>();
        providers.stream()
                .sorted(Comparator.comparing(provider -> provider.descriptor().id()))
                .forEach(provider -> {
                    if (!provider.descriptor().capabilities().search()) {
                        throw new IllegalArgumentException("search provider descriptor does not support search");
                    }
                    WebSearchProvider previous =
                            resolved.putIfAbsent(provider.descriptor().id(), provider);
                    if (previous != null) {
                        throw new IllegalArgumentException("duplicate web search provider "
                                + provider.descriptor().id().value());
                    }
                });
        this.providers = Map.copyOf(resolved);
    }

    public WebSearchProvider require(WebProviderId id) {
        WebSearchProvider provider = providers.get(Objects.requireNonNull(id, "id"));
        if (provider == null) {
            throw new WebProviderException(
                    WebFailureCode.WEB_PROVIDER_NOT_CONFIGURED,
                    io.haifa.agent.application.project.tool.web.WebDispatchState.NOT_DISPATCHED,
                    "configured web search provider is unavailable");
        }
        return provider;
    }

    public List<WebSearchProvider> providers() {
        return providers.values().stream()
                .sorted(Comparator.comparing(provider -> provider.descriptor().id()))
                .toList();
    }
}
