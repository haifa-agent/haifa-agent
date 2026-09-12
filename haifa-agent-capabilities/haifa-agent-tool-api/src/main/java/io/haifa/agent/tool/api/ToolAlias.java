package io.haifa.agent.tool.api;

public record ToolAlias(String value) implements Comparable<ToolAlias> {
    public ToolAlias {
        value = new ToolName(value).value();
    }

    @Override
    public int compareTo(ToolAlias other) {
        return value.compareTo(other.value);
    }
}
