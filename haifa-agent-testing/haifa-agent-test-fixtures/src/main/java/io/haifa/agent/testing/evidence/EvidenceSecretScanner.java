package io.haifa.agent.testing.evidence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Scans an evidence tree for injected secret values without loading complete artifacts into memory. */
public final class EvidenceSecretScanner {
    private static final Set<String> EXCLUDED_FILE_NAMES = Set.of("manifest.sha256", "secret-scan.json");
    private static final int BUFFER_SIZE = 8192;

    private EvidenceSecretScanner() {}

    public static Result scan(Path root, Collection<String> secrets) throws IOException {
        Objects.requireNonNull(secrets, "secrets must not be null");
        Path target = root.toAbsolutePath().normalize().toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target)) {
            throw new IOException("evidence root must be a real directory");
        }

        List<byte[]> needles = new LinkedHashSet<>(secrets)
                .stream()
                        .filter(Objects::nonNull)
                        .filter(value -> !value.isBlank())
                        .map(value -> value.getBytes(StandardCharsets.UTF_8))
                        .toList();
        List<Path> entries;
        try (var paths = Files.walk(target)) {
            entries = paths.sorted(
                            Comparator.comparing(path -> target.relativize(path).toString()))
                    .toList();
        }

        List<String> findingPaths = new ArrayList<>();
        for (Path entry : entries) {
            if (Files.isSymbolicLink(entry)) {
                EvidenceSymlinkTarget.requireInternal(target, entry);
                byte[] declaredTarget = Files.readSymbolicLink(entry).toString().getBytes(StandardCharsets.UTF_8);
                for (byte[] needle : needles) {
                    if (contains(declaredTarget, needle)) {
                        findingPaths.add(target.relativize(entry).toString().replace('\\', '/'));
                        break;
                    }
                }
                continue;
            }
            if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("evidence tree contains an unsupported entry");
            }
            if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
                    || EXCLUDED_FILE_NAMES.contains(entry.getFileName().toString())) {
                continue;
            }
            for (byte[] needle : needles) {
                if (contains(entry, needle)) {
                    findingPaths.add(target.relativize(entry).toString().replace('\\', '/'));
                    break;
                }
            }
        }
        return new Result(1, findingPaths.isEmpty(), findingPaths);
    }

    private static boolean contains(Path file, byte[] needle) throws IOException {
        if (needle.length == 0) return false;
        int[] failure = failureTable(needle);
        int matched = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                for (int index = 0; index < read; index++) {
                    while (matched > 0 && buffer[index] != needle[matched]) {
                        matched = failure[matched - 1];
                    }
                    if (buffer[index] == needle[matched]) {
                        matched++;
                        if (matched == needle.length) return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean contains(byte[] content, byte[] needle) {
        if (needle.length == 0) return false;
        int[] failure = failureTable(needle);
        int matched = 0;
        for (byte value : content) {
            while (matched > 0 && value != needle[matched]) {
                matched = failure[matched - 1];
            }
            if (value == needle[matched] && ++matched == needle.length) {
                return true;
            }
        }
        return false;
    }

    private static int[] failureTable(byte[] needle) {
        int[] failure = new int[needle.length];
        int matched = 0;
        for (int index = 1; index < needle.length; index++) {
            while (matched > 0 && needle[index] != needle[matched]) {
                matched = failure[matched - 1];
            }
            if (needle[index] == needle[matched]) {
                failure[index] = ++matched;
            }
        }
        return failure;
    }

    public record Result(int schemaVersion, boolean passed, List<String> findingPaths) {
        public Result {
            findingPaths = List.copyOf(Objects.requireNonNull(findingPaths, "findingPaths must not be null"));
        }
    }
}
