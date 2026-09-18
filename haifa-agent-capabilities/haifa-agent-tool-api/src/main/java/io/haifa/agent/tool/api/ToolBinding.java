package io.haifa.agent.tool.api;

import java.util.Objects;

public record ToolBinding(ToolAlias alias, ToolCoordinate coordinate, String providerBindingReference) {
    public ToolBinding {
        Objects.requireNonNull(alias, "alias");
        Objects.requireNonNull(coordinate, "coordinate");
        providerBindingReference = ToolValues.text(providerBindingReference, "providerBindingReference");
        if (!alias.value().equals(coordinate.name().value())) {
            throw new IllegalArgumentException("tool alias must equal tool name");
        }
    }
}
