package io.haifa.agent.testing.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/** Publishes one immutable evidence tree by hashing content and then verifying host read-only controls. */
public final class EvidenceFinalizer {
    private static final String SYMLINK_METADATA = ".evidence-symlinks-v1.json";

    private EvidenceFinalizer() {}

    public static void finalizeEvidence(Path root) throws IOException {
        writeManifest(root);
        EvidenceReadOnlyTree.apply(root);
    }

    public static void writeManifest(Path root) throws IOException {
        Path target = root.toAbsolutePath().normalize().toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target)) {
            throw new IOException("evidence root must be a real directory");
        }
        writeSymlinkMetadata(target);
        List<String> lines = new ArrayList<>();
        List<Path> entries;
        try (var paths = Files.walk(target)) {
            entries = paths.sorted(
                            Comparator.comparing(path -> target.relativize(path).toString()))
                    .toList();
        }
        for (Path entry : entries) {
            if (Files.isSymbolicLink(entry)) {
                EvidenceSymlinkTarget.requireInternal(target, entry);
                continue;
            }
            if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("evidence tree contains an unsupported entry");
            }
            if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
                    && !entry.getFileName().toString().equals("manifest.sha256")
                    && !entry.getFileName().toString().equals(".DS_Store")) {
                Path file = entry;
                lines.add(Sha256Digests.file(file) + "  "
                        + target.relativize(file).toString().replace('\\', '/'));
            }
        }
        Files.write(target.resolve("manifest.sha256"), lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private static void writeSymlinkMetadata(Path root) throws IOException {
        List<Path> links;
        try (var paths = Files.walk(root)) {
            links = paths.filter(Files::isSymbolicLink)
                    .sorted(Comparator.comparing(path -> root.relativize(path).toString()))
                    .toList();
        }
        if (links.isEmpty()) {
            return;
        }
        List<LinkedHashMap<String, Object>> entries = new ArrayList<>();
        for (Path link : links) {
            EvidenceSymlinkTarget.requireInternal(root, link);
            LinkedHashMap<String, Object> entry = new LinkedHashMap<>();
            entry.put("path", root.relativize(link).toString().replace('\\', '/'));
            entry.put("target", Files.readSymbolicLink(link).toString().replace('\\', '/'));
            entries.add(entry);
        }
        LinkedHashMap<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("schemaVersion", 1);
        artifact.put("links", entries);
        try (var output = Files.newOutputStream(
                root.resolve(SYMLINK_METADATA), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output, artifact);
        }
    }
}
