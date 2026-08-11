package io.haifa.agent.execution.core.change;

import io.haifa.agent.project.changeset.FileChange;
import io.haifa.agent.project.changeset.FileChangeType;
import io.haifa.agent.project.changeset.FileVersion;
import io.haifa.agent.project.filesystem.FileType;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cross-platform execution observer that hashes the workspace once, then hashes only changed candidates. On hosts
 * where short-lived WatchService windows can omit events, a metadata-only index supplies missing candidates. Watch
 * overflow or invalidation deliberately falls back to a fresh full snapshot instead of guessing.
 */
public final class LocalIncrementalWorkspaceChangeObserver implements WorkspaceChangeObserver, AutoCloseable {
    private static final int MAX_ENTRIES = 1_000_000;
    private static final boolean NEEDS_METADATA_RECONCILIATION =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

    private final WorkspaceId workspaceId;
    private final Path root;
    private final WorkspaceChangeIgnorePolicy ignores;
    private final FileVersionResolver versions;
    private final boolean reconcileMetadata;
    private final Semaphore window = new Semaphore(1, true);
    private final Map<WatchKey, Path> watchedDirectories = new HashMap<>();
    private Map<ProjectPath, FileVersion> baseline = new LinkedHashMap<>();
    private Map<ProjectPath, FileMetadata> metadataBaseline = new LinkedHashMap<>();
    private WatchService watcher;
    private boolean initialized;

    public LocalIncrementalWorkspaceChangeObserver(
            WorkspaceId workspaceId, Path root, WorkspaceChangeIgnorePolicy ignores) {
        this(
                workspaceId,
                root,
                ignores,
                LocalIncrementalWorkspaceChangeObserver::fileVersion,
                NEEDS_METADATA_RECONCILIATION);
    }

    LocalIncrementalWorkspaceChangeObserver(
            WorkspaceId workspaceId, Path root, WorkspaceChangeIgnorePolicy ignores, FileVersionResolver versions) {
        this(workspaceId, root, ignores, versions, NEEDS_METADATA_RECONCILIATION);
    }

    LocalIncrementalWorkspaceChangeObserver(
            WorkspaceId workspaceId,
            Path root,
            WorkspaceChangeIgnorePolicy ignores,
            FileVersionResolver versions,
            boolean reconcileMetadata) {
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        this.root = normalizeRoot(root);
        this.ignores = Objects.requireNonNull(ignores, "ignores must not be null");
        this.versions = Objects.requireNonNull(versions, "versions must not be null");
        this.reconcileMetadata = reconcileMetadata;
    }

    @Override
    public WorkspaceChangeObservation begin(WorkspaceId workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        if (!this.workspaceId.equals(workspaceId)) {
            throw new IllegalArgumentException("WORKSPACE_OBSERVER_BINDING_MISMATCH: unexpected workspace");
        }
        try {
            window.acquire();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("workspace change observation was interrupted", exception);
        }
        try {
            ensureInitialized();
            absorb(drain(false));
            return new Observation();
        } catch (RuntimeException exception) {
            window.release();
            throw unavailable(exception);
        }
    }

    @Override
    public void close() {
        window.acquireUninterruptibly();
        try {
            reset();
        } finally {
            window.release();
        }
    }

    private final class Observation implements WorkspaceChangeObservation {
        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public List<FileChange> complete() {
            if (!closed.compareAndSet(false, true)) throw new IllegalStateException("observation already completed");
            Map<ProjectPath, FileVersion> before = new LinkedHashMap<>(baseline);
            try {
                return apply(drain(true), true);
            } catch (RuntimeException incrementalFailure) {
                try {
                    Map<ProjectPath, FileVersion> after = resynchronize();
                    baseline = after;
                    if (reconcileMetadata) metadataBaseline = metadataSnapshot(root);
                    return diff(before, after);
                } catch (RuntimeException resyncFailure) {
                    resyncFailure.addSuppressed(incrementalFailure);
                    throw WorkspaceChangeObserverException.resyncFailed(resyncFailure);
                }
            } finally {
                window.release();
            }
        }

        @Override
        public void cancel() {
            if (!closed.compareAndSet(false, true)) return;
            try {
                baseline = resynchronize();
                if (reconcileMetadata) metadataBaseline = metadataSnapshot(root);
            } catch (RuntimeException exception) {
                reset();
            } finally {
                window.release();
            }
        }
    }

    private void ensureInitialized() {
        if (initialized) return;
        try {
            watcher = root.getFileSystem().newWatchService();
            baseline = scan(root, true);
            if (reconcileMetadata) metadataBaseline = metadataSnapshot(root);
            initialized = true;
        } catch (IOException | RuntimeException exception) {
            reset();
            throw new IllegalStateException("workspace change observer is unavailable", exception);
        }
    }

    private Map<ProjectPath, FileVersion> resynchronize() {
        closeWatcher();
        watchedDirectories.clear();
        try {
            watcher = root.getFileSystem().newWatchService();
            return scan(root, true);
        } catch (IOException | RuntimeException exception) {
            reset();
            throw new IllegalStateException("workspace change observer resynchronization failed", exception);
        }
    }

    private void absorb(CandidateBatch batch) {
        apply(batch, false);
    }

    private List<FileChange> apply(CandidateBatch batch, boolean returnChanges) {
        if (batch.overflow()) {
            Map<ProjectPath, FileVersion> after = resynchronize();
            List<FileChange> changes = diff(baseline, after);
            baseline = after;
            if (reconcileMetadata) metadataBaseline = metadataSnapshot(root);
            return returnChanges ? changes : List.of();
        }

        Set<ProjectPath> candidates = batch.paths();
        Map<ProjectPath, FileMetadata> observedMetadata = null;
        if (reconcileMetadata) {
            observedMetadata = metadataSnapshot(root);
            candidates = new HashSet<>(candidates);
            candidates.addAll(metadataCandidates(metadataBaseline, observedMetadata));
        }
        if (candidates.isEmpty()) {
            if (observedMetadata != null) metadataBaseline = observedMetadata;
            return List.of();
        }

        Map<ProjectPath, FileVersion> before = new LinkedHashMap<>();
        Map<ProjectPath, FileVersion> after = new LinkedHashMap<>();
        for (ProjectPath candidate : compact(candidates)) {
            baseline.forEach((path, version) -> {
                if (sameOrDescendant(path, candidate)) before.put(path, version);
            });
            Path physical = resolve(candidate);
            if (Files.exists(physical, LinkOption.NOFOLLOW_LINKS)) {
                after.putAll(scan(physical, true));
            }
        }
        before.keySet().forEach(baseline::remove);
        baseline.putAll(after);
        List<FileChange> changes = diff(before, after);
        if (observedMetadata != null) metadataBaseline = observedMetadata;
        return returnChanges ? changes : List.of();
    }

    private Map<ProjectPath, FileMetadata> metadataSnapshot(Path start) {
        Map<ProjectPath, FileMetadata> values = new LinkedHashMap<>();
        try {
            Files.walkFileTree(start, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    ProjectPath logical = projectPath(directory);
                    if (!logical.isRoot() && ignores.ignores(logical, FileType.DIRECTORY)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!logical.isRoot()) addMetadata(values, logical, FileMetadata.directory());
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (attributes.isSymbolicLink()) return FileVisitResult.CONTINUE;
                    ProjectPath logical = projectPath(file);
                    FileType type = attributes.isRegularFile() ? FileType.FILE : FileType.OTHER;
                    if (!ignores.ignores(logical, type)) {
                        addMetadata(values, logical, FileMetadata.from(type, attributes));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            return values;
        } catch (IOException exception) {
            throw new IllegalStateException("workspace change metadata could not be inspected", exception);
        }
    }

    private static Set<ProjectPath> metadataCandidates(
            Map<ProjectPath, FileMetadata> before, Map<ProjectPath, FileMetadata> after) {
        Set<ProjectPath> candidates = new HashSet<>(before.keySet());
        candidates.addAll(after.keySet());
        candidates.removeIf(path -> Objects.equals(before.get(path), after.get(path)));
        return candidates;
    }

    private CandidateBatch drain(boolean settle) {
        Set<ProjectPath> paths = new HashSet<>();
        boolean overflow = false;
        int quietPolls = settle ? 3 : 0;
        long deadline = settle ? System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250) : Long.MAX_VALUE;
        try {
            while (true) {
                if (System.nanoTime() >= deadline) {
                    overflow = true;
                    break;
                }
                WatchKey key = quietPolls > 0 ? watcher.poll(20, TimeUnit.MILLISECONDS) : watcher.poll();
                if (key == null) {
                    if (quietPolls-- > 0) continue;
                    break;
                }
                quietPolls = settle ? 3 : 0;
                Path directory = watchedDirectories.get(key);
                if (directory == null) {
                    overflow = true;
                } else {
                    for (WatchEvent<?> event : key.pollEvents()) {
                        if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                            overflow = true;
                            continue;
                        }
                        Object context = event.context();
                        if (!(context instanceof Path relative)) {
                            overflow = true;
                            continue;
                        }
                        Path changed =
                                directory.resolve(relative).toAbsolutePath().normalize();
                        if (!changed.startsWith(root)) {
                            overflow = true;
                            continue;
                        }
                        paths.add(projectPath(changed));
                    }
                }
                if (!key.reset()) {
                    watchedDirectories.remove(key);
                    overflow = true;
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("workspace change observation was interrupted", exception);
        }
        return new CandidateBatch(Set.copyOf(paths), overflow);
    }

    private Map<ProjectPath, FileVersion> scan(Path start, boolean registerDirectories) {
        Map<ProjectPath, FileVersion> values = new LinkedHashMap<>();
        try {
            Files.walkFileTree(start, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                        throws IOException {
                    ProjectPath logical = projectPath(directory);
                    if (!logical.isRoot() && ignores.ignores(logical, FileType.DIRECTORY)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (registerDirectories) register(directory);
                    if (!logical.isRoot()) add(values, logical, directoryVersion());
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    if (attributes.isSymbolicLink()) return FileVisitResult.CONTINUE;
                    ProjectPath logical = projectPath(file);
                    FileType type = attributes.isRegularFile() ? FileType.FILE : FileType.OTHER;
                    if (!ignores.ignores(logical, type)) add(values, logical, versions.resolve(file, attributes));
                    return FileVisitResult.CONTINUE;
                }
            });
            return values;
        } catch (IOException exception) {
            throw new IllegalStateException("workspace change candidates could not be inspected", exception);
        }
    }

    private void register(Path directory) throws IOException {
        WatchKey key = directory.register(
                watcher,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE,
                StandardWatchEventKinds.ENTRY_MODIFY);
        watchedDirectories.put(key, directory.toAbsolutePath().normalize());
    }

    private static void add(Map<ProjectPath, FileVersion> values, ProjectPath path, FileVersion version) {
        if (values.size() >= MAX_ENTRIES) throw new IllegalStateException("workspace observer entry budget exceeded");
        values.put(path, version);
    }

    private static void addMetadata(Map<ProjectPath, FileMetadata> values, ProjectPath path, FileMetadata metadata) {
        if (values.size() >= MAX_ENTRIES) throw new IllegalStateException("workspace observer entry budget exceeded");
        values.put(path, metadata);
    }

    private static FileVersion fileVersion(Path file, BasicFileAttributes initial) throws IOException {
        if (!initial.isRegularFile())
            return new FileVersion(FileType.OTHER, initial.size(), "metadata:OTHER:" + initial.size());
        for (int attempt = 0; attempt < 2; attempt++) {
            BasicFileAttributes before =
                    Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            MessageDigest digest = sha256();
            try (var input = new DigestInputStream(new BufferedInputStream(Files.newInputStream(file)), digest)) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            BasicFileAttributes after =
                    Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (before.size() == after.size() && before.lastModifiedTime().equals(after.lastModifiedTime())) {
                return new FileVersion(
                        FileType.FILE, after.size(), "sha256:" + HexFormat.of().formatHex(digest.digest()));
            }
        }
        throw new IOException("file changed while it was being hashed");
    }

    private static FileVersion directoryVersion() {
        return new FileVersion(FileType.DIRECTORY, 0, "metadata:DIRECTORY:0");
    }

    static String stableFileKey(Object fileKey) {
        return fileKey == null ? null : fileKey.toString();
    }

    private Path resolve(ProjectPath path) {
        Path value = root;
        for (String segment : path.segments()) value = value.resolve(segment);
        value = value.toAbsolutePath().normalize();
        if (!value.startsWith(root)) throw new IllegalStateException("workspace path escaped its root");
        return value;
    }

    private ProjectPath projectPath(Path physical) {
        Path normalized = physical.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) throw new IllegalStateException("observed path escaped workspace root");
        Path relative = root.relativize(normalized);
        if (relative.getNameCount() == 0) return ProjectPath.root();
        List<String> segments = new ArrayList<>();
        relative.forEach(value -> segments.add(value.toString()));
        return ProjectPath.of(String.join("/", segments));
    }

    private static Set<ProjectPath> compact(Set<ProjectPath> paths) {
        List<ProjectPath> ordered = paths.stream()
                .sorted(Comparator.comparingInt(path -> path.segments().size()))
                .toList();
        Set<ProjectPath> result = new java.util.LinkedHashSet<>();
        for (ProjectPath path : ordered) {
            if (result.stream().noneMatch(parent -> sameOrDescendant(path, parent))) result.add(path);
        }
        return result;
    }

    private static boolean sameOrDescendant(ProjectPath path, ProjectPath parent) {
        List<String> value = path.segments();
        List<String> prefix = parent.segments();
        if (value.size() < prefix.size()) return false;
        for (int index = 0; index < prefix.size(); index++) {
            if (!value.get(index).equals(prefix.get(index))) return false;
        }
        return true;
    }

    private static List<FileChange> diff(Map<ProjectPath, FileVersion> before, Map<ProjectPath, FileVersion> after) {
        Set<ProjectPath> removed = new HashSet<>(before.keySet());
        removed.removeAll(after.keySet());
        Set<ProjectPath> added = new HashSet<>(after.keySet());
        added.removeAll(before.keySet());
        List<FileChange> changes = new ArrayList<>();
        before.forEach((path, oldVersion) -> {
            FileVersion newVersion = after.get(path);
            if (newVersion != null && !oldVersion.equals(newVersion)) {
                changes.add(new FileChange(FileChangeType.REPLACE, path, null, oldVersion, newVersion));
            }
        });

        Map<FileVersion, List<ProjectPath>> addedByVersion = new HashMap<>();
        added.stream().filter(path -> after.get(path).type() == FileType.FILE).forEach(path -> addedByVersion
                .computeIfAbsent(after.get(path), ignored -> new ArrayList<>())
                .add(path));
        for (ProjectPath oldPath : new ArrayList<>(removed)) {
            FileVersion oldVersion = before.get(oldPath);
            List<ProjectPath> destinations =
                    oldVersion.type() == FileType.FILE ? addedByVersion.getOrDefault(oldVersion, List.of()) : List.of();
            if (destinations.size() == 1) {
                ProjectPath destination = destinations.getFirst();
                changes.add(
                        new FileChange(FileChangeType.MOVE, oldPath, destination, oldVersion, after.get(destination)));
                removed.remove(oldPath);
                added.remove(destination);
                addedByVersion.remove(oldVersion);
            }
        }
        removed.forEach(path -> changes.add(new FileChange(FileChangeType.DELETE, path, null, before.get(path), null)));
        added.forEach(path -> changes.add(new FileChange(FileChangeType.CREATE, path, null, null, after.get(path))));
        return changes.stream()
                .sorted(Comparator.comparing(change -> change.path().value()))
                .toList();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static Path normalizeRoot(Path root) {
        Objects.requireNonNull(root, "root must not be null");
        try {
            Path normalized = root.toRealPath();
            if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
                throw WorkspaceChangeObserverException.unavailable(
                        new IllegalArgumentException("workspace root is not a directory"));
            }
            return normalized;
        } catch (IOException exception) {
            throw WorkspaceChangeObserverException.unavailable(exception);
        }
    }

    private static WorkspaceChangeObserverException unavailable(RuntimeException exception) {
        return exception instanceof WorkspaceChangeObserverException observerFailure
                        && observerFailure.code().equals(WorkspaceChangeObserverException.UNAVAILABLE)
                ? observerFailure
                : WorkspaceChangeObserverException.unavailable(exception);
    }

    private void reset() {
        initialized = false;
        baseline = new LinkedHashMap<>();
        metadataBaseline = new LinkedHashMap<>();
        watchedDirectories.clear();
        closeWatcher();
    }

    private void closeWatcher() {
        if (watcher != null) {
            try {
                watcher.close();
            } catch (IOException ignored) {
                // Best-effort close during recovery.
            }
            watcher = null;
        }
    }

    private record CandidateBatch(Set<ProjectPath> paths, boolean overflow) {}

    private record FileMetadata(FileType type, long size, FileTime modifiedAt, String fileKey) {
        private static FileMetadata directory() {
            return new FileMetadata(FileType.DIRECTORY, 0, null, null);
        }

        private static FileMetadata from(FileType type, BasicFileAttributes attributes) {
            return new FileMetadata(
                    type, attributes.size(), attributes.lastModifiedTime(), stableFileKey(attributes.fileKey()));
        }
    }

    @FunctionalInterface
    interface FileVersionResolver {
        FileVersion resolve(Path file, BasicFileAttributes attributes) throws IOException;
    }
}
