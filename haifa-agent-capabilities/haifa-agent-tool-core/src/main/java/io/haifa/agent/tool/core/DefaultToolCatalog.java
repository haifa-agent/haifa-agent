package io.haifa.agent.tool.core;

import io.haifa.agent.tool.api.FrozenToolBinding;
import io.haifa.agent.tool.api.ToolAlias;
import io.haifa.agent.tool.api.ToolCatalog;
import io.haifa.agent.tool.api.ToolCatalogSnapshot;
import io.haifa.agent.tool.api.ToolCoordinate;
import io.haifa.agent.tool.api.ToolProvider;
import io.haifa.agent.tool.api.ToolProviderId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class DefaultToolCatalog implements ToolCatalog {
    private final ToolCatalogSnapshot snapshot;
    private final Map<ToolAlias, FrozenToolBinding> byAlias;
    private final Map<ToolCoordinate, FrozenToolBinding> byCoordinate;
    private final Map<ToolProviderId, ToolProvider> providers;

    DefaultToolCatalog(String digest, List<FrozenToolBinding> bindings, Map<ToolProviderId, ToolProvider> providers) {
        snapshot = new ToolCatalogSnapshot(digest, bindings);
        byAlias =
                bindings.stream().collect(Collectors.toUnmodifiableMap(FrozenToolBinding::alias, Function.identity()));
        byCoordinate = bindings.stream()
                .collect(Collectors.toUnmodifiableMap(FrozenToolBinding::coordinate, Function.identity()));
        this.providers = Map.copyOf(providers);
    }

    @Override
    public ToolCatalogSnapshot snapshot() {
        return snapshot;
    }

    @Override
    public Optional<FrozenToolBinding> findByAlias(ToolAlias alias) {
        return Optional.ofNullable(byAlias.get(alias));
    }

    @Override
    public Optional<FrozenToolBinding> findByCoordinate(ToolCoordinate coordinate) {
        return Optional.ofNullable(byCoordinate.get(coordinate));
    }

    ToolProvider provider(ToolProviderId providerId) {
        return Optional.ofNullable(providers.get(providerId))
                .orElseThrow(() -> new IllegalStateException("frozen tool provider is unavailable"));
    }
}
