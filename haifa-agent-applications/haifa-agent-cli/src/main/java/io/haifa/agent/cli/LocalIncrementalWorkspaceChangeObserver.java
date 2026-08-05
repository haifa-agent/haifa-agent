package io.haifa.agent.cli;

import io.haifa.agent.execution.core.change.WorkspaceChangeObservation;
import io.haifa.agent.execution.core.change.WorkspaceChangeObserver;
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
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Cross-platform execution observer that hashes the workspace once, then hashes only WatchService candidates.
 * Watch overflow or invalidation deliberately falls back to a fresh full snapshot instead of guessing.
 */
final class LocalIncrementalWorkspaceChangeObserver implements WorkspaceChangeObserver, AutoCloseable {
    private static final int MAX_ENTRIES = 1_000_000;

    private final Path root;
    private final CliWorkspaceManifestIgnorePolicy ignores;
    private final ReentrantLock window = new ReentrantLock();
    private final Map<WatchKey, Path> watchedDirectories = new HashMap<>();
    private Map<ProjectPath, FileVersion> baseline = new LinkedHashMap<>();
    private WatchService watcher;
    private boolean initialized;

    LocalIncrementalWorkspaceChangeObserver(Path root, CliWorkspaceManifestIgnorePolicy ignores) {
        this.root = Objects.requireNonNull(root, "root must not be null")
                .toAbsolutePath()
                .normalize();
        this.ignores = Objects.requireNonNull(ignores, "ignores must not be null");
    }

    @Override
    public WorkspaceChangeObservation begin(WorkspaceId workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        window.lock();
        try {
            ensureInitialized();
            absorb(drain(false));
            return new Observation();
        } catch (RuntimeException exception) {
            window.unlock();
            throw exception;
        }
    }

    @Override
    public void close() {
        window.lock();
        try {
            reset();
        } finally {
            window.unlock();
        }
    }

    private final class Observation implements WorkspaceChangeObservation {
        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public List<FileChange> complete() {
            if (!closed.compareAndSet(false, true)) throw new IllegalStateException("observation already completed");
            try {
                return apply(drain(true), true);
            } finally {
                window.unlock();
            }
        }

        @Override
        public void cancel() {
            if (!closed.compareAndSet(false, true)) return;
            try {
                absorb(drain(false));
            } catch (RuntimeException exception) {
                reset();
            } finally {
                window.unlock();
            }
        }
    }

    private void ensureInitialized() {
        if (initialized) return;
        try {
            watcher = root.getFileSystem().newWatchService();
            baseline = scan(root, true);
            initialized = true;
        } catch (IOException exception) {
            reset();
            throw new IllegalStateException("workspace change observer is unavailable", exception);
        }
    }

    private void absorb(CandidateBatch batch) {
        apply(batch, false);
    }

    private List<FileChange> apply(CandidateBatch batch, boolean returnChanges) {
        if (batch.overflow()) {
            Map<ProjectPath, FileVersion> after = scan(root, true);
            List<FileChange> changes = diff(baseline, after);
            baseline = after;
            return returnChanges ? changes : List.of();
        }
        if (batch.paths().isEmpty()) return List.of();

        Map<ProjectPath, FileVersion> before = new LinkedHashMap<>();
        Map<ProjectPath, FileVersion> after = new LinkedHashMap<>();
        for (ProjectPath candidate : compact(batch.paths())) {
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
        return returnChanges ? changes : List.of();
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
                if (!key.reset()) watchedDirectories.remove(key);
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
                    if (!ignores.ignores(logical, type)) add(values, logical, version(file, attributes));
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

    private FileVersion version(Path file, BasicFileAttributes initial) throws IOException {
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

    private void reset() {
        initialized = false;
        baseline = new LinkedHashMap<>();
        watchedDirectories.clear();
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
}
