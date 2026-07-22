package io.haifa.agent.tool.api;

public record ToolName(String value) implements Comparable<ToolName> {
    public ToolName {
        value = ToolValues.text(value, "value");
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("invalid tool name");
        }
    }

    @Override
    public int compareTo(ToolName other) {
        return value.compareTo(other.value);
    }
}
