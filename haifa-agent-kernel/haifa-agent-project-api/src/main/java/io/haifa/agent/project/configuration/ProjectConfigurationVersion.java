package io.haifa.agent.project.configuration;

import java.util.Objects;

public record ProjectConfigurationVersion(String value) {
    public ProjectConfigurationVersion {
        value = Objects.requireNonNull(value, "value must not be null").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("value must not be blank");
    }
}
