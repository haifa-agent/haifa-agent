package io.haifa.agent.sdk.contribution;

import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.sdk.product.ProductCapabilities;
import java.util.Objects;

/** Versioned model implementation and the exact snapshot frozen into each run. */
public final class ModelContribution extends AbstractSdkContribution {
    private final AgentChatModel model;
    private final ResolvedModelSnapshot snapshot;

    public ModelContribution(SdkContributionMetadata metadata, AgentChatModel model, ResolvedModelSnapshot snapshot) {
        super(metadata);
        if (!ProductCapabilities.MODEL.equals(metadata.capabilityId())) {
            throw new IllegalArgumentException("model contribution must provide the model capability");
        }
        this.model = Objects.requireNonNull(model, "model must not be null");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
    }

    public AgentChatModel model() {
        return model;
    }

    public ResolvedModelSnapshot snapshot() {
        return snapshot;
    }

    @Override
    public void validate() {
        if (!configurationDigest().equals(snapshot.configurationDigest())) {
            throw new IllegalArgumentException("model contribution digest must match the model snapshot");
        }
    }
}
