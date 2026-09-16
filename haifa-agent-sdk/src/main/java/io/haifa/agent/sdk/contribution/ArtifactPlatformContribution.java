package io.haifa.agent.sdk.contribution;

import io.haifa.agent.artifact.ArtifactService;
import io.haifa.agent.sdk.product.ProductArtifactPolicy;
import java.util.Objects;

/** Product-selected Artifact application service and its product governance. */
public record ArtifactPlatformContribution(ArtifactService service, ProductArtifactPolicy policy) {
    public ArtifactPlatformContribution {
        service = Objects.requireNonNull(service, "service must not be null");
        policy = Objects.requireNonNull(policy, "policy must not be null");
    }
}
