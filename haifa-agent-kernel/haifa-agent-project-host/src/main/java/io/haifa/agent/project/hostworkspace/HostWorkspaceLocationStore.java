package io.haifa.agent.project.hostworkspace;

import io.haifa.agent.project.workspace.WorkspaceId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Host-only runtime bridge from a logical {@link WorkspaceId} to its canonical host root. It carries
 * no authorization of its own; user authorization lives in the durable authorized-directory record
 * and this store never decides who may use a directory. Every trusted resolution re-verifies the
 * canonical path, link status and physical directory identity so a replaced or re-targeted root
 * fails closed instead of silently pointing at a different directory.
 */
public final class HostWorkspaceLocationStore {
    private static final String PHYSICAL_NAMESPACE = "io.haifa.agent.project.local-authorized-directory-physical/v1";

    private record Registration(Path root, String physicalFingerprint) {}

    private final ConcurrentHashMap<WorkspaceId, Registration> locations = new ConcurrentHashMap<>();

    public void register(WorkspaceId workspaceId, Path hostRoot) {
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        Objects.requireNonNull(hostRoot, "hostRoot must not be null");
        if (HostWorkspacePathSafety.isUnsafeNode(hostRoot)) {
            throw new IllegalStateException("workspace root must not be a symbolic link or reparse point");
        }
        Path canonical = canonicalRoot(hostRoot);
        if (HostWorkspacePathSafety.isUnsafeNode(canonical)) {
            throw new IllegalStateException("workspace root must not be a symbolic link or reparse point");
        }
        Registration registration = new Registration(canonical, physicalFingerprintFor(canonical));
        if (locations.putIfAbsent(workspaceId, registration) != null) {
            throw new IllegalStateException("workspace location is already registered");
        }
    }

    public Path resolve(WorkspaceId workspaceId) {
        return verified(workspaceId).root();
    }

    /** Host-path bridge for trusted provider implementations; never expose this value to product or model APIs. */
    public Path resolveForTrustedProvider(WorkspaceId workspaceId) {
        return verified(workspaceId).root();
    }

    /** Resolves the canonical root and fails closed when the physical identity no longer matches. */
    public Path resolveVerified(WorkspaceId workspaceId) {
        return verified(workspaceId).root();
    }

    private Registration verified(WorkspaceId workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        Registration registration = locations.get(workspaceId);
        if (registration == null) throw new IllegalStateException("workspace location is not registered");
        Path current;
        try {
            current = registration.root().toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (IOException exception) {
            throw new IllegalStateException("workspace root is unavailable", exception);
        }
        if (HostWorkspacePathSafety.isUnsafeNode(current)
                || !physicalFingerprintFor(current).equals(registration.physicalFingerprint())) {
            throw new IllegalStateException("workspace root identity changed");
        }
        return new Registration(current, registration.physicalFingerprint());
    }

    public void unregisterForTrustedProvider(WorkspaceId workspaceId, Path expectedHostRoot) {
        Path expected = Objects.requireNonNull(expectedHostRoot, "expectedHostRoot must not be null")
                .toAbsolutePath()
                .normalize();
        Registration registration = locations.get(workspaceId);
        if (registration == null
                || !registration.root().equals(expected)
                || !locations.remove(workspaceId, registration)) {
            throw new IllegalStateException("workspace location ownership mismatch");
        }
    }

    public boolean contains(WorkspaceId workspaceId) {
        return locations.containsKey(workspaceId);
    }

    /** Stable, canonical-path-based identity signal for one host root. */
    public static String fingerprintFor(Path hostRoot) {
        try {
            String canonical = canonicalRoot(hostRoot).toString();
            String hash = HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
            return "sha256:" + hash;
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    /**
     * Host-only physical identity of one directory: filesystem identity (file key or creation time),
     * file store and canonical path. It changes when the directory at the same path is replaced.
     */
    public static String physicalFingerprintFor(Path directory) {
        Objects.requireNonNull(directory, "directory must not be null");
        try {
            Path canonical = canonicalRoot(directory);
            if (!java.nio.file.Files.isDirectory(canonical, LinkOption.NOFOLLOW_LINKS)
                    || HostWorkspacePathSafety.isUnsafeNode(canonical)) {
                throw new IllegalStateException("authorized directory is unavailable");
            }
            String platform =
                    System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "windows" : "posix";
            BasicFileAttributes attributes =
                    java.nio.file.Files.readAttributes(canonical, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            Object fileKey = attributes.fileKey();
            String filesystemIdentity = fileKey == null
                    ? "creation-time\0" + attributes.creationTime()
                    : "file-key\0" + fileKey.getClass().getName() + "\0" + fileKey;
            var fileStore = java.nio.file.Files.getFileStore(canonical);
            return "physical-directory-v1:"
                    + sha256(String.join(
                            "\0",
                            PHYSICAL_NAMESPACE,
                            platform,
                            fileStore.name(),
                            fileStore.type(),
                            filesystemIdentity,
                            fingerprintFor(canonical)));
        } catch (IOException exception) {
            throw new IllegalStateException("authorized directory is unavailable", exception);
        }
    }

    private static Path canonicalRoot(Path hostRoot) {
        try {
            return Objects.requireNonNull(hostRoot, "hostRoot must not be null")
                    .toAbsolutePath()
                    .normalize()
                    .toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (IOException exception) {
            throw new IllegalStateException("workspace root is unavailable", exception);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    @Override
    public String toString() {
        return "HostWorkspaceLocationStore[locations=" + locations.size() + "]";
    }
}
