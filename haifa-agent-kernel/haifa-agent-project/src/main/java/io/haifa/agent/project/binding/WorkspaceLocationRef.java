package io.haifa.agent.project.binding;

import java.util.Objects;

public record WorkspaceLocationRef(String value) {
    public WorkspaceLocationRef {
        value = Objects.requireNonNull(value, "value must not be null").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("value must not be blank");
    }

    @Override
    public String toString() {
        return "WorkspaceLocationRef[redacted]";
    }
}
