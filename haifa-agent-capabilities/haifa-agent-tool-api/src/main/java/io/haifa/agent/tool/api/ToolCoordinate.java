package io.haifa.agent.tool.api;

import java.util.Objects;

public record ToolCoordinate(
        ToolName name, SemanticVersion version, ToolProviderId providerId, ToolDefinitionHash definitionHash)
        implements Comparable<ToolCoordinate> {
    public ToolCoordinate {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(providerId, "providerId");
        Objects.requireNonNull(definitionHash, "definitionHash");
    }

    public String externalForm() {
        return name.value() + "@" + version.value() + "#" + providerId.value() + "#" + definitionHash.value();
    }

    @Override
    public int compareTo(ToolCoordinate other) {
        return externalForm().compareTo(other.externalForm());
    }
}
