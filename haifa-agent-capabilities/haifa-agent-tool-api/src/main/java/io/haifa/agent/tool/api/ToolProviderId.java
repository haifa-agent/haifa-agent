package io.haifa.agent.tool.api;

public record ToolProviderId(String value) implements Comparable<ToolProviderId> {
    public ToolProviderId {
        value = ToolValues.text(value, "value");
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("invalid tool provider id");
        }
    }

    @Override
    public int compareTo(ToolProviderId other) {
        return value.compareTo(other.value);
    }
}
