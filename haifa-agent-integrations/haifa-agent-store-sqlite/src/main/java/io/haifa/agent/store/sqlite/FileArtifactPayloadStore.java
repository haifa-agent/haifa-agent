package io.haifa.agent.store.sqlite;

import io.haifa.agent.artifact.ArtifactPayloadRef;
import io.haifa.agent.artifact.ArtifactPayloadStore;
import io.haifa.agent.common.io.SecureFilePermissions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/** Opaque, no-follow payload storage rooted below the application-owned data directory. */
public final class FileArtifactPayloadStore implements ArtifactPayloadStore {
    private static final long MAX_PAYLOAD_BYTES = 1024 * 1024;
    private final Path root;

    public FileArtifactPayloadStore(Path root) {
        try {
            Path normalized = root.toAbsolutePath().normalize();
            Files.createDirectories(normalized);
            SecureFilePermissions.secureDirectory(normalized);
            this.root = normalized.toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (IOException exception) {
            throw new SqliteStoreException(
                    SqliteStoreFailure.FILE_PERMISSION_FAILED, "Artifact payload directory is unavailable", exception);
        }
    }

    @Override
    public ArtifactPayloadRef put(byte[] payload, String mediaType) {
        byte[] content = Arrays.copyOf(payload, payload.length);
        if (content.length > MAX_PAYLOAD_BYTES) throw new IllegalArgumentException("artifact payload exceeds limit");
        if (!("application/json".equals(mediaType) || "text/markdown; charset=utf-8".equals(mediaType))) {
            throw new IllegalArgumentException("artifact media type is not allowed");
        }
        String id = UUID.randomUUID().toString().replace("-", "");
        Path target = resolve(id);
        Path temporary = resolve(id + ".tmp-" + UUID.randomUUID().toString().replace("-", ""));
        try {
            Files.write(temporary, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            SecureFilePermissions.secureFile(temporary);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            SecureFilePermissions.secureFile(target);
            return new ArtifactPayloadRef(id, "sha256:" + sha256(content), content.length, mediaType);
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanup) {
                exception.addSuppressed(cleanup);
            }
            throw new SqliteStoreException(
                    SqliteStoreFailure.TRANSACTION_FAILED, "Artifact payload write failed", exception);
        }
    }

    @Override
    public Optional<byte[]> load(ArtifactPayloadRef reference) {
        Path target = resolve(reference.payloadId());
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        try {
            byte[] content = Files.readAllBytes(target);
            if (content.length != reference.byteCount() || !("sha256:" + sha256(content)).equals(reference.sha256())) {
                throw new IllegalStateException("artifact payload integrity check failed");
            }
            return Optional.of(content);
        } catch (IOException exception) {
            throw new SqliteStoreException(
                    SqliteStoreFailure.TRANSACTION_FAILED, "Artifact payload read failed", exception);
        }
    }

    @Override
    public void delete(ArtifactPayloadRef reference) {
        try {
            Files.deleteIfExists(resolve(reference.payloadId()));
        } catch (IOException exception) {
            throw new SqliteStoreException(
                    SqliteStoreFailure.TRANSACTION_FAILED, "Artifact payload delete failed", exception);
        }
    }

    private Path resolve(String id) {
        if (!id.matches("[a-f0-9]{32}(?:\\.tmp-[a-f0-9]{32})?")) {
            throw new SecurityException("invalid artifact payload identifier");
        }
        Path target = root.resolve(id).normalize();
        if (!target.getParent().equals(root) || Files.isSymbolicLink(target)) {
            throw new SecurityException("artifact payload escaped its root");
        }
        return target;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
