package io.haifa.agent.sdk.contribution;

import io.haifa.agent.artifact.ArtifactService;
import java.util.Objects;

/** Product-selected Artifact application service. Production storage is supplied by its adapter. */
public record ArtifactPlatformContribution(ArtifactService service) {
    public ArtifactPlatformContribution {
        service = Objects.requireNonNull(service, "service must not be null");
    }
}
