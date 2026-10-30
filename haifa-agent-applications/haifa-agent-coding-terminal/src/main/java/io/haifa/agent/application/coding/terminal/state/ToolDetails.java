package io.haifa.agent.application.coding.terminal.state;

import java.util.Objects;

/** Ephemeral Terminal projection, built from typed lifecycle facts rather than parsing tool output. */
public record ToolDetails(String content, String technical, boolean expanded) {
    public ToolDetails {
        content = Objects.requireNonNull(content, "content must not be null");
        technical = Objects.requireNonNull(technical, "technical must not be null");
    }

    public ToolDetails toggle() {
        return new ToolDetails(content, technical, !expanded);
    }

    public ToolDetails withContent(String value) {
        return new ToolDetails(value, technical, expanded);
    }

    @Override
    public String toString() {
        return "ToolDetails[content=[REDACTED], technical=[REDACTED], expanded=" + expanded + "]";
    }
}
