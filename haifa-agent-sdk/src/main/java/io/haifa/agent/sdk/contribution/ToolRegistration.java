package io.haifa.agent.sdk.contribution;

import io.haifa.agent.tool.api.ToolAlias;
import io.haifa.agent.tool.api.ToolDefinition;
import io.haifa.agent.tool.api.ToolProvider;
import java.util.Objects;

/**
 * One already-reviewed Tool registration handed to the SDK by an Integration such as MCP.
 *
 * <p>This is a narrow value record, not an extension point: it carries exactly what the Tool
 * platform needs to register one Tool on the single {@code ToolCatalogBuilder} the SDK freezes.
 * Registrations never arrive as an already frozen catalog, so Java Tools and Integration Tools share
 * one freeze and one collision check instead of being merged afterwards.
 */
public record ToolRegistration(
        ToolAlias alias, ToolDefinition definition, String providerBindingReference, ToolProvider provider) {
    public ToolRegistration {
        alias = Objects.requireNonNull(alias, "alias must not be null");
        definition = Objects.requireNonNull(definition, "definition must not be null");
        providerBindingReference = Objects.requireNonNull(
                        providerBindingReference, "providerBindingReference must not be null")
                .trim();
        if (providerBindingReference.isEmpty()) {
            throw new IllegalArgumentException("providerBindingReference must not be blank");
        }
        provider = Objects.requireNonNull(provider, "provider must not be null");
        if (!provider.id().equals(definition.providerId())) {
            throw new IllegalArgumentException("Tool provider does not match its Tool definition");
        }
        if (!alias.value().equals(definition.name().value())) {
            throw new IllegalArgumentException("Tool alias must equal its Tool name");
        }
    }
}
