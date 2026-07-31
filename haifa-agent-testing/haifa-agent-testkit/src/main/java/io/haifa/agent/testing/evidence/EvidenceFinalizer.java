package io.haifa.agent.testing.evidence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Publishes one immutable evidence tree by hashing content and then verifying host read-only controls. */
public final class EvidenceFinalizer {
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
        List<String> lines = new ArrayList<>();
        List<Path> entries;
        try (var paths = Files.walk(target)) {
            entries = paths.sorted(
                            Comparator.comparing(path -> target.relativize(path).toString()))
                    .toList();
        }
        for (Path entry : entries) {
            if (Files.isSymbolicLink(entry)) {
                throw new IOException("evidence tree must not contain symbolic links");
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
}
