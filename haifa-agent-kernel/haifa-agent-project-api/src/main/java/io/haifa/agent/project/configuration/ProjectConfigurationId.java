package io.haifa.agent.project.configuration;

import io.haifa.agent.common.id.Identifier;
import java.util.Objects;

public record ProjectConfigurationId(String value) implements Identifier {
    public ProjectConfigurationId {
        value = Objects.requireNonNull(value, "value must not be null").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("value must not be blank");
    }
}
