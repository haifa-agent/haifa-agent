package io.haifa.agent.artifact;

import java.util.Objects;

public record ArtifactRelation(String relationType, ArtifactId targetId, ArtifactVersion targetVersion) {
    public ArtifactRelation {
        relationType = Objects.requireNonNull(relationType, "relationType must not be null")
                .trim();
        if (relationType.isEmpty()) throw new IllegalArgumentException("relationType must not be blank");
        targetId = Objects.requireNonNull(targetId, "targetId must not be null");
        targetVersion = Objects.requireNonNull(targetVersion, "targetVersion must not be null");
    }
}
