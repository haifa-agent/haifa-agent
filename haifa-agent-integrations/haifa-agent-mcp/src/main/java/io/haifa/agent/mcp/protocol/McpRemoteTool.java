package io.haifa.agent.mcp.protocol;

import java.util.Map;
import java.util.Objects;

public record McpRemoteTool(
        String name,
        String title,
        String description,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema,
        Map<String, Object> annotations,
        Map<String, Object> metadata) {
    public McpRemoteTool {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("remote tool name must not be blank");
        title = title == null || title.isBlank() ? name : title;
        description = description == null || description.isBlank() ? name : description;
        inputSchema = Map.copyOf(Objects.requireNonNull(inputSchema, "inputSchema"));
        outputSchema = outputSchema == null ? Map.of() : Map.copyOf(outputSchema);
        annotations = annotations == null ? Map.of() : Map.copyOf(annotations);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public String remoteDefinitionDigest() {
        return McpCanonicalizer.digest(Map.of(
                "name", name,
                "title", title,
                "description", description,
                "inputSchema", inputSchema,
                "outputSchema", outputSchema,
                "annotations", annotations,
                "metadata", metadata));
    }
}
