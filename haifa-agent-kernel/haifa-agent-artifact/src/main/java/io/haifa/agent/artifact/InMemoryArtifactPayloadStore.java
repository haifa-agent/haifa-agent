package io.haifa.agent.artifact;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class InMemoryArtifactPayloadStore implements ArtifactPayloadStore {
    private final ConcurrentHashMap<String, Entry> values = new ConcurrentHashMap<>();

    @Override
    public ArtifactPayloadRef put(byte[] payload, String mediaType) {
        byte[] copy = Arrays.copyOf(payload, payload.length);
        String digest = "sha256:" + hash(copy);
        String id = "payload-" + digest.substring("sha256:".length(), "sha256:".length() + 24);
        values.compute(id, (key, existing) -> {
            if (existing == null) return new Entry(copy, new AtomicInteger(1));
            existing.references().incrementAndGet();
            return existing;
        });
        return new ArtifactPayloadRef(id, digest, copy.length, mediaType);
    }

    @Override
    public Optional<byte[]> load(ArtifactPayloadRef reference) {
        return Optional.ofNullable(values.get(reference.payloadId()))
                .map(value -> Arrays.copyOf(value.payload(), value.payload().length));
    }

    @Override
    public void delete(ArtifactPayloadRef reference) {
        values.computeIfPresent(
                reference.payloadId(),
                (key, existing) -> existing.references().decrementAndGet() == 0 ? null : existing);
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private record Entry(byte[] payload, AtomicInteger references) {}
}
