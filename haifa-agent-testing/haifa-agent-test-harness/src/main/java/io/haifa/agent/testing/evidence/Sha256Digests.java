package io.haifa.agent.testing.evidence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Stable SHA-256 helpers owned by the executable test harness. */
public final class Sha256Digests {
    private Sha256Digests() {}

    public static String bytes(byte[] content) {
        return HexFormat.of().formatHex(newDigest().digest(content));
    }

    public static String stream(InputStream input) throws IOException {
        MessageDigest digest = newDigest();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static String file(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return stream(input);
        }
    }

    /**
     * Digests a directory from sorted logical paths and individual file digests.
     *
     * <p>The canonical byte stream is repeated {@code relativePath NUL fileSha256 NUL}. Paths always
     * use {@code /}, so the digest is independent of the host path and platform separator.
     */
    public static String directory(Path root) throws IOException {
        return directory(root, false);
    }

    /**
     * Digests historical evidence while ignoring Finder's mutable desktop metadata.
     *
     * <p>No execution evidence or candidate workspace file is excluded.
     */
    public static String historicalEvidenceDirectory(Path root) throws IOException {
        return directory(root, true);
    }

    private static String directory(Path root, boolean ignoreDesktopMetadata) throws IOException {
        Path normalized = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            throw new IllegalArgumentException("digest root must be a directory");
        }
        List<Path> files;
        try (var paths = Files.walk(normalized)) {
            files = paths.filter(Files::isRegularFile)
                    .filter(path -> !ignoreDesktopMetadata
                            || !path.getFileName().toString().equals(".DS_Store"))
                    .sorted((left, right) -> logicalPath(normalized, left).compareTo(logicalPath(normalized, right)))
                    .toList();
        }
        MessageDigest digest = newDigest();
        for (Path file : files) {
            update(digest, logicalPath(normalized, file));
            digest.update((byte) 0);
            update(digest, file(file));
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String logicalPath(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
