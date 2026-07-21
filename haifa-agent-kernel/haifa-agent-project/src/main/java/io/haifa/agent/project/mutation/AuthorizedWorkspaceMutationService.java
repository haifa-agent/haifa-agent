package io.haifa.agent.project.mutation;

import io.haifa.agent.project.binding.WorkspaceBinding;
import io.haifa.agent.project.binding.WorkspaceBindingMode;
import io.haifa.agent.project.binding.WorkspaceBindingStatus;
import io.haifa.agent.project.path.WorkspacePath;
import io.haifa.agent.project.store.WorkspaceBindingStore;
import io.haifa.agent.project.store.WorkspaceStore;
import io.haifa.agent.project.workspace.Workspace;
import io.haifa.agent.project.workspace.WorkspacePermission;
import io.haifa.agent.project.workspace.WorkspaceStatus;
import java.util.Objects;

public final class AuthorizedWorkspaceMutationService implements WorkspaceMutationService {
    private final WorkspaceStore workspaces;
    private final WorkspaceBindingStore bindings;
    private final WorkspaceMutationService provider;

    public AuthorizedWorkspaceMutationService(
            WorkspaceStore workspaces, WorkspaceBindingStore bindings, WorkspaceMutationService provider) {
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces must not be null");
        this.bindings = Objects.requireNonNull(bindings, "bindings must not be null");
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
    }

    @Override
    public MutationResult create(CreateFileRequest request) {
        authorize(request.path(), WorkspacePermission.WRITE);
        return provider.create(request);
    }

    @Override
    public MutationResult write(WriteFileRequest request) {
        authorize(request.path(), WorkspacePermission.WRITE);
        return provider.write(request);
    }

    @Override
    public MutationResult delete(DeleteFileRequest request) {
        authorize(request.path(), WorkspacePermission.DELETE);
        return provider.delete(request);
    }

    @Override
    public MutationResult move(MoveFileRequest request) {
        authorize(request.source(), WorkspacePermission.WRITE);
        authorize(request.destination(), WorkspacePermission.WRITE);
        return provider.move(request);
    }

    private void authorize(WorkspacePath path, WorkspacePermission permission) {
        Workspace workspace = workspaces
                .find(path.workspaceId())
                .orElseThrow(() -> failure(MutationErrorCode.WORKSPACE_NOT_FOUND, path, "workspace not found"));
        if (workspace.status() != WorkspaceStatus.ACTIVE) {
            throw failure(MutationErrorCode.WORKSPACE_INACTIVE, path, "workspace is not active");
        }
        WorkspaceBinding binding = bindings.find(workspace.root().bindingId())
                .orElseThrow(() -> failure(MutationErrorCode.BINDING_INACTIVE, path, "workspace binding not found"));
        if (binding.status() != WorkspaceBindingStatus.ACTIVE) {
            throw failure(MutationErrorCode.BINDING_INACTIVE, path, "workspace binding is not active");
        }
        if (binding.mode() == WorkspaceBindingMode.READ_ONLY) {
            throw failure(MutationErrorCode.READ_ONLY, path, "read-only workspace rejects mutations");
        }
        if (!binding.permissions().allows(permission)) {
            throw failure(MutationErrorCode.PERMISSION_DENIED, path, "workspace mutation permission denied");
        }
        String capability = permission == WorkspacePermission.DELETE ? "files.delete" : "files.write";
        if (!binding.capabilities().allows(capability)) {
            throw failure(MutationErrorCode.PERMISSION_DENIED, path, "workspace mutation capability denied");
        }
    }

    private static WorkspaceMutationException failure(MutationErrorCode code, WorkspacePath path, String message) {
        return new WorkspaceMutationException(code, path, message);
    }
}
