package io.haifa.agent.sandbox.host;

import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.project.binding.WorkspaceBinding;
import io.haifa.agent.project.binding.WorkspaceBindingMode;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.provider.local.LocalWorkspaceLocationStore;
import io.haifa.agent.project.provider.local.SensitivePathPolicy;
import io.haifa.agent.project.store.WorkspaceBindingStore;
import io.haifa.agent.project.store.WorkspaceStore;
import io.haifa.agent.project.workspace.Workspace;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.project.workspace.WorkspacePurpose;
import io.haifa.agent.project.workspace.WorkspaceRevision;
import io.haifa.agent.project.workspace.WorkspaceRoot;
import io.haifa.agent.sandbox.api.EphemeralCopyRequest;
import io.haifa.agent.sandbox.api.IsolatedWorkspace;
import io.haifa.agent.sandbox.api.WorkspaceIsolationProvider;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class HostWorkspaceIsolationProvider implements WorkspaceIsolationProvider {
    private final WorkspaceStore workspaces;
    private final WorkspaceBindingStore bindings;
    private final LocalWorkspaceLocationStore locations;
    private final SensitivePathPolicy sensitivePaths;
    private final Path controlledBase;
    private final TimeProvider time;
    private final ConcurrentHashMap<WorkspaceId, OwnedCopy> owned = new ConcurrentHashMap<>();

    public HostWorkspaceIsolationProvider(
            WorkspaceStore workspaces,
            WorkspaceBindingStore bindings,
            LocalWorkspaceLocationStore locations,
            SensitivePathPolicy sensitivePaths,
            Path controlledBase,
            TimeProvider time) {
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces must not be null");
        this.bindings = Objects.requireNonNull(bindings, "bindings must not be null");
        this.locations = Objects.requireNonNull(locations, "locations must not be null");
        this.sensitivePaths = Objects.requireNonNull(sensitivePaths, "sensitivePaths must not be null");
        this.controlledBase = Objects.requireNonNull(controlledBase, "controlledBase must not be null")
                .toAbsolutePath()
                .normalize();
        this.time = Objects.requireNonNull(time, "time must not be null");
    }

    @Override
    public IsolatedWorkspace createEphemeralCopy(EphemeralCopyRequest request) {
        Workspace parent =
                workspaces.find(request.parentWorkspaceId()).orElseThrow(() -> failure("parent workspace not found"));
        var parentBinding =
                bindings.find(parent.root().bindingId()).orElseThrow(() -> failure("parent binding not found"));
        if (!parentBinding
                        .capabilities()
                        .values()
                        .containsAll(request.narrowedCapabilities().values())
                || !parentBinding
                        .permissions()
                        .values()
                        .containsAll(request.narrowedPermissions().values())) {
            throw failure("child workspace authority must be narrowed from parent");
        }
        try {
            Files.createDirectories(controlledBase);
            Path base = controlledBase.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (isLink(base)) throw failure("controlled copy base is unsafe");
            Path source = locations
                    .resolveForTrustedProvider(parentBinding.locationRef())
                    .toRealPath(LinkOption.NOFOLLOW_LINKS);
            Path target = base.resolve(
                            "copy-" + safeName(request.childWorkspaceId().value()))
                    .normalize();
            if (!target.startsWith(base) || Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw failure("child copy target is unavailable");
            }
            Files.createDirectory(target);
            CopyCounter counter = new CopyCounter(
                    System.nanoTime() + request.budget().timeout().toNanos());
            try {
                copyTree(source, target, request, counter);
                locations.register(request.childLocationRef(), target);
                Instant now = time.now();
                WorkspaceBinding binding = WorkspaceBinding.provision(
                                request.childBindingId(),
                                request.childLocationRef(),
                                WorkspaceBindingMode.EPHEMERAL_COPY,
                                request.owner(),
                                request.narrowedCapabilities(),
                                request.narrowedPermissions(),
                                LocalWorkspaceLocationStore.fingerprintFor(target),
                                now)
                        .activate(now);
                bindings.create(binding);
                Workspace child = Workspace.provision(
                                request.childWorkspaceId(),
                                parent.projectId(),
                                WorkspacePurpose.TEMPORARY,
                                new WorkspaceRoot(
                                        ProjectPath.root(),
                                        request.childBindingId(),
                                        parent.root().semanticsId()),
                                WorkspaceRevision.initial(
                                        "copy-of:" + parent.revision().digest()),
                                now)
                        .activate(now);
                workspaces.create(child);
                OwnedCopy ownership = new OwnedCopy(target, request.childLocationRef(), request.childBindingId());
                if (owned.putIfAbsent(child.id(), ownership) != null) throw failure("child workspace is already owned");
                return new IsolatedWorkspace(
                        parent.id(),
                        child.id(),
                        binding.id(),
                        binding.locationRef(),
                        WorkspaceBindingMode.EPHEMERAL_COPY,
                        parent.revision(),
                        now);
            } catch (RuntimeException | IOException exception) {
                safeDelete(target, base);
                throw exception instanceof HostSandboxException host ? host : failure("ephemeral copy failed safely");
            }
        } catch (IOException exception) {
            throw failure("ephemeral copy storage is unavailable");
        }
    }

    @Override
    public void release(WorkspaceId childWorkspaceId) {
        OwnedCopy copy = owned.remove(childWorkspaceId);
        if (copy == null) throw failure("child workspace is not owned by this provider");
        Workspace workspace = workspaces.find(childWorkspaceId).orElseThrow(() -> failure("child workspace not found"));
        var binding = bindings.find(copy.bindingId()).orElseThrow(() -> failure("child binding not found"));
        Instant now = time.now();
        Workspace releasingWorkspace = workspace.beginRelease(now);
        workspaces.save(releasingWorkspace, workspace.version());
        var releasingBinding = binding.beginRelease(now);
        bindings.save(releasingBinding, binding.version());
        try {
            Path base = controlledBase.toRealPath(LinkOption.NOFOLLOW_LINKS);
            safeDelete(copy.path(), base);
            locations.unregisterForTrustedProvider(copy.locationRef(), copy.path());
            bindings.save(releasingBinding.released(now), releasingBinding.version());
            workspaces.save(releasingWorkspace.released(now), releasingWorkspace.version());
        } catch (IOException | RuntimeException exception) {
            owned.putIfAbsent(childWorkspaceId, copy);
            throw failure("child workspace release requires reconciliation");
        }
    }

    private void copyTree(Path source, Path target, EphemeralCopyRequest request, CopyCounter counter)
            throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                guard(source, directory, attributes, request, counter);
                ProjectPath logical = logical(source, directory);
                if (!logical.isRoot() && !sensitivePaths.mayRead(logical)) return FileVisitResult.SKIP_SUBTREE;
                Path destination =
                        target.resolve(source.relativize(directory).toString()).normalize();
                if (!destination.startsWith(target)) throw failure("copy path escaped target");
                if (!destination.equals(target)) Files.createDirectory(destination);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                guard(source, file, attributes, request, counter);
                ProjectPath logical = logical(source, file);
                if (!sensitivePaths.mayRead(logical)) return FileVisitResult.CONTINUE;
                if (!attributes.isRegularFile()) throw failure("special files are unsupported in ephemeral copy");
                if (attributes.size() > request.budget().maxFileBytes()) throw failure("copy file budget exceeded");
                counter.totalBytes += attributes.size();
                if (counter.totalBytes > request.budget().maxTotalBytes())
                    throw failure("copy total byte budget exceeded");
                Path destination =
                        target.resolve(source.relativize(file).toString()).normalize();
                if (!destination.startsWith(target)) throw failure("copy path escaped target");
                Files.copy(file, destination, StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void guard(
            Path source,
            Path current,
            BasicFileAttributes attributes,
            EphemeralCopyRequest request,
            CopyCounter counter) {
        if (System.nanoTime() > counter.deadlineNanos) throw failure("copy timeout exceeded");
        if (++counter.files > request.budget().maxFiles()) throw failure("copy file budget exceeded");
        if (!current.normalize().startsWith(source)
                || attributes.isSymbolicLink()
                || attributes.isOther()
                || isLink(current)) {
            throw failure("links, reparse points and special files are denied");
        }
    }

    private static ProjectPath logical(Path source, Path current) {
        String value = source.relativize(current)
                .toString()
                .replace(current.getFileSystem().getSeparator(), "/");
        return ProjectPath.of(value);
    }

    private static void safeDelete(Path target, Path controlledBase) {
        Path normalized = target.toAbsolutePath().normalize();
        if (!normalized.startsWith(controlledBase) || normalized.equals(controlledBase)) {
            throw failure("refusing to delete outside controlled copy base");
        }
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(normalized)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException exception) {
            throw failure("controlled copy cleanup failed");
        }
    }

    private static boolean isLink(Path path) {
        if (Files.isSymbolicLink(path)) return true;
        try {
            return Boolean.TRUE.equals(Files.getAttribute(path, "dos:reparsePoint", LinkOption.NOFOLLOW_LINKS));
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String safeName(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static HostSandboxException failure(String message) {
        return new HostSandboxException("ISOLATION_FAILURE", message);
    }

    private record OwnedCopy(
            Path path,
            io.haifa.agent.project.binding.WorkspaceLocationRef locationRef,
            io.haifa.agent.project.binding.WorkspaceBindingId bindingId) {}

    private static final class CopyCounter {
        private final long deadlineNanos;
        private int files;
        private long totalBytes;

        private CopyCounter(long deadlineNanos) {
            this.deadlineNanos = deadlineNanos;
        }
    }
}
