package io.haifa.agent.project.mutation;

import io.haifa.agent.project.workspace.WorkspaceId;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryWorkspaceWriteLeaseManager implements WorkspaceWriteLeaseManager {
    private final ConcurrentHashMap<WorkspaceId, String> holders = new ConcurrentHashMap<>();

    @Override
    public WorkspaceWriteLease acquire(WorkspaceId workspaceId, String operationId) {
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        Objects.requireNonNull(operationId, "operationId must not be null");
        if (holders.putIfAbsent(workspaceId, operationId) != null) {
            throw new WorkspaceMutationException(
                    MutationErrorCode.WRITE_LEASE_UNAVAILABLE,
                    io.haifa.agent.project.path.WorkspacePath.root(workspaceId),
                    "workspace already has a controlled writer");
        }
        return new Lease(workspaceId, operationId);
    }

    private final class Lease implements WorkspaceWriteLease {
        private final WorkspaceId workspaceId;
        private final String operationId;
        private boolean closed;

        private Lease(WorkspaceId workspaceId, String operationId) {
            this.workspaceId = workspaceId;
            this.operationId = operationId;
        }

        @Override
        public WorkspaceId workspaceId() {
            return workspaceId;
        }

        @Override
        public String operationId() {
            return operationId;
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            holders.remove(workspaceId, operationId);
            closed = true;
        }
    }
}
