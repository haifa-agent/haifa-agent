package io.haifa.agent.artifact;

import io.haifa.agent.core.reference.ArtifactRef;
import java.time.Instant;
import java.util.Objects;

public record Artifact(
        ArtifactId id,
        ArtifactVersion version,
        ArtifactType type,
        String title,
        ArtifactPayloadRef payload,
        ArtifactProvenance provenance,
        ArtifactStatus status,
        Instant createdAt) {
    public Artifact {
        id = Objects.requireNonNull(id, "id must not be null");
        version = Objects.requireNonNull(version, "version must not be null");
        type = Objects.requireNonNull(type, "type must not be null");
        title = Objects.requireNonNull(title, "title must not be null").trim();
        if (title.isEmpty()) throw new IllegalArgumentException("title must not be blank");
        payload = Objects.requireNonNull(payload, "payload must not be null");
        provenance = Objects.requireNonNull(provenance, "provenance must not be null");
        status = Objects.requireNonNull(status, "status must not be null");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public ArtifactRef reference() {
        return new ArtifactRef(id.value(), type.value(), Long.toString(version.value()), title);
    }
}
