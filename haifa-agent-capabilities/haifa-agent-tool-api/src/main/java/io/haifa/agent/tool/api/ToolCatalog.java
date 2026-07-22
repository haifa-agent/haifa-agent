package io.haifa.agent.tool.api;

import java.util.Optional;

public interface ToolCatalog {
    ToolCatalogSnapshot snapshot();

    Optional<FrozenToolBinding> findByAlias(ToolAlias alias);

    Optional<FrozenToolBinding> findByCoordinate(ToolCoordinate coordinate);

    static ToolCatalog empty() {
        return new ToolCatalog() {
            private final ToolCatalogSnapshot snapshot = new ToolCatalogSnapshot(
                    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", java.util.List.of());

            @Override
            public ToolCatalogSnapshot snapshot() {
                return snapshot;
            }

            @Override
            public Optional<FrozenToolBinding> findByAlias(ToolAlias alias) {
                return Optional.empty();
            }

            @Override
            public Optional<FrozenToolBinding> findByCoordinate(ToolCoordinate coordinate) {
                return Optional.empty();
            }
        };
    }
}
