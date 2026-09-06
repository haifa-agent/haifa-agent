package io.haifa.agent.project.snapshot;

import io.haifa.agent.common.id.Identifier;
import java.util.Objects;

public record WorkspaceSnapshotId(String value) implements Identifier {
    public WorkspaceSnapshotId {
        value = Objects.requireNonNull(value, "value must not be null").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("value must not be blank");
    }
}
