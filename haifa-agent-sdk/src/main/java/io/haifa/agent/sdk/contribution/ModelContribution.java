package io.haifa.agent.sdk.contribution;

import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.ModelAdapterCoordinate;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.sdk.product.ProductCapabilities;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Versioned model implementation and the exact snapshot frozen into each run. */
public final class ModelContribution extends AbstractSdkContribution {
    private final Map<ModelAdapterCoordinate, AgentChatModel> adapters;
    private final ResolvedModelSnapshot snapshot;
    private final Map<String, ResolvedModelSnapshot> snapshots;

    public ModelContribution(
            SdkContributionMetadata metadata,
            Map<ModelAdapterCoordinate, AgentChatModel> adapters,
            ResolvedModelSnapshot defaultSnapshot,
            Map<String, ResolvedModelSnapshot> snapshots) {
        super(metadata);
        if (!ProductCapabilities.MODEL.equals(metadata.capabilityId())) {
            throw new IllegalArgumentException("model contribution must provide the model capability");
        }
        LinkedHashMap<ModelAdapterCoordinate, AgentChatModel> adapterCopy = new LinkedHashMap<>();
        Objects.requireNonNull(adapters, "adapters must not be null").forEach((coordinate, adapter) -> {
            if (adapterCopy.putIfAbsent(Objects.requireNonNull(coordinate), Objects.requireNonNull(adapter)) != null) {
                throw new IllegalArgumentException("model adapter coordinates must be unique");
            }
        });
        if (adapterCopy.isEmpty()) throw new IllegalArgumentException("model adapters must not be empty");
        this.adapters = Map.copyOf(adapterCopy);
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

    public Map<ModelAdapterCoordinate, AgentChatModel> adapters() {
        return adapters;
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
            if (!adapters.containsKey(ModelAdapterCoordinate.from(value))) {
                throw new IllegalArgumentException("model snapshot references an unavailable adapter");
            }
        });
    }
}
