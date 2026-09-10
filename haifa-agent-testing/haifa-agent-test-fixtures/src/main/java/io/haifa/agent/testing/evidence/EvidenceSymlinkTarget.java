package io.haifa.agent.testing.evidence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Validates evidence symlinks without permitting host-path or repository escapes. */
final class EvidenceSymlinkTarget {
    private EvidenceSymlinkTarget() {}

    static Path requireInternal(Path root, Path link) throws IOException {
        Path lexicalRoot = root.toAbsolutePath().normalize();
        Path realRoot = root.toRealPath();
        if (!Files.isSymbolicLink(link)) {
            throw new IOException("evidence entry is not a symbolic link");
        }
        Path declaredTarget = Files.readSymbolicLink(link);
        if (declaredTarget.isAbsolute()) {
            throw new IOException("evidence symbolic link target must be relative");
        }
        Path unresolvedTarget = link.toAbsolutePath()
                .normalize()
                .getParent()
                .resolve(declaredTarget)
                .normalize();
        if (!unresolvedTarget.startsWith(lexicalRoot)) {
            throw new IOException("evidence symbolic link target escapes the evidence root");
        }
        Path realTarget = unresolvedTarget.toRealPath();
        if (!realTarget.startsWith(realRoot)) {
            throw new IOException("evidence symbolic link resolves outside the evidence root");
        }
        if (!Files.isDirectory(realTarget, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(realTarget, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("evidence symbolic link target is unsupported");
        }
        return realTarget;
    }
}
