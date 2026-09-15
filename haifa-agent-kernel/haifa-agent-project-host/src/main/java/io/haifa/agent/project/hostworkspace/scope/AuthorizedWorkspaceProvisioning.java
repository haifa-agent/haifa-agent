package io.haifa.agent.project.hostworkspace.scope;

import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.project.core.workspace.WorkspaceService;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.hostworkspace.HostWorkspaceLocationStore;
import io.haifa.agent.project.hostworkspace.HostWorkspacePathSafety;
import io.haifa.agent.project.hostworkspace.directory.AuthorizedDirectoryEntry;
import io.haifa.agent.project.hostworkspace.directory.AuthorizedDirectoryStatus;
import io.haifa.agent.project.hostworkspace.directory.AuthorizedDirectoryStore;
import io.haifa.agent.project.hostworkspace.directory.AuthorizedDirectoryView;
import io.haifa.agent.project.hostworkspace.directory.InMemoryAuthorizedDirectoryStore;
import io.haifa.agent.project.store.WorkspaceStore;
import io.haifa.agent.project.workspace.WorkspaceAccessMode;
import io.haifa.agent.project.workspace.WorkspaceId;
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
 * Trusted local product boundary that turns a user-approved directory into a peer member of the
 * {@link HostWorkspaceScope}. It must only be invoked after the user has approved the directory:
 * it resolves the real path, applies the overlap policy, provisions or recovers the logical
 * workspace, writes the single durable authorization record, and atomically swaps the scope
 * snapshot. Revocation removes the physical authorization immediately; it never deletes user files
 * or the logical workspace and authorization audit facts. The Host boundary does not provide any OS
 * sandbox: ordinary host processes launched through the provider can still reach whatever the OS
 * user can reach.
 */
public final class AuthorizedWorkspaceProvisioning {
    private final WorkspaceStore workspaces;
    private final HostWorkspaceLocationStore locations;
    private final WorkspaceService workspaceService;
    private final ProjectId projectId;
    private final TenantRef tenant;
    private final PrincipalRef owner;
    private final TimeProvider time;
    private final AuthorizedDirectoryStore registry;
    private final WorkspaceId initialWorkspaceId;
    private final AtomicReference<HostWorkspaceScope> scope;
    private final AtomicBoolean registryMutation = new AtomicBoolean();

    public AuthorizedWorkspaceProvisioning(
            ProjectId projectId,
            WorkspaceStore workspaces,
            HostWorkspaceLocationStore locations,
            WorkspaceService workspaceService,
            TenantRef tenant,
            PrincipalRef owner,
            TimeProvider time,
            HostWorkspaceScope initialScope) {
        this(
                projectId,
                workspaces,
                locations,
                workspaceService,
                tenant,
                owner,
                time,
                initialScope,
                new InMemoryAuthorizedDirectoryStore(),
                "workspace");
    }

    public AuthorizedWorkspaceProvisioning(
            ProjectId projectId,
            WorkspaceStore workspaces,
            HostWorkspaceLocationStore locations,
            WorkspaceService workspaceService,
            TenantRef tenant,
            PrincipalRef owner,
            TimeProvider time,
            HostWorkspaceScope initialScope,
            AuthorizedDirectoryStore registry,
            String initialSafeDisplayName) {
        this.projectId = Objects.requireNonNull(projectId, "projectId must not be null");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces must not be null");
        this.locations = Objects.requireNonNull(locations, "locations must not be null");
        this.workspaceService = Objects.requireNonNull(workspaceService, "workspaceService must not be null");
        this.tenant = Objects.requireNonNull(tenant, "tenant must not be null");
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

    /** Initial workspace identifier for this provisioning instance. */
    public WorkspaceId initialWorkspaceId() {
        return initialWorkspaceId;
    }

    /** Safe product/model projection. Physical paths never appear in this view. */
    public List<AuthorizedDirectoryView> directoryViews() {
        return registry.list(projectId).stream()
                .filter(this::owns)
                .map(AuthorizedDirectoryEntry::view)
                .toList();
    }

    /**
     * Requires the current durable authorization for the given owner and mode. Failures preserve the
     * product vocabulary used by the file and execution entry points.
     */
    public AuthorizedDirectoryEntry requireAuthorized(
            TenantRef requestTenant, PrincipalRef requester, WorkspaceId workspaceId, WorkspaceAccessMode required) {
        Objects.requireNonNull(requestTenant, "requestTenant must not be null");
        Objects.requireNonNull(requester, "requester must not be null");
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        Objects.requireNonNull(required, "required must not be null");
        AuthorizedDirectoryEntry entry = registry.find(projectId, workspaceId)
                .filter(candidate -> candidate.status() == AuthorizedDirectoryStatus.ACTIVE)
                .filter(candidate -> candidate.tenant().equals(requestTenant)
                        && candidate.owner().equals(requester))
                .orElseThrow(() -> new SecurityException("WORKSPACE_ACCESS_UNAVAILABLE"));
        if (!entry.mode().allows(required)) {
            throw new SecurityException("WORKSPACE_ACCESS_MODE_DENIED");
        }
        return entry;
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
     * authorized boundary, the existing entry is reused and its current mode is kept. If it would
     * swallow an existing boundary, the request is rejected fail closed.
     */
    public ProvisioningResult authorize(Path directory) {
        return authorizeApprovedAttach(directory, WorkspaceAccessMode.DEVELOP);
    }

    public synchronized ProvisioningResult authorizeApprovedAttach(Path directory, WorkspaceAccessMode requestedMode) {
        Objects.requireNonNull(directory, "directory must not be null");
        Objects.requireNonNull(requestedMode, "requestedMode must not be null");
        if (HostWorkspacePathSafety.isUnsafeNode(directory)) {
            throw HostWorkspaceScopeException.invalidArgument(
                    directory.toString(),
                    "Authorized directory must not be a symbolic link or reparse point: " + directory);
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw HostWorkspaceScopeException.invalidArgument(
                    directory.toString(), "Authorized directory must be an existing directory");
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
                AuthorizedDirectoryEntry enclosing = registry.find(projectId, existing.workspaceId())
                        .filter(this::owns)
                        .filter(entry -> entry.status() == AuthorizedDirectoryStatus.ACTIVE)
                        .orElseThrow(() -> HostWorkspaceScopeException.accessDenied(
                                realPath.toString(),
                                "Enclosing authorized directory has no current active owned authorization"));
                try {
                    locations.resolveVerified(existing.workspaceId());
                } catch (RuntimeException failure) {
                    throw HostWorkspaceScopeException.accessDenied(
                            realPath.toString(), "Enclosing authorized directory is no longer verifiable");
                }
                return new ProvisioningResult(existing, true, false, enclosing.view());
            }
            if (existing.realPath().startsWith(realPath)) {
                throw HostWorkspaceScopeException.invalidArgument(
                        realPath.toString(),
                        "Directory overlaps an existing authorized boundary; choose a non-overlapping"
                                + " directory: "
                                + realPath);
            }
        }

        HostDirectoryIdentity identity = HostDirectoryIdentity.resolve(realPath);
        AuthorizedDirectoryEntry existingEntry =
                registry.find(projectId, identity.workspaceId()).orElse(null);
        if (existingEntry != null && !owns(existingEntry)) {
            throw HostWorkspaceScopeException.accessDenied(
                    realPath.toString(), "Authorized directory belongs to a different owner");
        }
        boolean recovered = existingEntry != null;
        AuthorizedHostDirectory allowed = provisionDirectory(realPath, identity);
        Instant now = time.now();
        registryMutation.set(true);
        try {
            AuthorizedDirectoryEntry persisted;
            if (existingEntry == null) {
                persisted = registry.create(AuthorizedDirectoryEntry.active(
                        projectId,
                        allowed.workspaceId(),
                        tenant,
                        owner,
                        requestedMode,
                        safeDisplayName(realPath, allowed.workspaceId()),
                        realPath,
                        identity.physicalFingerprint(),
                        now));
            } else {
                AuthorizedDirectoryEntry updated = existingEntry.status() == AuthorizedDirectoryStatus.ACTIVE
                        ? existingEntry.revalidated(realPath, identity.physicalFingerprint(), now)
                        : existingEntry.reactivate(realPath, identity.physicalFingerprint(), requestedMode, now);
                persisted = registry.update(updated, existingEntry.version());
            }
            current = scope.get();
            scope.set(current.withDirectory(allowed));
            return new ProvisioningResult(allowed, false, recovered, persisted.view());
        } finally {
            registryMutation.set(false);
        }
    }

    /** Revokes the physical authorization of one directory. Logical and audit facts stay untouched. */
    public void revoke(WorkspaceId workspaceId) {
        revoke(workspaceId, "USER_REVOKED");
    }

    public synchronized void revoke(WorkspaceId workspaceId, String reasonCode) {
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        if (workspaceId.equals(initialWorkspaceId)) {
            throw new IllegalArgumentException("initial workspace authorization cannot be revoked");
        }
        AuthorizedDirectoryEntry entry = registry.find(projectId, workspaceId)
                .filter(this::owns)
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
            if (locations.contains(workspaceId)) {
                locations.unregisterForTrustedProvider(workspaceId, entry.realPath());
            }
        } finally {
            registryMutation.set(false);
        }
    }

    private AuthorizedHostDirectory provisionDirectory(Path realPath, HostDirectoryIdentity identity) {
        workspaceService.provisionDirectory(projectId, identity.workspaceId(), identity.fingerprint());
        if (!locations.contains(identity.workspaceId())) {
            locations.register(identity.workspaceId(), realPath);
        }
        return AuthorizedHostDirectory.of(identity.workspaceId(), realPath);
    }

    public record ProvisioningResult(
            AuthorizedHostDirectory directory,
            boolean reusedExistingBoundary,
            boolean recovered,
            AuthorizedDirectoryView directoryView) {
        public ProvisioningResult {
            Objects.requireNonNull(directory, "directory must not be null");
            Objects.requireNonNull(directoryView, "directoryView must not be null");
        }
    }

    private HostWorkspaceScope restore(HostWorkspaceScope initialScope, String initialSafeDisplayName) {
        AuthorizedHostDirectory initial = initialScope.allowedDirectories().getFirst();
        Instant now = time.now();
        HostDirectoryIdentity initialIdentity = HostDirectoryIdentity.resolve(initial.realPath());
        Path initialRealPath = initial.realPath().toAbsolutePath().normalize();
        AuthorizedDirectoryEntry existingInitial =
                registry.find(projectId, initial.workspaceId()).orElse(null);
        if (existingInitial == null) {
            registry.create(AuthorizedDirectoryEntry.active(
                    projectId,
                    initial.workspaceId(),
                    tenant,
                    owner,
                    WorkspaceAccessMode.DEVELOP,
                    initialSafeDisplayName,
                    initialRealPath,
                    initialIdentity.physicalFingerprint(),
                    now));
        } else {
            if (!owns(existingInitial) || existingInitial.status() != AuthorizedDirectoryStatus.ACTIVE) {
                throw HostWorkspaceScopeException.accessDenied(
                        initialRealPath.toString(),
                        "Initial authorized directory has no current active owned authorization");
            }
            if (!existingInitial.realPath().equals(initialRealPath)
                    || !existingInitial.physicalFingerprint().equals(initialIdentity.physicalFingerprint())) {
                throw HostWorkspaceScopeException.accessDenied(
                        initialRealPath.toString(),
                        "Initial authorized directory path or physical identity changed;"
                                + " explicit re-authorization is required");
            }
        }

        HostWorkspaceScope recovered = initialScope;
        for (AuthorizedDirectoryEntry entry : registry.list(projectId)) {
            if (entry.workspaceRef().equals(initial.workspaceId())
                    || !owns(entry)
                    || entry.status() != AuthorizedDirectoryStatus.ACTIVE) {
                continue;
            }
            try {
                RestorableDirectory restorable = requireRestorable(entry);
                AuthorizedHostDirectory directory = provisionDirectory(restorable.realPath(), restorable.identity());
                recovered = recovered.withDirectory(directory);
                registry.update(
                        entry.revalidated(restorable.realPath(), restorable.physicalFingerprint(), now),
                        entry.version());
            } catch (RuntimeException failure) {
                registry.update(entry.disable(recoveryReason(failure), now), entry.version());
            }
        }
        return recovered;
    }

    private boolean owns(AuthorizedDirectoryEntry entry) {
        return entry.tenant().equals(tenant) && entry.owner().equals(owner);
    }

    private RestorableDirectory requireRestorable(AuthorizedDirectoryEntry entry) {
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
            if (!identity.workspaceId().equals(entry.workspaceRef())) {
                throw new IllegalStateException("registered directory identity has drifted");
            }
            if (!identity.physicalFingerprint().equals(entry.physicalFingerprint())) {
                throw new IllegalStateException("registered directory physical identity has changed");
            }
            return new RestorableDirectory(verified, identity, entry.physicalFingerprint());
        } catch (IOException exception) {
            throw new IllegalStateException("registered directory cannot be resolved", exception);
        }
    }

    private record RestorableDirectory(Path realPath, HostDirectoryIdentity identity, String physicalFingerprint) {}

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
