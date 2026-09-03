package io.haifa.agent.project.provider.local.scope;

import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.project.binding.WorkspaceBinding;
import io.haifa.agent.project.binding.WorkspaceBindingMode;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.provider.local.LocalWorkspaceLocationStore;
import io.haifa.agent.project.provider.local.LocalWorkspacePathSafety;
import io.haifa.agent.project.store.WorkspaceBindingStore;
import io.haifa.agent.project.store.WorkspaceStore;
import io.haifa.agent.project.workspace.Workspace;
import io.haifa.agent.project.workspace.WorkspaceCapabilitySet;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.project.workspace.WorkspacePermissionSet;
import io.haifa.agent.project.workspace.WorkspaceService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Trusted local product boundary that turns an approved directory into a peer member of the
 * {@link LocalWorkspaceScope}. It must only be invoked after the user has approved the directory:
 * it resolves the real path, applies the overlap policy, provisions or recovers the logical
 * workspace and binding, and atomically swaps the scope snapshot. Revocation removes the physical
 * authorization immediately; it never deletes user files, the ledger, or the logical workspace and
 * binding audit facts.
 */
public final class AuthorizedDirectoryProvisioning {
    private static final String SEMANTICS_ID = "local-authorized-directory";

    private final WorkspaceStore workspaces;
    private final WorkspaceBindingStore bindings;
    private final LocalWorkspaceLocationStore locations;
    private final WorkspaceService workspaceService;
    private final ProjectId projectId;
    private final PrincipalRef owner;
    private final TimeProvider time;
    private final AtomicReference<LocalWorkspaceScope> scope;

    public AuthorizedDirectoryProvisioning(
            ProjectId projectId,
            WorkspaceStore workspaces,
            WorkspaceBindingStore bindings,
            LocalWorkspaceLocationStore locations,
            WorkspaceService workspaceService,
            PrincipalRef owner,
            TimeProvider time,
            LocalWorkspaceScope initialScope) {
        this.projectId = Objects.requireNonNull(projectId, "projectId must not be null");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces must not be null");
        this.bindings = Objects.requireNonNull(bindings, "bindings must not be null");
        this.locations = Objects.requireNonNull(locations, "locations must not be null");
        this.workspaceService = Objects.requireNonNull(workspaceService, "workspaceService must not be null");
        this.owner = Objects.requireNonNull(owner, "owner must not be null");
        this.time = Objects.requireNonNull(time, "time must not be null");
        Objects.requireNonNull(initialScope, "initialScope must not be null");
        this.scope = new AtomicReference<>(initialScope);
    }

    /** Current scope snapshot. Callers must re-validate the snapshot before physical write I/O. */
    public LocalWorkspaceScope scope() {
        return scope.get();
    }

    /** Fails closed when the scope has changed since the given snapshot was resolved. */
    public void requireUnchanged(LocalWorkspaceScope resolvedAgainst) {
        Objects.requireNonNull(resolvedAgainst, "resolvedAgainst must not be null");
        if (scope.get().version() != resolvedAgainst.version()) {
            throw LocalWorkspaceScopeException.accessDenied(
                    null, "Directory authorization changed while the operation was in flight");
        }
    }

    /**
     * Authorizes one user-approved directory. If the directory is already covered by an existing
     * authorized boundary, the existing entry is reused with its current permission. If it would
     * swallow an existing boundary, the request is rejected fail closed.
     */
    public ProvisioningResult authorize(Path directory, LocalDirectoryPermission permission) {
        Objects.requireNonNull(directory, "directory must not be null");
        Objects.requireNonNull(permission, "permission must not be null");
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw LocalWorkspaceScopeException.invalidArgument(
                    directory.toString(), "Authorized directory must be an existing directory");
        }
        if (LocalWorkspacePathSafety.isUnsafeNode(directory)) {
            throw LocalWorkspaceScopeException.invalidArgument(
                    directory.toString(),
                    "Authorized directory must not be a symbolic link or reparse point: " + directory);
        }
        Path realPath;
        try {
            realPath = directory.toRealPath();
        } catch (IOException exception) {
            throw LocalWorkspaceScopeException.invalidArgument(
                    directory.toString(), "Authorized directory must exist and be accessible");
        }
        if (LocalWorkspacePathSafety.isUnsafeNode(realPath)) {
            throw LocalWorkspaceScopeException.invalidArgument(
                    realPath.toString(),
                    "Authorized directory must not be a symbolic link or reparse point: " + realPath);
        }

        LocalWorkspaceScope current = scope.get();
        for (LocalAllowedDirectory existing : current.allowedDirectories()) {
            if (existing.encloses(realPath)) {
                if (permission.canWrite() && !existing.permission().canWrite()) {
                    throw LocalWorkspaceScopeException.permissionDenied(
                            realPath.toString(),
                            "Directory is already authorized as read-only by an enclosing boundary: " + realPath);
                }
                return new ProvisioningResult(existing, true, false);
            }
            if (existing.realPath().startsWith(realPath)) {
                throw LocalWorkspaceScopeException.invalidArgument(
                        realPath.toString(),
                        "Directory overlaps an existing authorized boundary; choose a non-overlapping"
                                + " directory: "
                                + realPath);
            }
        }

        ProvisioningResult result = provisionDirectory(realPath, permission);
        LocalAllowedDirectory allowed = result.directory();
        while (true) {
            current = scope.get();
            LocalWorkspaceScope updated = current.withDirectory(allowed);
            if (scope.compareAndSet(current, updated)) {
                return result;
            }
        }
    }

    /** Revokes the physical authorization of one directory. Logical facts stay untouched. */
    public void revoke(WorkspaceId workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        while (true) {
            LocalWorkspaceScope before = scope.get();
            LocalWorkspaceScope updated = before.withoutDirectory(workspaceId);
            if (updated == before) {
                throw new IllegalArgumentException("Directory is not authorized in this scope");
            }
            if (scope.compareAndSet(before, updated)) {
                return;
            }
        }
    }

    private ProvisioningResult provisionDirectory(Path realPath, LocalDirectoryPermission permission) {
        LocalDirectoryIdentity identity = LocalDirectoryIdentity.resolve(realPath);
        boolean recovered = workspaces.find(identity.workspaceId()).isPresent();
        WorkspaceBindingMode mode =
                permission.canWrite() ? WorkspaceBindingMode.DIRECT : WorkspaceBindingMode.READ_ONLY;
        WorkspaceBinding binding = WorkspaceBinding.provision(
                        identity.bindingId(),
                        identity.locationRef(),
                        mode,
                        owner,
                        permission.canWrite()
                                ? WorkspaceCapabilitySet.readWriteFiles()
                                : WorkspaceCapabilitySet.readOnlyFiles(),
                        permission.canWrite() ? WorkspacePermissionSet.readWrite() : WorkspacePermissionSet.readOnly(),
                        identity.fingerprint(),
                        time.now())
                .activate(time.now());
        Workspace workspace =
                workspaceService.provisionDirectory(projectId, identity.workspaceId(), binding, SEMANTICS_ID);
        if (!locations.contains(identity.locationRef())) {
            locations.register(identity.locationRef(), realPath);
        }
        return new ProvisioningResult(LocalAllowedDirectory.of(workspace.id(), realPath, permission), false, recovered);
    }

    public record ProvisioningResult(
            LocalAllowedDirectory directory, boolean reusedExistingBoundary, boolean recovered) {
        public ProvisioningResult {
            Objects.requireNonNull(directory, "directory must not be null");
        }
    }
}
