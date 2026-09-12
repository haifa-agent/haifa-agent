package io.haifa.agent.project.hostworkspace.scope;

import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.project.binding.WorkspaceBinding;
import io.haifa.agent.project.binding.WorkspaceBindingId;
import io.haifa.agent.project.binding.WorkspaceBindingMode;
import io.haifa.agent.project.binding.WorkspaceLocationRef;
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
import java.util.function.Consumer;

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
     * authorized boundary, the existing entry is reused. If it would swallow an existing boundary,
     * the request is rejected fail closed. User authorization remains in WorkspaceAccess outside
     * this Host mount boundary.
     */
    ProvisioningResult authorize(Path directory) {
        return authorizeApprovedAttach(directory, ignored -> {});
    }

    /**
     * Registers an approved attach and invokes the product-owned access activation after the
     * Registry write but before the new directory is published in the live Scope. During this
     * sequence {@link #scope()} fails closed, so no observer can combine a new mount with stale
     * access.
     */
    public synchronized ProvisioningResult authorizeApprovedAttach(
            Path directory, Consumer<AuthorizedHostDirectory> accessActivation) {
        Objects.requireNonNull(directory, "directory must not be null");
        Objects.requireNonNull(accessActivation, "accessActivation must not be null");
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
                HostWorkspaceRegistryView view = registry.find(projectId, existing.workspaceId())
                        .map(HostWorkspaceRegistryEntry::view)
                        .orElseGet(() -> new HostWorkspaceRegistryView(
                                existing.workspaceId().value(),
                                safeDisplayName(existing.realPath(), existing.workspaceId()),
                                existing.workspaceId().equals(initialWorkspaceId)
                                        ? HostWorkspaceRegistrySource.INITIAL
                                        : HostWorkspaceRegistrySource.APPROVED_ATTACH,
                                HostWorkspaceRegistryStatus.ACTIVE));
                accessActivation.accept(existing);
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

        HostDirectoryIdentity identity = HostDirectoryIdentity.resolve(realPath);
        ProvisioningResult provisioned = provisionDirectory(realPath, identity);
        AuthorizedHostDirectory allowed = provisioned.directory();
        HostWorkspaceRegistryEntry entry = HostWorkspaceRegistryEntry.active(
                projectId,
                allowed.workspaceId(),
                identity.locationRef(),
                safeDisplayName(realPath, allowed.workspaceId()),
                HostWorkspaceRegistrySource.APPROVED_ATTACH,
                realPath,
                identity.physicalFingerprint(),
                time.now());
        registryMutation.set(true);
        try {
            HostWorkspaceRegistryEntry persisted = registry.find(projectId, allowed.workspaceId())
                    .map(existing -> registry.update(
                            existing.reactivate(realPath, identity.physicalFingerprint(), time.now()),
                            existing.version()))
                    .orElseGet(() -> registry.create(entry));
            try {
                accessActivation.accept(allowed);
            } catch (RuntimeException activationFailure) {
                try {
                    registry.update(
                            persisted.disable("WORKSPACE_ACCESS_ACTIVATION_FAILED", time.now()), persisted.version());
                } catch (RuntimeException compensationFailure) {
                    activationFailure.addSuppressed(compensationFailure);
                }
                throw activationFailure;
            }
            current = scope.get();
            scope.set(current.withDirectory(allowed));
            return new ProvisioningResult(allowed, false, provisioned.recovered(), persisted.view());
        } finally {
            registryMutation.set(false);
        }
    }

    /** Registers a trusted provider-created Git worktree reached through the approved Tool path. */
    public synchronized ProvisioningResult authorizeApprovedWorktree(
            WorkspaceId parentWorkspaceId,
            WorkspaceId childWorkspaceId,
            WorkspaceBindingId childBindingId,
            WorkspaceLocationRef childLocationRef,
            String safeDisplayName) {
        Objects.requireNonNull(parentWorkspaceId, "parentWorkspaceId must not be null");
        Objects.requireNonNull(childWorkspaceId, "childWorkspaceId must not be null");
        Objects.requireNonNull(childBindingId, "childBindingId must not be null");
        Objects.requireNonNull(childLocationRef, "childLocationRef must not be null");
        if (scope.get().allowedDirectories().stream()
                .noneMatch(directory -> directory.workspaceId().equals(parentWorkspaceId))) {
            throw HostWorkspaceScopeException.accessDenied(null, "worktree parent workspace is not active");
        }
        Workspace child = workspaces
                .find(childWorkspaceId)
                .orElseThrow(() -> new IllegalStateException("provider-created child workspace is unavailable"));
        WorkspaceBinding binding = bindings.find(childBindingId)
                .orElseThrow(() -> new IllegalStateException("provider-created child binding is unavailable"));
        if (!child.root().bindingId().equals(childBindingId)
                || !binding.locationRef().equals(childLocationRef)
                || binding.mode() != WorkspaceBindingMode.COPY_ON_WRITE
                || !binding.capabilities().allows("execution_run")) {
            throw new IllegalStateException("provider-created worktree authority does not match registration");
        }
        Path target;
        try {
            target = locations.resolveForTrustedProvider(childLocationRef).toRealPath();
        } catch (IOException exception) {
            throw new IllegalStateException("provider-created worktree cannot be resolved", exception);
        }
        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) || HostWorkspacePathSafety.isUnsafeNode(target)) {
            throw new IllegalStateException("provider-created worktree is not a safe directory");
        }
        String bindingFingerprint = HostWorkspaceLocationStore.fingerprintFor(target);
        if (!binding.rootFingerprint().equals(bindingFingerprint)) {
            throw new IllegalStateException("provider-created worktree fingerprint does not match its binding");
        }
        String physicalFingerprint = HostDirectoryIdentity.resolve(target).physicalFingerprint();
        AuthorizedHostDirectory directory = AuthorizedHostDirectory.of(childWorkspaceId, target);
        HostWorkspaceScope current = scope.get();
        HostWorkspaceScope updated = current.withDirectory(directory);
        HostWorkspaceRegistryEntry entry = HostWorkspaceRegistryEntry.active(
                projectId,
                childWorkspaceId,
                childLocationRef,
                safeDisplayName,
                HostWorkspaceRegistrySource.APPROVED_WORKTREE_CREATE,
                target,
                physicalFingerprint,
                time.now());
        registryMutation.set(true);
        try {
            HostWorkspaceRegistryEntry persisted = registry.create(entry);
            scope.set(updated);
            return new ProvisioningResult(directory, false, false, persisted.view());
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

    private ProvisioningResult provisionDirectory(Path realPath) {
        HostDirectoryIdentity identity = HostDirectoryIdentity.resolve(realPath);
        return provisionDirectory(realPath, identity);
    }

    private ProvisioningResult provisionDirectory(Path realPath, HostDirectoryIdentity identity) {
        boolean recovered = workspaces.find(identity.workspaceId()).isPresent();
        bindings.find(identity.bindingId()).ifPresent(existing -> {
            if (existing.mode() != WorkspaceBindingMode.DIRECT) {
                throw HostWorkspaceScopeException.permissionDenied(
                        realPath.toString(),
                        "Directory binding does not provide the required technical mount capability: " + realPath);
            }
        });
        WorkspaceBinding binding = WorkspaceBinding.provision(
                        identity.bindingId(),
                        identity.locationRef(),
                        WorkspaceBindingMode.DIRECT,
                        owner,
                        WorkspaceCapabilitySet.readWriteFiles(),
                        WorkspacePermissionSet.readWrite(),
                        identity.fingerprint(),
                        time.now())
                .activate(time.now());
        Workspace workspace =
                workspaceService.provisionDirectory(projectId, identity.workspaceId(), binding, SEMANTICS_ID);
        if (!locations.contains(identity.locationRef())) {
            locations.register(identity.locationRef(), realPath);
        }
        AuthorizedHostDirectory directory = AuthorizedHostDirectory.of(workspace.id(), realPath);
        return new ProvisioningResult(
                directory,
                false,
                recovered,
                new HostWorkspaceRegistryView(
                        directory.workspaceId().value(),
                        safeDisplayName(realPath, directory.workspaceId()),
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
                    HostWorkspaceRegistrySource.INITIAL,
                    initial.realPath(),
                    HostDirectoryIdentity.resolve(initial.realPath()).physicalFingerprint(),
                    now));
        }

        HostWorkspaceScope recovered = initialScope;
        for (HostWorkspaceRegistryEntry entry : registry.list(projectId)) {
            if (entry.workspaceRef().equals(initial.workspaceId())
                    || entry.status() != HostWorkspaceRegistryStatus.ACTIVE) {
                continue;
            }
            try {
                if (entry.source() == HostWorkspaceRegistrySource.APPROVED_WORKTREE_CREATE) {
                    throw new IllegalStateException("worktree recovery requires trusted Git reconciliation");
                }
                RestorableDirectory restorable = requireRestorable(entry);
                ProvisioningResult result = provisionDirectory(restorable.realPath());
                recovered = recovered.withDirectory(result.directory());
                registry.update(
                        entry.revalidated(restorable.realPath(), restorable.physicalFingerprint(), now),
                        entry.version());
            } catch (RuntimeException failure) {
                registry.update(entry.disable(recoveryReason(failure), now), entry.version());
            }
        }
        return recovered;
    }

    private RestorableDirectory requireRestorable(HostWorkspaceRegistryEntry entry) {
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
                    || !identity.locationRef().equals(entry.locationRef())) {
                throw new IllegalStateException("registered directory identity has drifted");
            }
            return new RestorableDirectory(verified, identity.physicalFingerprint());
        } catch (IOException exception) {
            throw new IllegalStateException("registered directory cannot be resolved", exception);
        }
    }

    private record RestorableDirectory(Path realPath, String physicalFingerprint) {}

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
