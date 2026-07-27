package io.haifa.agent.application.project.tool.web;

import io.haifa.agent.tool.api.ToolAlias;
import io.haifa.agent.tool.api.ToolDefinition;
import io.haifa.agent.tool.api.ToolProvider;
import java.util.Objects;

public record WebToolCatalogContribution(
        ToolAlias alias, ToolDefinition definition, String providerBindingReference, ToolProvider provider) {
    public WebToolCatalogContribution {
        Objects.requireNonNull(alias, "alias");
        Objects.requireNonNull(definition, "definition");
        if (providerBindingReference == null || providerBindingReference.isBlank()) {
            throw new IllegalArgumentException("providerBindingReference must not be blank");
        }
        Objects.requireNonNull(provider, "provider");
    }
}
