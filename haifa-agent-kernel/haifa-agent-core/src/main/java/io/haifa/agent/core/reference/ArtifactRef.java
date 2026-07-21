package io.haifa.agent.core.reference;

import static io.haifa.agent.core.support.DomainValues.requireText;

/** Lightweight reference to an independently versioned Agent deliverable. */
public record ArtifactRef(String artifactId, String artifactType, String version, String title) {
    public ArtifactRef {
        artifactId = requireText(artifactId, "artifactId");
        artifactType = requireText(artifactType, "artifactType");
        version = requireText(version, "version");
        title = requireText(title, "title");
    }
}
