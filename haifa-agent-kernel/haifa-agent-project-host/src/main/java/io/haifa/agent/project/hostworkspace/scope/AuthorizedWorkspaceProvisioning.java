package io.haifa.agent.project.hostworkspace.scope;

import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.project.binding.WorkspaceBinding;
import io.haifa.agent.project.binding.WorkspaceBindingMode;
import io.haifa.agent.project.core.workspace.WorkspaceService;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.hostworkspace.HostWorkspaceLocationStore;
import io.haifa.agent.project.hostworkspace.HostWorkspacePathSafety;
import io.haifa.agent.project.hostworkspace.registry.HostWorkspaceRegistryEntry;
import io.haifa.agent.project.hostworkspace.registry.HostWorkspaceRegistrySource;
import io.haifa.agent.project.hostworkspace.registry.HostWorkspaceRegistryStatus;
import io.haifa.agent.project.hostworkspace.registry.HostWorkspaceRegistryStore;
import io.haifa.agent.project.hostworkspace.registry.HostWorkspaceRegistryView;
import io.haifa.agent.project.hostworkspace.registry.InMemoryHostWorkspaceRegistryStore;
import io.haifa.agent.project.store.WorkspaceBindingStore;
import io.haifa.agent.project.store.WorkspaceStore;
import io.haifa.agent.project.workspace.Workspace;
import io.haifa.agent.project.workspace.WorkspaceCapabilitySet;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.project.workspace.WorkspacePermissionSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Trusted local product boundary that turns an approved directory into a peer member of the
 * {@link HostWorkspaceScope}. It must only be invoked after the user has approved the directory:
 * it resolves the real path, applies the overlap policy, provisions or recovers the logical
 * workspace and binding, and atomically swaps the scope snapshot. Revocation removes the physical
 * authorization immediately; it never deletes user files, the ledger, or the logical workspace and
 * binding audit facts.
 */
public final class AuthorizedWorkspaceProvisioning {
    private static final String SEMANTICS_ID = "local-authorized-directory";

    private final WorkspaceStore workspaces;
    private final WorkspaceBindingStore bindings;
    private final HostWorkspaceLocationStore locations;
    private final WorkspaceService workspaceService;
    private final ProjectId projectId;
    private final PrincipalRef owner;
    private final TimeProvider time;
    private final HostWorkspaceRegistryStore registry;
    private final WorkspaceId initialWorkspaceId;
    private final AtomicReference<HostWorkspaceScope> scope;
    private final AtomicBoolean registryMutation = new AtomicBoolean();

    public AuthorizedWorkspaceProvisioning(
            ProjectId projectId,
            WorkspaceStore workspaces,
            WorkspaceBindingStore bindings,
            HostWorkspaceLocationStore locations,
            WorkspaceService workspaceService,
            PrincipalRef owner,
            TimeProvider time,
            HostWorkspaceScope initialScope) {
        this(
                projectId,
                workspaces,
                bindings,
                locations,
                workspaceService,
                owner,
                time,
                initialScope,
                new InMemoryHostWorkspaceRegistryStore(),
                "workspace");
    }

    public AuthorizedWorkspaceProvisioning(
            ProjectId projectId,
            WorkspaceStore workspaces,
            WorkspaceBindingStore bindings,
            HostWorkspaceLocationStore locations,
            WorkspaceService workspaceService,
            PrincipalRef owner,
            TimeProvider time,
            HostWorkspaceScope initialScope,
            HostWorkspaceRegistryStore registry,
            String initialSafeDisplayName) {
        this.projectId = Objects.requireNonNull(projectId, "projectId must not be null");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces must not be null");
        this.bindings = Objects.requireNonNull(bindings, "bindings must not be null");
        this.locations = Objects.requireNonNull(locations, "locations must not be null");
        this.workspaceService = Objects.requireNonNull(workspaceService, "workspaceService must not be null");
        this.owner = Objects.requireNonNull(owner, "owner must not be null");
        this.time = Objects.requireNonNull(time, "time must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        HostWorkspaceScope startingScope = Objects.requireNonNull(initialScope, "initialScope must not be null");
        this.initialWorkspaceId = startingScope.allowedDirectories().getFirst().workspaceId();
        this.scope = new AtomicReference<>(restore(startingScope, initialSafeDisplayName));
    }

    /** Current scope snapshot. Callers must re-validate the snapshot before physical write I/O. */
    public HostWorkspaceScope scope() {
        if (registryMutation.get()) {
            throw HostWorkspaceScopeException.accessDenied(
                    null, "Directory authorization is changing; retry against the current registry");
        }
        return scope.get();
    }

    /** Safe product/model projection. Physical paths never appear in this view. */
    public List<HostWorkspaceRegistryView> registryViews() {
        return registry.list(projectId).stream()
                .map(HostWorkspaceRegistryEntry::view)
                .toList();
    }

    /** Fails closed when the scope has changed since the given snapshot was resolved. */
    public void requireUnchanged(HostWorkspaceScope resolvedAgainst) {
        Objects.requireNonNull(resolvedAgainst, "resolvedAgainst must not be null");
        if (registryMutation.get() || scope.get().version() != resolvedAgainst.version()) {
            throw HostWorkspaceScopeException.accessDenied(
                    null, "Directory authorization changed while the operation was in flight");
        }
    }

    /**
     * Authorizes one user-approved directory. If the directory is already covered by an existing
     * authorized boundary, the existing entry is reused with its current permission. If it would
     * swallow an existing boundary, the request is rejected fail closed.
     */
    public ProvisioningResult authorize(Path directory, HostDirectoryPermission permission) {
        return authorizeApprovedAttach(directory, permission, "legacy-approved-attach");
    }

    /** Registers an attach only after Runtime supplied the exact approved policy decision reference. */
    public synchronized ProvisioningResult authorizeApprovedAttach(
            Path directory, HostDirectoryPermission permission, String authorizationRef) {
        Objects.requireNonNull(directory, "directory must not be null");
        Objects.requireNonNull(permission, "permission must not be null");
        String approvedRef = requireText(authorizationRef, "authorizationRef");
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw HostWorkspaceScopeException.invalidArgument(
                    directory.toString(), "Authorized directory must be an existing directory");
        }
        if (HostWorkspacePathSafety.isUnsafeNode(directory)) {
            throw HostWorkspaceScopeException.invalidArgument(
                    directory.toString(),
                    "Authorized directory must not be a symbolic link or reparse point: " + directory);
        }
        Path realPath;
        try {
            realPath = directory.toRealPath();
        } catch (IOException exception) {
            throw HostWorkspaceScopeException.invalidArgument(
                    directory.toString(), "Authorized directory must exist and be accessible");
        }
        if (HostWorkspacePathSafety.isUnsafeNode(realPath)) {
            throw HostWorkspaceScopeException.invalidArgument(
                    realPath.toString(),
                    "Authorized directory must not be a symbolic link or reparse point: " + realPath);
        }

        HostWorkspaceScope current = scope.get();
        for (AuthorizedHostDirectory existing : current.allowedDirectories()) {
            if (existing.encloses(realPath)) {
                if (permission.canWrite() && !existing.permission().canWrite()) {
                    throw HostWorkspaceScopeException.permissionDenied(
                            realPath.toString(),
                            "Directory is already authorized as read-only by an enclosing boundary: " + realPath);
                }
                HostWorkspaceRegistryView view = registry.find(projectId, existing.workspaceId())
                        .map(HostWorkspaceRegistryEntry::view)
                        .orElseGet(() -> new HostWorkspaceRegistryView(
                                existing.workspaceId().value(),
                                safeDisplayName(existing.realPath(), existing.workspaceId()),
                                existing.permission(),
                                existing.workspaceId().equals(initialWorkspaceId)
                                        ? HostWorkspaceRegistrySource.INITIAL
                                        : HostWorkspaceRegistrySource.APPROVED_ATTACH,
                                HostWorkspaceRegistryStatus.ACTIVE));
                return new ProvisioningResult(existing, true, false, view);
            }
            if (existing.realPath().startsWith(realPath)) {
                throw HostWorkspaceScopeException.invalidArgument(
                        realPath.toString(),
                        "Directory overlaps an existing authorized boundary; choose a non-overlapping"
                                + " directory: "
                                + realPath);
            }
        }

        ProvisioningResult provisioned = provisionDirectory(realPath, permission);
        AuthorizedHostDirectory allowed = provisioned.directory();
        HostDirectoryIdentity identity = HostDirectoryIdentity.resolve(realPath);
        HostWorkspaceRegistryEntry entry = HostWorkspaceRegistryEntry.active(
                projectId,
                allowed.workspaceId(),
                identity.locationRef(),
                safeDisplayName(realPath, allowed.workspaceId()),
                permission,
                HostWorkspaceRegistrySource.APPROVED_ATTACH,
                realPath,
                identity.fingerprint(),
                approvedRef,
                time.now());
        registryMutation.set(true);
        try {
            HostWorkspaceRegistryEntry persisted = registry.find(projectId, allowed.workspaceId())
                    .map(existing -> registry.update(
                            existing.reactivate(realPath, identity.fingerprint(), approvedRef, time.now()),
                            existing.version()))
                    .orElseGet(() -> registry.create(entry));
            current = scope.get();
            scope.set(current.withDirectory(allowed));
            return new ProvisioningResult(allowed, false, provisioned.recovered(), persisted.view());
        } finally {
            registryMutation.set(false);
        }
    }

    /** Revokes the physical authorization of one directory. Logical facts stay untouched. */
    public void revoke(WorkspaceId workspaceId) {
        revoke(workspaceId, "USER_REVOKED");
    }

    public synchronized void revoke(WorkspaceId workspaceId, String reasonCode) {
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        if (workspaceId.equals(initialWorkspaceId)) {
            throw new IllegalArgumentException("initial workspace authorization cannot be revoked");
        }
        HostWorkspaceRegistryEntry entry = registry.find(projectId, workspaceId)
                .orElseThrow(() -> new IllegalArgumentException("Directory is not registered"));
        registryMutation.set(true);
        try {
            registry.update(entry.revoke(requireText(reasonCode, "reasonCode"), time.now()), entry.version());
            HostWorkspaceScope before = scope.get();
            HostWorkspaceScope updated = before.withoutDirectory(workspaceId);
            if (updated == before) {
                throw new IllegalArgumentException("Directory is not authorized in this scope");
            }
            scope.set(updated);
        } finally {
            registryMutation.set(false);
        }
    }

    private ProvisioningResult provisionDirectory(Path realPath, HostDirectoryPermission permission) {
        HostDirectoryIdentity identity = HostDirectoryIdentity.resolve(realPath);
        boolean recovered = workspaces.find(identity.workspaceId()).isPresent();
        WorkspaceBindingMode mode =
                permission.canWrite() ? WorkspaceBindingMode.DIRECT : WorkspaceBindingMode.READ_ONLY;
        bindings.find(identity.bindingId()).ifPresent(existing -> {
            if (existing.mode() != mode) {
                throw HostWorkspaceScopeException.permissionDenied(
                        realPath.toString(),
                        "Directory was previously authorized with a different permission; permission changes during"
                                + " recovery are not supported: "
                                + realPath);
            }
        });
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
        AuthorizedHostDirectory directory = AuthorizedHostDirectory.of(workspace.id(), realPath, permission);
        return new ProvisioningResult(
                directory,
                false,
                recovered,
                new HostWorkspaceRegistryView(
                        directory.workspaceId().value(),
                        safeDisplayName(realPath, directory.workspaceId()),
                        permission,
                        HostWorkspaceRegistrySource.APPROVED_ATTACH,
                        HostWorkspaceRegistryStatus.ACTIVE));
    }

    public record ProvisioningResult(
            AuthorizedHostDirectory directory,
            boolean reusedExistingBoundary,
            boolean recovered,
            HostWorkspaceRegistryView registryView) {
        public ProvisioningResult {
            Objects.requireNonNull(directory, "directory must not be null");
            Objects.requireNonNull(registryView, "registryView must not be null");
        }
    }

    private HostWorkspaceScope restore(HostWorkspaceScope initialScope, String initialSafeDisplayName) {
        AuthorizedHostDirectory initial = initialScope.allowedDirectories().getFirst();
        Workspace workspace = workspaces
                .find(initial.workspaceId())
                .orElseThrow(() -> new IllegalStateException("initial workspace is unavailable"));
        WorkspaceBinding binding = bindings.find(workspace.root().bindingId())
                .orElseThrow(() -> new IllegalStateException("initial workspace binding is unavailable"));
        Instant now = time.now();
        if (registry.find(projectId, initial.workspaceId()).isEmpty()) {
            registry.create(HostWorkspaceRegistryEntry.active(
                    projectId,
                    initial.workspaceId(),
                    binding.locationRef(),
                    initialSafeDisplayName,
                    initial.permission(),
                    HostWorkspaceRegistrySource.INITIAL,
                    initial.realPath(),
                    binding.rootFingerprint(),
                    "initial-workspace",
                    now));
        }

        HostWorkspaceScope recovered = initialScope;
        for (HostWorkspaceRegistryEntry entry : registry.list(projectId)) {
            if (entry.workspaceRef().equals(initial.workspaceId())
                    || entry.status() != HostWorkspaceRegistryStatus.ACTIVE) {
                continue;
            }
            try {
                Path verified = requireRestorable(entry);
                ProvisioningResult result = provisionDirectory(verified, entry.permission());
                recovered = recovered.withDirectory(result.directory());
                registry.update(entry.revalidated(verified, now), entry.version());
            } catch (RuntimeException failure) {
                registry.update(entry.disable(recoveryReason(failure), now), entry.version());
            }
        }
        return recovered;
    }

    private Path requireRestorable(HostWorkspaceRegistryEntry entry) {
        Path stored = entry.realPath();
        if (!Files.isDirectory(stored, LinkOption.NOFOLLOW_LINKS) || HostWorkspacePathSafety.isUnsafeNode(stored)) {
            throw new IllegalStateException("registered directory is unavailable");
        }
        try {
            Path verified = stored.toRealPath();
            if (HostWorkspacePathSafety.isUnsafeNode(verified)) {
                throw new IllegalStateException("registered directory is an unsafe node");
            }
            HostDirectoryIdentity identity = HostDirectoryIdentity.resolve(verified);
            if (!identity.workspaceId().equals(entry.workspaceRef())
                    || !identity.locationRef().equals(entry.locationRef())
                    || !identity.fingerprint().equals(entry.fingerprint())) {
                throw new IllegalStateException("registered directory identity has drifted");
            }
            return verified;
        } catch (IOException exception) {
            throw new IllegalStateException("registered directory cannot be resolved", exception);
        }
    }

    private static String recoveryReason(RuntimeException failure) {
        return failure instanceof IllegalArgumentException ? "REGISTRY_ROOT_OVERLAP" : "REGISTRY_REVALIDATION_FAILED";
    }

    private static String safeDisplayName(Path realPath, WorkspaceId workspaceId) {
        Path name = realPath.getFileName();
        String candidate = name == null ? fallbackDisplayName(workspaceId) : name.toString();
        String safe = candidate.replaceAll("[\\p{Cntrl}\\\\/]", "-").trim();
        if (safe.isEmpty()) safe = fallbackDisplayName(workspaceId);
        return safe.length() <= 80 ? safe : safe.substring(0, 80);
    }

    private static String fallbackDisplayName(WorkspaceId workspaceId) {
        String value = workspaceId.value();
        return "workspace-" + value.substring(0, Math.min(8, value.length()));
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
