package io.haifa.agent.tool.core;

import io.haifa.agent.tool.api.FrozenToolBinding;
import io.haifa.agent.tool.api.ToolAlias;
import io.haifa.agent.tool.api.ToolCatalogSnapshot;
import io.haifa.agent.tool.api.ToolResolver;

public final class DefaultToolResolver implements ToolResolver {
    @Override
    public FrozenToolBinding resolve(ToolCatalogSnapshot snapshot, ToolAlias alias) {
        return snapshot.bindings().stream()
                .filter(binding -> binding.alias().equals(alias))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("tool alias is not present in frozen catalog"));
    }
}
