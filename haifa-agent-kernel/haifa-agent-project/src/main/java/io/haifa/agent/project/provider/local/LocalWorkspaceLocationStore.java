package io.haifa.agent.project.provider.local;

import io.haifa.agent.project.binding.WorkspaceLocationRef;
import io.haifa.agent.project.spi.WorkspaceLocationStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class LocalWorkspaceLocationStore implements WorkspaceLocationStore {
    private final ConcurrentHashMap<WorkspaceLocationRef, Path> locations = new ConcurrentHashMap<>();

    public void register(WorkspaceLocationRef reference, Path hostRoot) {
        Objects.requireNonNull(reference, "reference must not be null");
        Path normalized = Objects.requireNonNull(hostRoot, "hostRoot must not be null")
                .toAbsolutePath()
                .normalize();
        if (locations.putIfAbsent(reference, normalized) != null) {
            throw new IllegalStateException("workspace location is already registered");
        }
    }

    Path resolve(WorkspaceLocationRef reference) {
        Path path = locations.get(reference);
        if (path == null) throw new IllegalStateException("workspace location is not registered");
        return path;
    }

    /** Host-path bridge for trusted provider implementations; never expose this value to product or model APIs. */
    public Path resolveForTrustedProvider(WorkspaceLocationRef reference) {
        return resolve(reference);
    }

    public void unregisterForTrustedProvider(WorkspaceLocationRef reference, Path expectedHostRoot) {
        Path expected = Objects.requireNonNull(expectedHostRoot, "expectedHostRoot must not be null")
                .toAbsolutePath()
                .normalize();
        if (!locations.remove(reference, expected)) {
            throw new IllegalStateException("workspace location ownership mismatch");
        }
    }

    @Override
    public boolean contains(WorkspaceLocationRef reference) {
        return locations.containsKey(reference);
    }

    public static String fingerprintFor(Path hostRoot) {
        try {
            String canonical = Objects.requireNonNull(hostRoot, "hostRoot must not be null")
                    .toAbsolutePath()
                    .normalize()
                    .toString();
            String hash = HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
            return "sha256:" + hash;
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    @Override
    public String toString() {
        return "LocalWorkspaceLocationStore[locations=" + locations.size() + "]";
    }
}
