package io.haifa.agent.cli;

import io.haifa.agent.project.hostworkspace.SensitivePathPolicy;
import io.haifa.agent.project.path.ProjectPath;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Bounded, redaction-aware logical file discovery for interactive {@code @file} completion. */
final class LocalWorkspacePathCatalog {
    private static final int MAX_DEPTH = 16;
    private static final int MAX_VISITED_FILES = 20_000;
    private static final int MAX_RESULTS = 2_000;
    private static final Set<String> SKIPPED_DIRECTORIES =
            Set.of(".git", ".idea", ".vscode", "node_modules", "target", "build", "dist");

    private final Path root;
    private final SensitivePathPolicy sensitivePaths;

    LocalWorkspacePathCatalog(Path root) {
        this(root, SensitivePathPolicy.defaults());
    }

    LocalWorkspacePathCatalog(Path root, SensitivePathPolicy sensitivePaths) {
        this.root = root.toAbsolutePath().normalize();
        this.sensitivePaths = java.util.Objects.requireNonNull(sensitivePaths, "sensitivePaths must not be null");
    }

    List<String> list() {
        List<String> results = new ArrayList<>();
        int[] visitedFiles = {0};
        try {
            Files.walkFileTree(
                    root,
                    java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class),
                    MAX_DEPTH,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                            if (directory.equals(root)) {
                                return FileVisitResult.CONTINUE;
                            }
                            if (attributes.isSymbolicLink()) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            ProjectPath logical = logical(directory);
                            String name = directory.getFileName().toString().toLowerCase(Locale.ROOT);
                            if (name.startsWith(".")
                                    || SKIPPED_DIRECTORIES.contains(name)
                                    || !sensitivePaths.mayRead(logical)) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            results.add(logical.value() + "/");
                            return results.size() >= MAX_RESULTS ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                            if (++visitedFiles[0] > MAX_VISITED_FILES) {
                                return FileVisitResult.TERMINATE;
                            }
                            if (attributes.isRegularFile()
                                    && !Files.isSymbolicLink(file)
                                    && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                                ProjectPath logical = logical(file);
                                if (!file.getFileName().toString().startsWith(".") && sensitivePaths.mayRead(logical)) {
                                    results.add(logical.value());
                                }
                            }
                            return results.size() >= MAX_RESULTS ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException | IllegalArgumentException ignored) {
            // Completion is best-effort and must not make the editor unavailable.
        }
        return results.stream().sorted().toList();
    }

    private ProjectPath logical(Path path) {
        return ProjectPath.of(root.relativize(path.toAbsolutePath().normalize()).toString());
    }
}
