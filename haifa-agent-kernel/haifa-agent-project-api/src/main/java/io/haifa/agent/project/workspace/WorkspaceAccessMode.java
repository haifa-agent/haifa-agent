package io.haifa.agent.project.workspace;

import java.util.Objects;

/** Host directory authorization mode shared by the Coding Agent product and the host adapters. */
public enum WorkspaceAccessMode {
    READ,
    DEVELOP;

    public boolean allows(WorkspaceAccessMode required) {
        Objects.requireNonNull(required, "required must not be null");
        return this == DEVELOP || required == READ;
    }
}
