package io.haifa.agent.application.project.artifact;

import io.haifa.agent.core.reference.ArtifactRef;
import java.util.Objects;

public record ArtifactExportResult(ArtifactRef artifact, String sourceHash, long byteCount) {
    public ArtifactExportResult {
        artifact = Objects.requireNonNull(artifact, "artifact must not be null");
        sourceHash = Objects.requireNonNull(sourceHash, "sourceHash must not be null");
        if (byteCount < 0) throw new IllegalArgumentException("byteCount must not be negative");
    }
}
