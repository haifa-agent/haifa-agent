package io.haifa.agent.project.changeset;

import io.haifa.agent.common.id.Identifier;
import java.util.Objects;

public record FileChangeSetId(String value) implements Identifier {
    public FileChangeSetId {
        value = Objects.requireNonNull(value, "value must not be null").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("value must not be blank");
    }
}
