package io.haifa.agent.store.sqlite.payload;

import io.haifa.agent.core.reference.ArtifactRef;

public record ArtifactPayload(String artifactId, String artifactType, String version, String title) {
    public static ArtifactPayload from(ArtifactRef reference) {
        return new ArtifactPayload(
                reference.artifactId(), reference.artifactType(), reference.version(), reference.title());
    }

    public ArtifactRef toDomain() {
        return new ArtifactRef(artifactId, artifactType, version, title);
    }
}
