package io.haifa.agent.tool.api;

public interface ToolResolver {
    FrozenToolBinding resolve(ToolCatalogSnapshot snapshot, ToolAlias alias);
}
