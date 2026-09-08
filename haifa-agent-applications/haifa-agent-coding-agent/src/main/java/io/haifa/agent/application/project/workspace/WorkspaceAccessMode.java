package io.haifa.agent.application.project.workspace;

import java.util.Objects;

/** CA-owned durable workspace access modes. */
public enum WorkspaceAccessMode {
    READ,
    DEVELOP;

    public boolean allows(WorkspaceAccessMode required) {
        Objects.requireNonNull(required, "required must not be null");
        return this == DEVELOP || required == READ;
    }
}
