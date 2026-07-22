package io.haifa.agent.tool.api;

import java.util.Comparator;
import java.util.List;

public record ToolCatalogSnapshot(String digest, List<FrozenToolBinding> bindings) {
    public ToolCatalogSnapshot {
        digest = ToolValues.text(digest, "digest");
        bindings = List.copyOf(bindings).stream()
                .sorted(Comparator.comparing(FrozenToolBinding::alias))
                .toList();
    }
}
