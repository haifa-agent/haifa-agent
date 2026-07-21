package io.haifa.agent.project.provider.local;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.project.binding.WorkspaceBinding;
import io.haifa.agent.project.binding.WorkspaceBindingMode;
import io.haifa.agent.project.binding.WorkspaceBindingStatus;
import io.haifa.agent.project.changeset.FileChange;
import io.haifa.agent.project.changeset.FileChangeSet;
import io.haifa.agent.project.changeset.FileChangeSetService;
import io.haifa.agent.project.changeset.FileChangeSetStore;
import io.haifa.agent.project.changeset.FileChangeType;
import io.haifa.agent.project.changeset.FileVersion;
import io.haifa.agent.project.filesystem.FileType;
import io.haifa.agent.project.mutation.CreateFileRequest;
import io.haifa.agent.project.mutation.DeleteFileRequest;
import io.haifa.agent.project.mutation.MoveFileRequest;
import io.haifa.agent.project.mutation.MutationContext;
import io.haifa.agent.project.mutation.MutationErrorCode;
import io.haifa.agent.project.mutation.MutationPrecondition;
import io.haifa.agent.project.mutation.MutationResult;
import io.haifa.agent.project.mutation.WorkspaceMutationCapabilities;
import io.haifa.agent.project.mutation.WorkspaceMutationException;
import io.haifa.agent.project.mutation.WorkspaceMutationProvider;
import io.haifa.agent.project.mutation.WorkspaceWriteLease;
import io.haifa.agent.project.mutation.WorkspaceWriteLeaseManager;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.path.WorkspacePath;
import io.haifa.agent.project.quarantine.QuarantineEntry;
import io.haifa.agent.project.quarantine.QuarantineRestoreRequest;
import io.haifa.agent.project.quarantine.QuarantineService;
import io.haifa.agent.project.quarantine.QuarantineStore;
import io.haifa.agent.project.store.WorkspaceBindingStore;
import io.haifa.agent.project.store.WorkspaceStore;
import io.haifa.agent.project.workspace.Workspace;
import io.haifa.agent.project.workspace.WorkspacePermission;
import io.haifa.agent.project.workspace.WorkspaceRevision;
import io.haifa.agent.project.workspace.WorkspaceStatus;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class LocalWorkspaceMutationService implements WorkspaceMutationProvider, QuarantineService {
    private static final int MAX_CONTENT_BYTES = 16 * 1024 * 1024;
    private static final String QUARANTINE_DIRECTORY = ".haifa-quarantine";

    private final WorkspaceStore workspaces;
    private final WorkspaceBindingStore bindings;
    private final LocalWorkspaceLocationStore locations;
    private final SensitivePathPolicy sensitivePaths;
    private final WorkspaceWriteLeaseManager leases;
    private final FileChangeSetStore changeSets;
    private final FileChangeSetService changeSetService;
    private final QuarantineStore quarantine;
    private final IdentifierGenerator identifiers;
    private final TimeProvider time;

    public LocalWorkspaceMutationService(
            WorkspaceStore workspaces,
            WorkspaceBindingStore bindings,
            LocalWorkspaceLocationStore locations,
            SensitivePathPolicy sensitivePaths,
            WorkspaceWriteLeaseManager leases,
            FileChangeSetStore changeSets,
            FileChangeSetService changeSetService,
            QuarantineStore quarantine,
            IdentifierGenerator identifiers,
            TimeProvider time) {
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces must not be null");
        this.bindings = Objects.requireNonNull(bindings, "bindings must not be null");
        this.locations = Objects.requireNonNull(locations, "locations must not be null");
        this.sensitivePaths = Objects.requireNonNull(sensitivePaths, "sensitivePaths must not be null");
        this.leases = Objects.requireNonNull(leases, "leases must not be null");
        this.changeSets = Objects.requireNonNull(changeSets, "changeSets must not be null");
        this.changeSetService = Objects.requireNonNull(changeSetService, "changeSetService must not be null");
        this.quarantine = Objects.requireNonNull(quarantine, "quarantine must not be null");
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers must not be null");
        this.time = Objects.requireNonNull(time, "time must not be null");
    }

    @Override
    public String providerId() {
        return "local-guarded";
    }

    @Override
    public WorkspaceMutationCapabilities capabilities() {
        String operatingSystem = System.getProperty("os.name", "unknown").toLowerCase(java.util.Locale.ROOT);
        String caseSensitivity = operatingSystem.contains("win") || operatingSystem.contains("mac")
                ? "case-insensitive-default"
                : "case-sensitive-default";
        return new WorkspaceMutationCapabilities(true, true, caseSensitivity);
    }

    @Override
    public MutationResult create(CreateFileRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        ensureContentBudget(request.path(), request.content());
        access(request.path(), WorkspacePermission.WRITE);
        Optional<MutationResult> replay = replay(
                request.path(),
                request.context().operationId(),
                FileChangeType.CREATE,
                null,
                "sha256:" + hash(request.content()));
        if (replay.isPresent()) return replay.orElseThrow();
        try (WorkspaceWriteLease ignored =
                leases.acquire(request.path().workspaceId(), request.context().operationId())) {
            Access access = access(request.path(), WorkspacePermission.WRITE);
            validateRevision(access.workspace(), request.precondition(), request.path());
            Path target = resolveAbsent(access, request.path());
            FileChangeSet pending = begin(access.workspace(), request.context());
            try {
                boolean atomic = writeAtomically(
                        target,
                        request.content(),
                        false,
                        request.path(),
                        () -> requireStillAbsent(access, request.path(), target));
                FileVersion after = version(target, request.path());
                return completeOrUnknown(
                        access.workspace(),
                        pending,
                        List.of(new FileChange(
                                FileChangeType.CREATE, request.path().projectPath(), null, null, after)),
                        atomic);
            } catch (WorkspaceMutationException exception) {
                fail(pending, exception.getMessage());
                throw exception;
            }
        }
    }

    @Override
    public MutationResult write(io.haifa.agent.project.mutation.WriteFileRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        ensureContentBudget(request.path(), request.content());
        access(request.path(), WorkspacePermission.WRITE);
        Optional<MutationResult> replay = replay(
                request.path(),
                request.context().operationId(),
                FileChangeType.REPLACE,
                null,
                "sha256:" + hash(request.content()));
        if (replay.isPresent()) return replay.orElseThrow();
        try (WorkspaceWriteLease ignored =
                leases.acquire(request.path().workspaceId(), request.context().operationId())) {
            Access access = access(request.path(), WorkspacePermission.WRITE);
            validateRevision(access.workspace(), request.precondition(), request.path());
            Path target = resolveExisting(access, request.path());
            FileVersion before = requireRegularVersion(target, request.path());
            validateHash(before, request.precondition(), request.path());
            FileChangeSet pending = begin(access.workspace(), request.context());
            try {
                boolean atomic = writeAtomically(
                        target,
                        request.content(),
                        true,
                        request.path(),
                        () -> validateHash(
                                requireRegularVersion(resolveExisting(access, request.path()), request.path()),
                                request.precondition(),
                                request.path()));
                FileVersion after = requireRegularVersion(resolveExisting(access, request.path()), request.path());
                return completeOrUnknown(
                        access.workspace(),
                        pending,
                        List.of(new FileChange(
                                FileChangeType.REPLACE, request.path().projectPath(), null, before, after)),
                        atomic);
            } catch (WorkspaceMutationException exception) {
                fail(pending, exception.getMessage());
                throw exception;
            }
        }
    }

    @Override
    public MutationResult delete(DeleteFileRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        access(request.path(), WorkspacePermission.DELETE);
        Optional<MutationResult> replay =
                replay(request.path(), request.context().operationId(), FileChangeType.DELETE, null, null);
        if (replay.isPresent()) return replay.orElseThrow();
        try (WorkspaceWriteLease ignored =
                leases.acquire(request.path().workspaceId(), request.context().operationId())) {
            Access access = access(request.path(), WorkspacePermission.DELETE);
            validateRevision(access.workspace(), request.precondition(), request.path());
            Path source = resolveExisting(access, request.path());
            FileVersion before = version(source, request.path());
            validateHash(before, request.precondition(), request.path());
            if (before.type() == FileType.DIRECTORY) requireEmptyDirectory(source, request.path());
            FileChangeSet pending = begin(access.workspace(), request.context());
            String token = safeToken(identifiers.nextValue());
            try {
                Path quarantineDirectory = quarantineDirectory(access, request.path());
                Path destination = quarantineDirectory.resolve(token);
                boolean atomic = movePath(
                        source,
                        destination,
                        false,
                        request.path(),
                        () -> validateHash(
                                version(resolveExisting(access, request.path()), request.path()),
                                request.precondition(),
                                request.path()));
                Instant quarantinedAt = time.now();
                quarantine.create(new QuarantineEntry(
                        token,
                        access.workspace().id(),
                        request.context().operationId(),
                        request.path().projectPath(),
                        before,
                        quarantinedAt,
                        quarantinedAt.plus(java.time.Duration.ofDays(30))));
                return completeOrUnknown(
                        access.workspace(),
                        pending,
                        List.of(new FileChange(
                                FileChangeType.DELETE, request.path().projectPath(), null, before, null)),
                        atomic);
            } catch (WorkspaceMutationException exception) {
                fail(pending, exception.getMessage());
                throw exception;
            }
        }
    }

    @Override
    public MutationResult move(MoveFileRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        access(request.source(), WorkspacePermission.WRITE);
        access(request.destination(), WorkspacePermission.WRITE);
        Optional<MutationResult> replay = replay(
                request.source(),
                request.context().operationId(),
                FileChangeType.MOVE,
                request.destination().projectPath(),
                null);
        if (replay.isPresent()) return replay.orElseThrow();
        try (WorkspaceWriteLease ignored =
                leases.acquire(request.source().workspaceId(), request.context().operationId())) {
            Access access = access(request.source(), WorkspacePermission.WRITE);
            access(request.destination(), WorkspacePermission.WRITE);
            validateRevision(access.workspace(), request.sourcePrecondition(), request.source());
            Path source = resolveExisting(access, request.source());
            Path destination = resolveAbsent(access, request.destination());
            FileVersion before = version(source, request.source());
            validateHash(before, request.sourcePrecondition(), request.source());
            FileChangeSet pending = begin(access.workspace(), request.context());
            try {
                boolean atomic = movePath(source, destination, false, request.source(), () -> {
                    validateHash(
                            version(resolveExisting(access, request.source()), request.source()),
                            request.sourcePrecondition(),
                            request.source());
                    requireStillAbsent(access, request.destination(), destination);
                });
                FileVersion after = version(resolveExisting(access, request.destination()), request.destination());
                return completeOrUnknown(
                        access.workspace(),
                        pending,
                        List.of(new FileChange(
                                FileChangeType.MOVE,
                                request.source().projectPath(),
                                request.destination().projectPath(),
                                before,
                                after)),
                        atomic);
            } catch (WorkspaceMutationException exception) {
                fail(pending, exception.getMessage());
                throw exception;
            }
        }
    }

    @Override
    public MutationResult restore(QuarantineRestoreRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        QuarantineEntry entry = quarantine
                .find(request.token())
                .orElseThrow(() -> failure(
                        MutationErrorCode.TARGET_NOT_FOUND, request.destination(), "quarantine entry not found"));
        if (!entry.workspaceId().equals(request.destination().workspaceId())) {
            throw failure(
                    MutationErrorCode.CROSS_PROVIDER_MOVE_UNSUPPORTED,
                    request.destination(),
                    "quarantine restore cannot cross workspaces");
        }
        access(request.destination(), WorkspacePermission.WRITE);
        Optional<MutationResult> replay = replay(
                request.destination(),
                request.context().operationId(),
                FileChangeType.CREATE,
                null,
                entry.version().contentHash());
        if (replay.isPresent()) return replay.orElseThrow();
        try (WorkspaceWriteLease ignored = leases.acquire(
                request.destination().workspaceId(), request.context().operationId())) {
            Access access = access(request.destination(), WorkspacePermission.WRITE);
            Path destination = resolveAbsent(access, request.destination());
            Path source = quarantineDirectory(access, request.destination()).resolve(entry.token());
            if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS) || isLinkOrReparse(source)) {
                throw failure(
                        MutationErrorCode.OUTCOME_UNKNOWN, request.destination(), "quarantined content is unavailable");
            }
            FileChangeSet pending = begin(access.workspace(), request.context());
            try {
                if (!version(source, request.destination()).equals(entry.version())) {
                    throw failure(
                            MutationErrorCode.CONCURRENT_MODIFICATION,
                            request.destination(),
                            "quarantined content changed");
                }
                boolean atomic = movePath(
                        source,
                        destination,
                        false,
                        request.destination(),
                        () -> requireStillAbsent(access, request.destination(), destination));
                FileVersion after = version(destination, request.destination());
                if (!after.equals(entry.version())) {
                    throw failure(
                            MutationErrorCode.CONCURRENT_MODIFICATION,
                            request.destination(),
                            "quarantined content changed");
                }
                quarantine.remove(entry.token());
                return completeOrUnknown(
                        access.workspace(),
                        pending,
                        List.of(new FileChange(
                                FileChangeType.CREATE, request.destination().projectPath(), null, null, after)),
                        atomic);
            } catch (WorkspaceMutationException exception) {
                fail(pending, exception.getMessage());
                throw exception;
            }
        }
    }

    private Access access(WorkspacePath path, WorkspacePermission permission) {
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
        if (!sensitivePaths.mayRead(path.projectPath())) {
            throw failure(MutationErrorCode.PATH_DENIED, path, "protected logical path rejects mutations");
        }
        try {
            Path root = locations.resolve(binding.locationRef()).toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!LocalWorkspaceLocationStore.fingerprintFor(root).equals(binding.rootFingerprint())
                    || isLinkOrReparse(root)) {
                throw failure(MutationErrorCode.BINDING_INACTIVE, path, "workspace root identity changed");
            }
            return new Access(workspace, binding, root);
        } catch (IOException | IllegalStateException exception) {
            throw failure(MutationErrorCode.BINDING_INACTIVE, path, "workspace location is unavailable");
        }
    }

    private Path resolveExisting(Access access, WorkspacePath logical) {
        Path target = access.root();
        for (String segment : logical.projectPath().segments()) {
            target = target.resolve(segment).normalize();
            verifyContained(access, logical, target);
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw failure(MutationErrorCode.TARGET_NOT_FOUND, logical, "logical target does not exist");
            }
            if (isLinkOrReparse(target)) {
                throw failure(MutationErrorCode.PATH_DENIED, logical, "links and reparse points are denied");
            }
        }
        return target;
    }

    private Path resolveAbsent(Access access, WorkspacePath logical) {
        if (logical.projectPath().isRoot()) {
            throw failure(MutationErrorCode.PATH_DENIED, logical, "workspace root cannot be mutated");
        }
        List<String> segments = logical.projectPath().segments();
        Path parent = access.root();
        for (int index = 0; index < segments.size() - 1; index++) {
            parent = parent.resolve(segments.get(index)).normalize();
            verifyContained(access, logical, parent);
            if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) || isLinkOrReparse(parent)) {
                throw failure(MutationErrorCode.PATH_DENIED, logical, "target parent is unavailable or unsafe");
            }
        }
        Path target = parent.resolve(segments.get(segments.size() - 1)).normalize();
        verifyContained(access, logical, target);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(MutationErrorCode.TARGET_EXISTS, logical, "logical target already exists");
        }
        return target;
    }

    private static void verifyContained(Access access, WorkspacePath logical, Path target) {
        if (!target.startsWith(access.root())) {
            throw failure(MutationErrorCode.PATH_DENIED, logical, "logical path escapes workspace root");
        }
    }

    private static FileVersion requireRegularVersion(Path target, WorkspacePath logical) {
        FileVersion value = version(target, logical);
        if (value.type() != FileType.FILE) {
            throw failure(MutationErrorCode.WRONG_FILE_TYPE, logical, "logical target is not a regular file");
        }
        return value;
    }

    private static FileVersion version(Path target, WorkspacePath logical) {
        try {
            BasicFileAttributes attributes =
                    Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (isLinkOrReparse(target)) {
                throw failure(MutationErrorCode.PATH_DENIED, logical, "links and reparse points are denied");
            }
            if (attributes.isRegularFile()) {
                if (attributes.size() > MAX_CONTENT_BYTES) {
                    throw failure(MutationErrorCode.CONTENT_TOO_LARGE, logical, "file exceeds mutation hash budget");
                }
                byte[] bytes = Files.readAllBytes(target);
                return new FileVersion(FileType.FILE, attributes.size(), "sha256:" + hash(bytes));
            }
            if (attributes.isDirectory()) {
                return new FileVersion(FileType.DIRECTORY, 0, "directory:empty");
            }
            throw failure(MutationErrorCode.WRONG_FILE_TYPE, logical, "unsupported logical target type");
        } catch (WorkspaceMutationException exception) {
            throw exception;
        } catch (IOException exception) {
            throw failure(MutationErrorCode.IO_FAILURE, logical, "unable to inspect logical target");
        }
    }

    private static void validateRevision(Workspace workspace, MutationPrecondition precondition, WorkspacePath path) {
        precondition.optionalWorkspaceRevision().ifPresent(expected -> {
            if (!expected.equals(workspace.revision())) {
                throw failure(MutationErrorCode.REVISION_CONFLICT, path, "workspace revision precondition failed");
            }
        });
    }

    private static void validateHash(FileVersion actual, MutationPrecondition precondition, WorkspacePath path) {
        String expected = precondition
                .optionalContentHash()
                .orElseThrow(() -> failure(
                        MutationErrorCode.PRECONDITION_REQUIRED, path, "content hash precondition is required"));
        if (!expected.equals(actual.contentHash())) {
            throw failure(MutationErrorCode.CONTENT_HASH_CONFLICT, path, "content hash precondition failed");
        }
    }

    private static boolean writeAtomically(
            Path target, byte[] content, boolean replace, WorkspacePath logical, Runnable beforeCommit) {
        Path parent = target.getParent();
        Path temporary = null;
        try {
            temporary = Files.createTempFile(parent, ".haifa-write-", ".tmp");
            try (FileChannel channel =
                    FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                channel.write(java.nio.ByteBuffer.wrap(content));
                channel.force(true);
            }
            beforeCommit.run();
            boolean atomic = true;
            try {
                if (replace) {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                }
            } catch (AtomicMoveNotSupportedException exception) {
                atomic = false;
                if (replace) Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                else Files.move(temporary, target);
            }
            return atomic;
        } catch (IOException exception) {
            throw failure(MutationErrorCode.IO_FAILURE, logical, "unable to commit guarded file write");
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // The reconciliation path can identify a residual managed temporary file.
                }
            }
        }
    }

    private static boolean movePath(
            Path source, Path destination, boolean replace, WorkspacePath logical, Runnable beforeCommit) {
        try {
            beforeCommit.run();
            try {
                if (replace) {
                    Files.move(
                            source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
                }
                return true;
            } catch (AtomicMoveNotSupportedException exception) {
                if (replace) Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
                else Files.move(source, destination);
                return false;
            }
        } catch (IOException exception) {
            throw failure(MutationErrorCode.IO_FAILURE, logical, "unable to commit guarded file move");
        }
    }

    private Path quarantineDirectory(Access access, WorkspacePath logical) {
        Path directory = access.root().resolve(QUARANTINE_DIRECTORY).normalize();
        verifyContained(access, logical, directory);
        try {
            Files.createDirectories(directory);
            if (isLinkOrReparse(directory)) {
                throw failure(MutationErrorCode.PATH_DENIED, logical, "managed quarantine is unsafe");
            }
            return directory;
        } catch (IOException exception) {
            throw failure(MutationErrorCode.IO_FAILURE, logical, "managed quarantine is unavailable");
        }
    }

    private FileChangeSet begin(Workspace workspace, MutationContext context) {
        return changeSetService.begin(
                workspace,
                context.operationId(),
                context.runRef(),
                context.toolCallRef(),
                context.actor(),
                context.securityDecisionRef());
    }

    private MutationResult complete(
            Workspace workspace, FileChangeSet pending, List<FileChange> changes, boolean atomic) {
        Instant now = time.now();
        WorkspaceRevision nextRevision = new WorkspaceRevision(
                workspace.revision().sequence() + 1,
                "sha256:"
                        + hash((workspace.revision().digest() + "|"
                                        + pending.id().value() + "|" + changes)
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Workspace advanced = workspace.advanceRevision(nextRevision, now);
        workspaces.save(advanced, workspace.version());
        FileChangeSet applied = pending.applied(nextRevision, changes, atomic, now);
        changeSets.save(applied, pending.version());
        return result(applied, false);
    }

    private MutationResult completeOrUnknown(
            Workspace workspace, FileChangeSet pending, List<FileChange> changes, boolean atomic) {
        try {
            return complete(workspace, pending, changes, atomic);
        } catch (RuntimeException finalizationFailure) {
            FileChangeSet unknown =
                    changeSetService.markUnknown(pending, changes, "physical post-state requires reconciliation");
            return result(unknown, false);
        }
    }

    private void fail(FileChangeSet pending, String detail) {
        FileChangeSet failed = pending.failed(detail, time.now());
        changeSets.save(failed, pending.version());
    }

    private Optional<MutationResult> replay(
            WorkspacePath path,
            String operationId,
            FileChangeType type,
            ProjectPath destination,
            String expectedAfterHash) {
        return changeSets.findByOperation(path.workspaceId(), operationId).map(existing -> {
            if (!existing.changes().isEmpty()) {
                FileChange first = existing.changes().get(0);
                if (first.type() != type
                        || !first.path().equals(path.projectPath())
                        || !Objects.equals(first.destination(), destination)
                        || (expectedAfterHash != null
                                && first.optionalAfter()
                                        .map(FileVersion::contentHash)
                                        .filter(expectedAfterHash::equals)
                                        .isEmpty())) {
                    throw failure(
                            MutationErrorCode.CONCURRENT_MODIFICATION,
                            path,
                            "operation id is already bound to a different mutation");
                }
            }
            return result(existing, true);
        });
    }

    private static void requireStillAbsent(Access access, WorkspacePath logical, Path expectedTarget) {
        Path current = resolveAbsentStatic(access, logical);
        if (!current.equals(expectedTarget)) {
            throw failure(MutationErrorCode.CONCURRENT_MODIFICATION, logical, "logical target changed before commit");
        }
    }

    private static Path resolveAbsentStatic(Access access, WorkspacePath logical) {
        if (logical.projectPath().isRoot()) {
            throw failure(MutationErrorCode.PATH_DENIED, logical, "workspace root cannot be mutated");
        }
        List<String> segments = logical.projectPath().segments();
        Path parent = access.root();
        for (int index = 0; index < segments.size() - 1; index++) {
            parent = parent.resolve(segments.get(index)).normalize();
            verifyContained(access, logical, parent);
            if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) || isLinkOrReparse(parent)) {
                throw failure(MutationErrorCode.PATH_DENIED, logical, "target parent changed before commit");
            }
        }
        Path target = parent.resolve(segments.get(segments.size() - 1)).normalize();
        verifyContained(access, logical, target);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(MutationErrorCode.CONCURRENT_MODIFICATION, logical, "logical target appeared before commit");
        }
        return target;
    }

    private static MutationResult result(FileChangeSet changeSet, boolean replayed) {
        return new MutationResult(
                changeSet.id(),
                changeSet.status(),
                changeSet.baseRevision(),
                changeSet.resultRevision(),
                changeSet.changes(),
                changeSet.atomic(),
                replayed);
    }

    private static void requireEmptyDirectory(Path directory, WorkspacePath logical) {
        try (var entries = Files.list(directory)) {
            if (entries.findAny().isPresent()) {
                throw failure(MutationErrorCode.WRONG_FILE_TYPE, logical, "only an empty directory may be deleted");
            }
        } catch (WorkspaceMutationException exception) {
            throw exception;
        } catch (IOException exception) {
            throw failure(MutationErrorCode.IO_FAILURE, logical, "unable to inspect logical directory");
        }
    }

    private static void ensureContentBudget(WorkspacePath path, byte[] content) {
        if (content.length > MAX_CONTENT_BYTES) {
            throw failure(MutationErrorCode.CONTENT_TOO_LARGE, path, "content exceeds mutation budget");
        }
    }

    private static boolean isLinkOrReparse(Path path) {
        if (Files.isSymbolicLink(path)) return true;
        try {
            BasicFileAttributes attributes =
                    Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isOther()) return true;
        } catch (IOException exception) {
            return true;
        }
        try {
            return Boolean.TRUE.equals(Files.getAttribute(path, "dos:reparsePoint", LinkOption.NOFOLLOW_LINKS));
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String safeToken(String value) {
        return hash(Objects.requireNonNull(value, "identifier value must not be null")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static WorkspaceMutationException failure(MutationErrorCode code, WorkspacePath path, String message) {
        return new WorkspaceMutationException(code, path, message);
    }

    private record Access(Workspace workspace, WorkspaceBinding binding, Path root) {}
}
