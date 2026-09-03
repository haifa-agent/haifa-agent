package io.haifa.agent.project.hostworkspace;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.project.binding.WorkspaceBinding;
import io.haifa.agent.project.binding.WorkspaceBindingMode;
import io.haifa.agent.project.binding.WorkspaceBindingStatus;
import io.haifa.agent.project.changeset.FileChange;
import io.haifa.agent.project.changeset.FileChangeType;
import io.haifa.agent.project.changeset.FileVersion;
import io.haifa.agent.project.filesystem.FileType;
import io.haifa.agent.project.mutation.CreateFileRequest;
import io.haifa.agent.project.mutation.DeleteFileRequest;
import io.haifa.agent.project.mutation.MoveFileRequest;
import io.haifa.agent.project.mutation.MutationErrorCode;
import io.haifa.agent.project.mutation.MutationPrecondition;
import io.haifa.agent.project.mutation.MutationResult;
import io.haifa.agent.project.mutation.WorkspaceMutationCapabilities;
import io.haifa.agent.project.mutation.WorkspaceMutationException;
import io.haifa.agent.project.mutation.WorkspaceMutationProvider;
import io.haifa.agent.project.mutation.WorkspaceWriteLease;
import io.haifa.agent.project.mutation.WorkspaceWriteLeaseManager;
import io.haifa.agent.project.patch.PatchFileMutationRequest;
import io.haifa.agent.project.patch.PatchTransformException;
import io.haifa.agent.project.patch.StreamingPatchMutationService;
import io.haifa.agent.project.path.WorkspacePath;
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
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

public final class HostWorkspaceMutationService implements WorkspaceMutationProvider, StreamingPatchMutationService {
    private static final int MAX_CONTENT_BYTES = 16 * 1024 * 1024;

    private final WorkspaceStore workspaces;
    private final WorkspaceBindingStore bindings;
    private final HostWorkspaceLocationStore locations;
    private final SensitivePathPolicy sensitivePaths;
    private final WorkspaceWriteLeaseManager leases;
    private final IdentifierGenerator identifiers;
    private final TimeProvider time;

    public HostWorkspaceMutationService(
            WorkspaceStore workspaces,
            WorkspaceBindingStore bindings,
            HostWorkspaceLocationStore locations,
            SensitivePathPolicy sensitivePaths,
            WorkspaceWriteLeaseManager leases,
            IdentifierGenerator identifiers,
            TimeProvider time) {
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces must not be null");
        this.bindings = Objects.requireNonNull(bindings, "bindings must not be null");
        this.locations = Objects.requireNonNull(locations, "locations must not be null");
        this.sensitivePaths = Objects.requireNonNull(sensitivePaths, "sensitivePaths must not be null");
        this.leases = Objects.requireNonNull(leases, "leases must not be null");
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
        try (WorkspaceWriteLease ignored =
                leases.acquire(request.path().workspaceId(), request.context().operationId())) {
            Access access = access(request.path(), WorkspacePermission.WRITE);
            validateRevision(access.workspace(), request.precondition(), request.path());
            Path target = resolveAbsent(access, request.path());
            boolean atomic = writeAtomically(
                    target,
                    request.content(),
                    false,
                    request.path(),
                    () -> requireStillAbsent(access, request.path(), target));
            FileVersion after = version(target, request.path());
            return advanceWorkspaceRevision(
                    access.workspace(),
                    List.of(new FileChange(FileChangeType.CREATE, request.path().projectPath(), null, null, after)),
                    atomic);
        }
    }

    @Override
    public MutationResult write(io.haifa.agent.project.mutation.WriteFileRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        ensureContentBudget(request.path(), request.content());
        access(request.path(), WorkspacePermission.WRITE);
        try (WorkspaceWriteLease ignored =
                leases.acquire(request.path().workspaceId(), request.context().operationId())) {
            Access access = access(request.path(), WorkspacePermission.WRITE);
            validateRevision(access.workspace(), request.precondition(), request.path());
            Path target = resolveExisting(access, request.path());
            FileVersion before = requireRegularVersion(target, request.path());
            validateHash(before, request.precondition(), request.path());
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
            return advanceWorkspaceRevision(
                    access.workspace(),
                    List.of(new FileChange(
                            FileChangeType.REPLACE, request.path().projectPath(), null, before, after)),
                    atomic);
        }
    }

    @Override
    public MutationResult patch(PatchFileMutationRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        access(request.path(), WorkspacePermission.WRITE);
        try (WorkspaceWriteLease ignored =
                leases.acquire(request.path().workspaceId(), request.context().operationId())) {
            Access access = access(request.path(), WorkspacePermission.WRITE);
            validateRevision(access.workspace(), request.precondition(), request.path());
            Path target = resolveExisting(access, request.path());
            FileVersion before = requireRegularVersion(target, request.path());
            validateHash(before, request.precondition(), request.path());
            Path temporary = null;
            try {
                temporary = Files.createTempFile(target.getParent(), ".haifa-patch-", ".tmp");
                MessageDigest sourceDigest = sha256Digest();
                try (var input = new DigestInputStream(
                                Files.newInputStream(target, StandardOpenOption.READ), sourceDigest);
                        var output = Files.newOutputStream(
                                temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    new HostStreamingPatchTransformer()
                            .transform(request.patch(), input, output, request.maxOutputBytes());
                }
                String streamedSourceHash = "sha256:" + HexFormat.of().formatHex(sourceDigest.digest());
                if (!streamedSourceHash.equals(
                        request.precondition().optionalContentHash().orElseThrow())) {
                    throw failure(
                            MutationErrorCode.CONTENT_HASH_CONFLICT,
                            request.path(),
                            "source changed while the patch was being transformed");
                }
                try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                    channel.force(true);
                }
                validateHash(
                        requireRegularVersion(resolveExisting(access, request.path()), request.path()),
                        request.precondition(),
                        request.path());
                boolean atomic = replacePreparedFile(temporary, target, request.path());
                temporary = null;
                FileVersion after = requireRegularVersion(resolveExisting(access, request.path()), request.path());
                return advanceWorkspaceRevision(
                        access.workspace(),
                        List.of(new FileChange(
                                FileChangeType.REPLACE, request.path().projectPath(), null, before, after)),
                        atomic);
            } catch (PatchTransformException exception) {
                throw exception;
            } catch (WorkspaceMutationException exception) {
                throw exception;
            } catch (IOException exception) {
                throw failure(MutationErrorCode.IO_FAILURE, request.path(), "unable to apply streaming patch");
            } finally {
                if (temporary != null) {
                    try {
                        Files.deleteIfExists(temporary);
                    } catch (IOException ignoredDelete) {
                        // A managed temporary file is harmless.
                    }
                }
            }
        }
    }

    @Override
    public MutationResult delete(DeleteFileRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        access(request.path(), WorkspacePermission.DELETE);
        try (WorkspaceWriteLease ignored =
                leases.acquire(request.path().workspaceId(), request.context().operationId())) {
            Access access = access(request.path(), WorkspacePermission.DELETE);
            validateRevision(access.workspace(), request.precondition(), request.path());
            if (request.path().projectPath().isRoot()) {
                throw failure(MutationErrorCode.PATH_DENIED, request.path(), "cannot delete workspace root");
            }
            Path source = resolveExisting(access, request.path());
            FileVersion before = version(source, request.path());
            validateHash(before, request.precondition(), request.path());
            if (before.type() == FileType.DIRECTORY) {
                deleteDirectory(source, request.path());
            } else {
                try {
                    Files.delete(source);
                } catch (IOException exception) {
                    throw failure(
                            MutationErrorCode.IO_FAILURE,
                            request.path(),
                            "failed to delete file: " + exception.getMessage());
                }
            }
            return advanceWorkspaceRevision(
                    access.workspace(),
                    List.of(new FileChange(FileChangeType.DELETE, request.path().projectPath(), null, before, null)),
                    true);
        }
    }

    @Override
    public MutationResult move(MoveFileRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        access(request.source(), WorkspacePermission.WRITE);
        access(request.destination(), WorkspacePermission.WRITE);
        try (WorkspaceWriteLease ignored =
                leases.acquire(request.source().workspaceId(), request.context().operationId())) {
            Access access = access(request.source(), WorkspacePermission.WRITE);
            access(request.destination(), WorkspacePermission.WRITE);
            validateRevision(access.workspace(), request.sourcePrecondition(), request.source());
            Path source = resolveExisting(access, request.source());
            Path destination = resolveAbsent(access, request.destination());
            FileVersion before = version(source, request.source());
            validateHash(before, request.sourcePrecondition(), request.source());
            boolean atomic = movePath(source, destination, false, request.source(), () -> {
                validateHash(
                        version(resolveExisting(access, request.source()), request.source()),
                        request.sourcePrecondition(),
                        request.source());
                requireStillAbsent(access, request.destination(), destination);
            });
            FileVersion after = version(resolveExisting(access, request.destination()), request.destination());
            return advanceWorkspaceRevision(
                    access.workspace(),
                    List.of(new FileChange(
                            FileChangeType.MOVE,
                            request.source().projectPath(),
                            request.destination().projectPath(),
                            before,
                            after)),
                    atomic);
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
            if (!HostWorkspaceLocationStore.fingerprintFor(root).equals(binding.rootFingerprint())
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
                for (int attempt = 0; attempt < 2; attempt++) {
                    String contentHash = "sha256:" + hashFile(target);
                    BasicFileAttributes after =
                            Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (attributes.size() == after.size()
                            && attributes.lastModifiedTime().equals(after.lastModifiedTime())
                            && Objects.equals(attributes.fileKey(), after.fileKey())) {
                        return new FileVersion(FileType.FILE, after.size(), contentHash);
                    }
                    attributes = after;
                }
                throw failure(
                        MutationErrorCode.CONCURRENT_MODIFICATION,
                        logical,
                        "logical target changed while it was being inspected");
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

    private static void deleteDirectory(Path directory, WorkspacePath logical) {
        List<Path> paths;
        try (var walk = Files.walk(directory)) {
            paths = walk.toList();
        } catch (IOException exception) {
            throw failure(MutationErrorCode.IO_FAILURE, logical, "unable to inspect directory for deletion");
        }
        if (paths.size() > 1) {
            throw failure(
                    MutationErrorCode.PATH_DENIED,
                    logical,
                    "directory is not empty; recursive deletion is not supported");
        }
        if (paths.stream().anyMatch(HostWorkspaceMutationService::isLinkOrReparse)) {
            throw failure(MutationErrorCode.PATH_DENIED, logical, "directory contains links or reparse points");
        }
        try {
            Files.delete(directory);
        } catch (IOException exception) {
            throw failure(
                    MutationErrorCode.IO_FAILURE,
                    logical,
                    "failed to delete empty directory: " + exception.getMessage());
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
                    // Ignored.
                }
            }
        }
    }

    private static boolean replacePreparedFile(Path temporary, Path target, WorkspacePath logical) {
        try {
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                return false;
            }
        } catch (IOException exception) {
            throw failure(MutationErrorCode.IO_FAILURE, logical, "unable to commit guarded patch");
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

    private MutationResult advanceWorkspaceRevision(Workspace workspace, List<FileChange> changes, boolean atomic) {
        Instant now = time.now();
        WorkspaceRevision before = workspace.revision();
        WorkspaceRevision nextRevision = new WorkspaceRevision(
                before.sequence() + 1,
                "sha256:" + hash((before.digest() + "|" + changes).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Workspace advanced = workspace.advanceRevision(nextRevision, now);
        workspaces.save(advanced, workspace.version());
        return new MutationResult(before, nextRevision, changes, atomic, false);
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

    private static void ensureContentBudget(WorkspacePath path, byte[] content) {
        if (content.length > MAX_CONTENT_BYTES) {
            throw failure(MutationErrorCode.CONTENT_TOO_LARGE, path, "content exceeds mutation budget");
        }
    }

    private static boolean isLinkOrReparse(Path path) {
        return HostWorkspacePathSafety.isUnsafeNode(path);
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static String hashFile(Path path) throws IOException {
        MessageDigest digest = sha256Digest();
        try (var input = Files.newInputStream(path, StandardOpenOption.READ)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static WorkspaceMutationException failure(MutationErrorCode code, WorkspacePath path, String message) {
        return new WorkspaceMutationException(code, path, message);
    }

    private record Access(Workspace workspace, WorkspaceBinding binding, Path root) {}
}
