package io.haifa.agent.cli;

import io.haifa.agent.execution.core.manifest.WorkspaceManifestIgnorePolicy;
import io.haifa.agent.project.filesystem.FileMetadata;
import io.haifa.agent.project.filesystem.FileType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Frozen CLI policy for generated directories that should not enter execution manifests. */
final class CliWorkspaceManifestIgnorePolicy implements WorkspaceManifestIgnorePolicy {
    private static final long MAX_GITIGNORE_BYTES = 64 * 1024;
    private static final Set<String> STANDARD_DIRECTORY_NAMES = Set.of(
            ".git",
            ".idea",
            ".mypy_cache",
            ".pytest_cache",
            ".ruff_cache",
            ".tox",
            ".venv",
            ".vscode",
            "__pycache__",
            "node_modules",
            "target",
            "build",
            "dist");

    private final List<DirectoryRule> rules;
    private final String version;

    private CliWorkspaceManifestIgnorePolicy(List<DirectoryRule> rules) {
        this.rules = rules.stream()
                .distinct()
                .sorted(Comparator.comparing(DirectoryRule::identity))
                .toList();
        this.version = "cli-workspace-manifest-v3-sha256-"
                + sha256(this.rules.stream()
                                .map(DirectoryRule::identity)
                                .reduce("", (left, right) -> left + "\n" + right))
                        .substring(0, 16);
    }

    static CliWorkspaceManifestIgnorePolicy load(Path workspaceRoot) {
        Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null");
        List<DirectoryRule> rules = new ArrayList<>();
        STANDARD_DIRECTORY_NAMES.forEach(name -> rules.add(DirectoryRule.unanchored(name)));
        rules.addAll(
                readRootGitignore(workspaceRoot.toAbsolutePath().normalize().resolve(".gitignore")));
        return new CliWorkspaceManifestIgnorePolicy(rules);
    }

    @Override
    public String version() {
        return version;
    }

    @Override
    public boolean ignores(FileMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata must not be null");
        return ignores(metadata.path().projectPath(), metadata.type());
    }

    boolean ignores(io.haifa.agent.project.path.ProjectPath path, FileType type) {
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(type, "type must not be null");
        List<String> segments = path.segments().stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .toList();
        return rules.stream().anyMatch(rule -> rule.matches(segments, type));
    }

    private static List<DirectoryRule> readRootGitignore(Path file) {
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(file)
                    || Files.size(file) > MAX_GITIGNORE_BYTES) {
                return List.of();
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            List<DirectoryRule> ignored = lines.stream()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#") && !line.startsWith("!"))
                    .map(CliWorkspaceManifestIgnorePolicy::directoryRule)
                    .flatMap(java.util.Optional::stream)
                    .toList();
            List<DirectoryRule> reIncluded = lines.stream()
                    .map(String::strip)
                    .filter(line -> line.startsWith("!") && line.length() > 1)
                    .map(line -> line.substring(1))
                    .map(CliWorkspaceManifestIgnorePolicy::reIncludedPath)
                    .flatMap(java.util.Optional::stream)
                    .toList();
            return ignored.stream()
                    .filter(rule -> reIncluded.stream().noneMatch(rule::couldContain))
                    .toList();
        } catch (IOException | RuntimeException ignored) {
            return List.of();
        }
    }

    private static java.util.Optional<DirectoryRule> directoryRule(String line) {
        if (!line.endsWith("/") || line.indexOf('\\') >= 0 || containsGlob(line)) {
            return java.util.Optional.empty();
        }
        boolean anchored = line.startsWith("/");
        String value = line.substring(anchored ? 1 : 0, line.length() - 1);
        if (value.isBlank()) return java.util.Optional.empty();
        List<String> segments = List.of(value.split("/", -1)).stream()
                .map(segment -> segment.toLowerCase(Locale.ROOT))
                .toList();
        if (segments.stream().anyMatch(segment -> segment.isBlank() || segment.equals(".") || segment.equals(".."))) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new DirectoryRule(anchored || segments.size() > 1, segments));
    }

    private static java.util.Optional<DirectoryRule> reIncludedPath(String line) {
        return directoryRule(line.endsWith("/") ? line : line + "/");
    }

    private static boolean containsGlob(String value) {
        return value.indexOf('*') >= 0 || value.indexOf('?') >= 0 || value.indexOf('[') >= 0;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private record DirectoryRule(boolean anchored, List<String> segments) {
        private DirectoryRule {
            segments = List.copyOf(segments);
        }

        private static DirectoryRule unanchored(String segment) {
            return new DirectoryRule(false, List.of(segment.toLowerCase(Locale.ROOT)));
        }

        private boolean matches(List<String> path, FileType type) {
            if (anchored) {
                return startsWithDirectory(path, segments, type);
            }
            String expected = segments.getFirst();
            for (int index = 0; index < path.size(); index++) {
                if (path.get(index).equals(expected) && (index < path.size() - 1 || type == FileType.DIRECTORY)) {
                    return true;
                }
            }
            return false;
        }

        private String identity() {
            return (anchored ? "/" : "") + String.join("/", segments) + "/";
        }

        private boolean couldContain(DirectoryRule reIncluded) {
            if (anchored) {
                return startsWith(reIncluded.segments, segments);
            }
            if (segments.size() == 1) {
                return reIncluded.segments.contains(segments.getFirst());
            }
            for (int index = 0; index <= reIncluded.segments.size() - segments.size(); index++) {
                if (startsWith(reIncluded.segments.subList(index, reIncluded.segments.size()), segments)) return true;
            }
            return false;
        }

        private static boolean startsWithDirectory(List<String> path, List<String> prefix, FileType type) {
            if (path.size() < prefix.size()) return false;
            for (int index = 0; index < prefix.size(); index++) {
                if (!path.get(index).equals(prefix.get(index))) return false;
            }
            return path.size() > prefix.size() || type == FileType.DIRECTORY;
        }

        private static boolean startsWith(List<String> path, List<String> prefix) {
            if (path.size() < prefix.size()) return false;
            for (int index = 0; index < prefix.size(); index++) {
                if (!path.get(index).equals(prefix.get(index))) return false;
            }
            return true;
        }
    }
}
