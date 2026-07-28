package io.haifa.agent.sdk.contribution;

import io.haifa.agent.artifact.ArtifactService;
import io.haifa.agent.sdk.product.ProductCapabilities;
import java.util.Objects;

/** Product-selected Artifact application service. Production storage is supplied by its adapter. */
public final class ArtifactPlatformContribution extends AbstractSdkContribution {
    private final ArtifactService service;

    public ArtifactPlatformContribution(SdkContributionMetadata metadata, ArtifactService service) {
        super(metadata);
        if (!ProductCapabilities.ARTIFACT.equals(metadata.capabilityId())) {
            throw new IllegalArgumentException("artifact contribution must provide the artifact capability");
        }
        this.service = Objects.requireNonNull(service, "service must not be null");
    }

    public ArtifactService service() {
        return service;
    }
}
