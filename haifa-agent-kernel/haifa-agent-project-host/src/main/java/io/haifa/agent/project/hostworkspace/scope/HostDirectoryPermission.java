package io.haifa.agent.project.hostworkspace.scope;

/**
 * Write permission granted for one authorized local directory. All peer directories can be read;
 * only {@link #READ_WRITE} directories accept mutations.
 */
public enum HostDirectoryPermission {
    READ_ONLY,
    READ_WRITE;

    public boolean canRead() {
        return true;
    }

    public boolean canWrite() {
        return this == READ_WRITE;
    }
}
