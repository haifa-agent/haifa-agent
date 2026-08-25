package io.haifa.agent.personalassistant.server.configuration.model;

import io.haifa.agent.personalassistant.server.configuration.product.PersonalAssistantProperties;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Routes explicitly configured model origins through their provider-level HTTP proxy. */
final class PersonalModelProxySelector extends ProxySelector {
    private static final List<Proxy> DIRECT = List.of(Proxy.NO_PROXY);

    private final Map<Origin, Proxy> proxies;
    private final ProxySelector fallback;

    private PersonalModelProxySelector(Map<Origin, Proxy> proxies, ProxySelector fallback) {
        this.proxies = Map.copyOf(proxies);
        this.fallback = fallback;
    }

    static ProxySelector from(List<PersonalAssistantProperties.ModelProvider> providers) {
        return from(providers, ProxySelector.getDefault());
    }

    static ProxySelector from(List<PersonalAssistantProperties.ModelProvider> providers, ProxySelector fallback) {
        Objects.requireNonNull(providers, "providers must not be null");
        Map<Origin, Optional<URI>> configuredRoutes = new LinkedHashMap<>();
        for (PersonalAssistantProperties.ModelProvider provider : providers) {
            Optional<URI> proxy = Optional.ofNullable(provider.proxy());
            register(configuredRoutes, Origin.from(provider.endpoint()), proxy);
            for (PersonalAssistantProperties.ApiBinding binding : provider.apiBindings()) {
                register(
                        configuredRoutes,
                        Origin.from(binding.endpoint() == null ? provider.endpoint() : binding.endpoint()),
                        proxy);
            }
        }

        Map<Origin, Proxy> proxies = new LinkedHashMap<>();
        configuredRoutes.forEach((origin, proxy) -> proxy.ifPresent(value -> proxies.put(
                origin,
                new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(value.getHost(), value.getPort())))));
        return new PersonalModelProxySelector(proxies, fallback);
    }

    private static void register(Map<Origin, Optional<URI>> routes, Origin origin, Optional<URI> proxy) {
        Optional<URI> existing = routes.putIfAbsent(origin, proxy);
        if (existing != null && !existing.equals(proxy)) {
            throw new IllegalArgumentException(
                    "model providers sharing an endpoint origin must use the same proxy configuration");
        }
    }

    @Override
    public List<Proxy> select(URI uri) {
        Objects.requireNonNull(uri, "uri must not be null");
        Proxy configured = proxies.get(Origin.from(uri));
        if (configured != null) return List.of(configured);
        if (fallback == null || fallback == this) return DIRECT;
        List<Proxy> selected = fallback.select(uri);
        return selected == null || selected.isEmpty() ? DIRECT : List.copyOf(selected);
    }

    @Override
    public void connectFailed(URI uri, SocketAddress socketAddress, IOException failure) {
        Objects.requireNonNull(uri, "uri must not be null");
        Objects.requireNonNull(socketAddress, "socketAddress must not be null");
        Objects.requireNonNull(failure, "failure must not be null");
        if (!proxies.containsKey(Origin.from(uri)) && fallback != null && fallback != this) {
            fallback.connectFailed(uri, socketAddress, failure);
        }
    }

    private record Origin(String scheme, String host, int port) {
        private static Origin from(URI uri) {
            Objects.requireNonNull(uri, "endpoint must not be null");
            String scheme = Objects.requireNonNull(uri.getScheme(), "endpoint scheme must not be null")
                    .toLowerCase(Locale.ROOT);
            String host = Objects.requireNonNull(uri.getHost(), "endpoint host must not be null")
                    .toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            if (port < 0) {
                port = "https".equals(scheme) ? 443 : "http".equals(scheme) ? 80 : -1;
            }
            return new Origin(scheme, host, port);
        }
    }
}
