package io.haifa.agent.project.root;

/**
 * Authorization level granted to a workspace root within a session.
 */
public enum WorkspaceRootPermission {
    READ_ONLY,
    READ_WRITE;

    public boolean canRead() {
        return true;
    }

    public boolean canWrite() {
        return this == READ_WRITE;
    }
}
