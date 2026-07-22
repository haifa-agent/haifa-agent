package io.haifa.agent.tool.api;

import java.util.Objects;

public record FrozenToolBinding(
        ToolAlias alias,
        ToolCoordinate coordinate,
        ToolDefinition definition,
        String providerBindingReference,
        String catalogDigest) {
    public FrozenToolBinding {
        Objects.requireNonNull(alias, "alias");
        Objects.requireNonNull(coordinate, "coordinate");
        Objects.requireNonNull(definition, "definition");
        providerBindingReference = ToolValues.text(providerBindingReference, "providerBindingReference");
        catalogDigest = ToolValues.text(catalogDigest, "catalogDigest");
        if (!coordinate.name().equals(definition.name())
                || !coordinate.version().equals(definition.version())
                || !coordinate.providerId().equals(definition.providerId())) {
            throw new IllegalArgumentException("coordinate does not identify definition");
        }
    }
}
