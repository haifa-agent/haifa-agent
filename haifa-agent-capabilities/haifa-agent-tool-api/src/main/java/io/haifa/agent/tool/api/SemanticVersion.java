package io.haifa.agent.tool.api;

public record SemanticVersion(String value) implements Comparable<SemanticVersion> {
    private static final String IDENTIFIER = "(?:0|[1-9]\\d*|\\d*[A-Za-z-][0-9A-Za-z-]*)";
    private static final String PATTERN = "(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)"
            + "(?:-" + IDENTIFIER + "(?:\\." + IDENTIFIER + ")*)?"
            + "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?";

    public SemanticVersion {
        value = ToolValues.text(value, "value");
        if (!value.matches(PATTERN)) {
            throw new IllegalArgumentException("invalid semantic version");
        }
    }

    @Override
    public int compareTo(SemanticVersion other) {
        return value.compareTo(other.value);
    }
}
