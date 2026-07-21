package io.haifa.agent.artifact;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

public final class ArtifactService {
    private final ArtifactStore artifacts;
    private final ArtifactPayloadStore payloads;
    private final IdentifierGenerator ids;
    private final TimeProvider time;

    public ArtifactService(
            ArtifactStore artifacts, ArtifactPayloadStore payloads, IdentifierGenerator ids, TimeProvider time) {
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts must not be null");
        this.payloads = Objects.requireNonNull(payloads, "payloads must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.time = Objects.requireNonNull(time, "time must not be null");
    }

    public Artifact publish(
            ArtifactType type, String title, byte[] content, String mediaType, ArtifactProvenance provenance) {
        byte[] immutable = Arrays.copyOf(Objects.requireNonNull(content, "content must not be null"), content.length);
        ArtifactPayloadRef payload = payloads.put(immutable, mediaType);
        if (!payload.sha256().equals("sha256:" + hash(immutable)) || payload.byteCount() != immutable.length) {
            throw new IllegalStateException("artifact payload verification failed");
        }
        try {
            Artifact artifact = new Artifact(
                    new ArtifactId(ids.nextValue()),
                    new ArtifactVersion(1),
                    type,
                    title,
                    payload,
                    provenance,
                    ArtifactStatus.PUBLISHED,
                    time.now());
            artifacts.create(artifact);
            return artifact;
        } catch (RuntimeException exception) {
            payloads.delete(payload);
            throw exception;
        }
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
