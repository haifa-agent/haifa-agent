package io.haifa.agent.mcp.tool;

import io.haifa.agent.tool.api.ToolAlias;
import io.haifa.agent.tool.api.ToolDefinition;
import io.haifa.agent.tool.api.ToolProvider;
import java.util.Objects;

public record McpToolCatalogContribution(
        ToolAlias alias, ToolDefinition definition, String providerBindingReference, ToolProvider provider) {
    public McpToolCatalogContribution {
        Objects.requireNonNull(alias, "alias");
        Objects.requireNonNull(definition, "definition");
        if (providerBindingReference == null || providerBindingReference.isBlank()) {
            throw new IllegalArgumentException("providerBindingReference must not be blank");
        }
        Objects.requireNonNull(provider, "provider");
        if (!provider.id().equals(definition.providerId())) {
            throw new IllegalArgumentException("MCP provider does not match imported definition");
        }
    }

    public static McpToolCatalogContribution from(McpToolImportCandidate candidate, ToolProvider provider) {
        if (!candidate.enabled()) throw new IllegalArgumentException("disabled MCP candidate cannot enter a catalog");
        return new McpToolCatalogContribution(
                candidate.alias().orElseThrow(),
                candidate.definition().orElseThrow(),
                candidate.binding().orElseThrow().bindingReference(),
                provider);
    }
}
