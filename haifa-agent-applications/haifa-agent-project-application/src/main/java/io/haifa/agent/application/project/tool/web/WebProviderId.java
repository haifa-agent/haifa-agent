package io.haifa.agent.application.project.tool.web;

public record WebProviderId(String value) implements Comparable<WebProviderId> {
    public WebProviderId {
        value = WebValues.text(value, "web provider id", 64).toLowerCase(java.util.Locale.ROOT);
        if (!value.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("invalid web provider id");
        }
    }

    @Override
    public int compareTo(WebProviderId other) {
        return value.compareTo(other.value);
    }
}
