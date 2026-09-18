package io.haifa.agent.project.domain;

import java.util.Objects;

public record ProjectConfigurationRef(String configurationId, String version) {
    public ProjectConfigurationRef {
        configurationId = requireText(configurationId, "configurationId");
        version = requireText(version, "version");
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
