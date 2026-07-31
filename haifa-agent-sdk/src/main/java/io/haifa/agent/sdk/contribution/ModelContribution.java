package io.haifa.agent.sdk.contribution;

import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.sdk.product.ProductCapabilities;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Versioned model implementation and the exact snapshot frozen into each run. */
public final class ModelContribution extends AbstractSdkContribution {
    private final AgentChatModel model;
    private final ResolvedModelSnapshot snapshot;
    private final Map<String, ResolvedModelSnapshot> snapshots;

    public ModelContribution(SdkContributionMetadata metadata, AgentChatModel model, ResolvedModelSnapshot snapshot) {
        super(metadata);
        if (!ProductCapabilities.MODEL.equals(metadata.capabilityId())) {
            throw new IllegalArgumentException("model contribution must provide the model capability");
        }
        this.model = Objects.requireNonNull(model, "model must not be null");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
        this.snapshots = Map.of(snapshot.modelId().value(), snapshot);
    }

    public ModelContribution(
            SdkContributionMetadata metadata,
            AgentChatModel model,
            ResolvedModelSnapshot defaultSnapshot,
            Map<String, ResolvedModelSnapshot> snapshots) {
        super(metadata);
        if (!ProductCapabilities.MODEL.equals(metadata.capabilityId())) {
            throw new IllegalArgumentException("model contribution must provide the model capability");
        }
        this.model = Objects.requireNonNull(model, "model must not be null");
        this.snapshot = Objects.requireNonNull(defaultSnapshot, "defaultSnapshot must not be null");
        LinkedHashMap<String, ResolvedModelSnapshot> copy = new LinkedHashMap<>();
        Objects.requireNonNull(snapshots, "snapshots must not be null").forEach((id, value) -> {
            if (id == null || id.isBlank() || copy.putIfAbsent(id.trim(), Objects.requireNonNull(value)) != null) {
                throw new IllegalArgumentException("model snapshot ids must be unique text");
            }
        });
        if (copy.isEmpty() || !copy.containsValue(defaultSnapshot)) {
            throw new IllegalArgumentException("model snapshots must contain defaultSnapshot");
        }
        this.snapshots = Map.copyOf(copy);
    }

    public AgentChatModel model() {
        return model;
    }

    public ResolvedModelSnapshot snapshot() {
        return snapshot;
    }

    public Map<String, ResolvedModelSnapshot> snapshots() {
        return snapshots;
    }

    @Override
    public void validate() {
        if (!configurationDigest().equals(snapshot.configurationDigest())) {
            throw new IllegalArgumentException("model contribution digest must match the model snapshot");
        }
        snapshots.forEach((id, value) -> {
            if (!id.equals(value.modelId().value())) {
                throw new IllegalArgumentException("model snapshot key must match internal model id");
            }
        });
    }
}
