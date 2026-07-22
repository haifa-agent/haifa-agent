package io.haifa.agent.tool.api;

public record ToolAlias(String value) implements Comparable<ToolAlias> {
    public ToolAlias {
        value = ToolValues.text(value, "value");
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("invalid tool alias");
        }
    }

    @Override
    public int compareTo(ToolAlias other) {
        return value.compareTo(other.value);
    }
}
