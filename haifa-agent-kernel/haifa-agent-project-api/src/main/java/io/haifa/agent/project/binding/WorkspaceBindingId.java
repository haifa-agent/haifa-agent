package io.haifa.agent.project.binding;

import io.haifa.agent.common.id.Identifier;
import java.util.Objects;

public record WorkspaceBindingId(String value) implements Identifier {
    public WorkspaceBindingId {
        value = Objects.requireNonNull(value, "value must not be null").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("value must not be blank");
    }
}
