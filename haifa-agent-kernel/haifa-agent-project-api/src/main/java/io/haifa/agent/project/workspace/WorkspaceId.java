package io.haifa.agent.project.workspace;

import io.haifa.agent.common.id.Identifier;
import io.haifa.agent.core.reference.WorkspaceRef;
import java.util.Objects;

public record WorkspaceId(String value) implements Identifier {
    public WorkspaceId {
        value = requireText(value);
    }

    public WorkspaceRef reference() {
        return new WorkspaceRef(value);
    }

    private static String requireText(String value) {
        String normalized =
                Objects.requireNonNull(value, "value must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("value must not be blank");
        return normalized;
    }
}
