package io.haifa.agent.personalassistant.server.configuration.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.haifa.agent.personalassistant.server.configuration.product.PersonalAssistantProperties;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Product-local, non-secret proxy route preferences for configured remote model providers. */
public final class PersonalModelProxySettings {
    private static final int SCHEMA_VERSION = 1;
    private static final long MAX_FILE_BYTES = 64 * 1024;

    private final Map<String, PersonalAssistantProperties.ModelProvider> providers;
    private final Map<Origin, Set<String>> providerIdsByOrigin;
    private final Map<Origin, URI> startupProxies;
    private final Map<String, URI> customProxies = new LinkedHashMap<>();
    private final Path file;
    private final ObjectMapper mapper;

    public PersonalModelProxySettings(
            List<PersonalAssistantProperties.ModelProvider> configured, Path dataDirectory, ObjectMapper mapper) {
        this(
                configured,
                Objects.requireNonNull(dataDirectory, "dataDirectory must not be null")
                        .toAbsolutePath()
                        .normalize()
                        .resolve("model-proxy-settings.json"),
                mapper,
                true);
    }

    private PersonalModelProxySettings(
            List<PersonalAssistantProperties.ModelProvider> configured,
            Path file,
            ObjectMapper mapper,
            boolean persisted) {
        this.providers = configuredProviders(configured);
        this.providerIdsByOrigin = providerIdsByOrigin(this.providers.values());
        this.startupProxies = startupProxies(this.providers.values());
        this.file = Objects.requireNonNull(file, "file must not be null")
                .toAbsolutePath()
                .normalize();
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        load();
    }

    public synchronized String mode(String providerId) {
        Set<String> connected = connectedProviderIds(provider(providerId).id());
        if (connected.stream().anyMatch(customProxies::containsKey)) return "CUSTOM";
        return endpointOrigins(provider(providerId)).stream().anyMatch(startupProxies::containsKey)
                ? "STARTUP"
                : "SYSTEM";
    }

    public synchronized void saveCustom(String providerId, URI proxy) {
        PersonalAssistantProperties.ModelProvider selected = provider(providerId);
        requireRemote(selected);
        URI checked = validate(proxy);
        connectedProviderIds(selected.id()).forEach(id -> customProxies.put(id, checked));
        persist();
    }

    public synchronized void resetToSystem(String providerId) {
        PersonalAssistantProperties.ModelProvider selected = provider(providerId);
        requireRemote(selected);
        connectedProviderIds(selected.id()).forEach(customProxies::remove);
        persist();
    }

    synchronized Optional<URI> proxyFor(URI endpoint) {
        Origin origin = Origin.from(endpoint);
        Set<String> providerIds = providerIdsByOrigin.get(origin);
        if (providerIds == null || providerIds.isEmpty()) return Optional.empty();
        URI custom = providerIds.stream()
                .map(customProxies::get)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        return Optional.ofNullable(custom == null ? startupProxies.get(origin) : custom);
    }

    /** Uses one provider's effective route for OAuth/token endpoints outside the model API origin. */
    public ProxySelector providerSelector(String providerId, ProxySelector fallback) {
        provider(providerId);
        return new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                return proxyForProvider(providerId)
                        .map(value -> List.of(new Proxy(
                                Proxy.Type.HTTP, InetSocketAddress.createUnresolved(value.getHost(), value.getPort()))))
                        .orElseGet(() -> {
                            if (fallback == null || fallback == this) return List.of(Proxy.NO_PROXY);
                            List<Proxy> selected = fallback.select(uri);
                            return selected == null || selected.isEmpty()
                                    ? List.of(Proxy.NO_PROXY)
                                    : List.copyOf(selected);
                        });
            }

            @Override
            public void connectFailed(URI uri, SocketAddress address, IOException failure) {
                if (proxyForProvider(providerId).isEmpty() && fallback != null && fallback != this) {
                    fallback.connectFailed(uri, address, failure);
                }
            }
        };
    }

    private synchronized Optional<URI> proxyForProvider(String providerId) {
        Set<String> connected = connectedProviderIds(provider(providerId).id());
        URI custom = connected.stream()
                .map(customProxies::get)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        if (custom != null) return Optional.of(custom);
        return endpointOrigins(provider(providerId)).stream()
                .map(startupProxies::get)
                .filter(Objects::nonNull)
                .findFirst();
    }

    private PersonalAssistantProperties.ModelProvider provider(String providerId) {
        PersonalAssistantProperties.ModelProvider value =
                providers.get(Objects.requireNonNull(providerId, "providerId must not be null"));
        if (value == null) throw new IllegalArgumentException("MODEL_PROXY_PROVIDER_UNAVAILABLE");
        return value;
    }

    private Set<String> connectedProviderIds(String providerId) {
        Set<String> visited = new LinkedHashSet<>();
        List<String> pending = new ArrayList<>();
        pending.add(providerId);
        while (!pending.isEmpty()) {
            String next = pending.removeFirst();
            if (!visited.add(next)) continue;
            endpointOrigins(provider(next))
                    .forEach(origin ->
                            providerIdsByOrigin.getOrDefault(origin, Set.of()).forEach(pending::add));
        }
        return visited;
    }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            if (Files.size(file) > MAX_FILE_BYTES)
                throw new IllegalStateException("Personal model proxy settings are invalid");
            JsonNode root = mapper.readTree(Files.readAllBytes(file));
            if (!root.isObject() || root.path("version").asInt(-1) != SCHEMA_VERSION || root.size() != 2) {
                throw new IllegalStateException("Personal model proxy settings are invalid");
            }
            JsonNode values = root.get("customProxies");
            if (values == null || !values.isObject())
                throw new IllegalStateException("Personal model proxy settings are invalid");
            values.fields().forEachRemaining(entry -> {
                if (!providers.containsKey(entry.getKey()) || !entry.getValue().isTextual()) {
                    throw new IllegalStateException("Personal model proxy settings are invalid");
                }
                customProxies.put(
                        entry.getKey(), validate(URI.create(entry.getValue().textValue())));
            });
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("Personal model proxy settings are invalid", exception);
        }
    }

    private void persist() {
        try {
            Files.createDirectories(file.getParent());
            ObjectNode root = mapper.createObjectNode();
            root.put("version", SCHEMA_VERSION);
            ObjectNode values = root.putObject("customProxies");
            customProxies.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(
                            entry -> values.put(entry.getKey(), entry.getValue().toString()));
            byte[] encoded = mapper.writeValueAsBytes(root);
            if (encoded.length > MAX_FILE_BYTES)
                throw new IllegalStateException("Personal model proxy settings are invalid");
            Path temporary = Files.createTempFile(file.getParent(), ".model-proxy-", ".tmp");
            boolean moved = false;
            try {
                Files.write(temporary, encoded);
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                moved = true;
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IllegalStateException("Personal model proxy settings require atomic replacement", exception);
            } finally {
                if (!moved) Files.deleteIfExists(temporary);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("PERSONAL_MODEL_PROXY_STORE_FAILED", exception);
        }
    }

    private static Map<String, PersonalAssistantProperties.ModelProvider> configuredProviders(
            List<PersonalAssistantProperties.ModelProvider> configured) {
        Map<String, PersonalAssistantProperties.ModelProvider> result = new LinkedHashMap<>();
        Objects.requireNonNull(configured, "configured must not be null")
                .forEach(provider -> result.put(provider.id(), provider));
        return Map.copyOf(result);
    }

    private static Map<Origin, Set<String>> providerIdsByOrigin(
            java.util.Collection<PersonalAssistantProperties.ModelProvider> configured) {
        Map<Origin, Set<String>> result = new LinkedHashMap<>();
        configured.forEach(provider -> endpointOrigins(provider)
                .forEach(origin -> result.computeIfAbsent(origin, ignored -> new LinkedHashSet<>())
                        .add(provider.id())));
        Map<Origin, Set<String>> immutable = new LinkedHashMap<>();
        result.forEach((origin, providerIds) -> immutable.put(origin, Set.copyOf(providerIds)));
        return Map.copyOf(immutable);
    }

    private static Map<Origin, URI> startupProxies(
            java.util.Collection<PersonalAssistantProperties.ModelProvider> configured) {
        Map<Origin, URI> result = new LinkedHashMap<>();
        configured.forEach(provider -> endpointOrigins(provider).forEach(origin -> {
            URI proxy = provider.proxy();
            if (result.containsKey(origin) && !Objects.equals(result.get(origin), proxy)) {
                throw new IllegalArgumentException(
                        "model providers sharing an endpoint origin must use the same proxy configuration");
            }
            result.put(origin, proxy);
        }));
        result.values().removeIf(Objects::isNull);
        return Map.copyOf(result);
    }

    private static Set<Origin> endpointOrigins(PersonalAssistantProperties.ModelProvider provider) {
        Set<Origin> result = new LinkedHashSet<>();
        result.add(Origin.from(provider.endpoint()));
        provider.apiBindings()
                .forEach(binding ->
                        result.add(Origin.from(binding.endpoint() == null ? provider.endpoint() : binding.endpoint())));
        return Set.copyOf(result);
    }

    private static void requireRemote(PersonalAssistantProperties.ModelProvider provider) {
        if (!"remote".equals(provider.mode())) throw new IllegalArgumentException("MODEL_PROXY_PROVIDER_UNAVAILABLE");
    }

    private static URI validate(URI proxy) {
        if (proxy == null
                || !proxy.isAbsolute()
                || !"http".equalsIgnoreCase(proxy.getScheme())
                || proxy.getHost() == null
                || proxy.getPort() < 1
                || proxy.getPort() > 65_535
                || proxy.getRawUserInfo() != null
                || proxy.getRawQuery() != null
                || proxy.getRawFragment() != null
                || (proxy.getRawPath() != null && !proxy.getRawPath().isEmpty() && !"/".equals(proxy.getRawPath()))) {
            throw new IllegalArgumentException("MODEL_PROXY_INVALID");
        }
        return URI.create("http://" + proxy.getHost() + ":" + proxy.getPort());
    }

    private record Origin(String scheme, String host, int port) {
        private static Origin from(URI uri) {
            String scheme = Objects.requireNonNull(uri.getScheme(), "endpoint scheme must not be null")
                    .toLowerCase(java.util.Locale.ROOT);
            String host = Objects.requireNonNull(uri.getHost(), "endpoint host must not be null")
                    .toLowerCase(java.util.Locale.ROOT);
            int port =
                    uri.getPort() >= 0 ? uri.getPort() : "https".equals(scheme) ? 443 : "http".equals(scheme) ? 80 : -1;
            return new Origin(scheme, host, port);
        }
    }
}
