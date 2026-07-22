package io.haifa.agent.tool.api;

public record ToolDefinitionHash(String value) implements Comparable<ToolDefinitionHash> {
    public ToolDefinitionHash {
        value = ToolValues.text(value, "value").toLowerCase(java.util.Locale.ROOT);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("tool definition hash must be 64 lowercase hexadecimal characters");
        }
    }

    @Override
    public int compareTo(ToolDefinitionHash other) {
        return value.compareTo(other.value);
    }
}
