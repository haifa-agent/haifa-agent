package io.haifa.agent.sdk.contribution;

import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.ModelAdapterCoordinate;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Versioned model implementation and the exact snapshots frozen into runs. */
public record ModelContribution(
        Map<ModelAdapterCoordinate, AgentChatModel> adapters,
        ResolvedModelSnapshot snapshot,
        Map<String, ResolvedModelSnapshot> snapshots) {

    public ModelContribution {
        LinkedHashMap<ModelAdapterCoordinate, AgentChatModel> adapterCopy = new LinkedHashMap<>();
        Objects.requireNonNull(adapters, "adapters must not be null").forEach((coordinate, adapter) -> {
            if (adapterCopy.putIfAbsent(Objects.requireNonNull(coordinate), Objects.requireNonNull(adapter)) != null) {
                throw new IllegalArgumentException("model adapter coordinates must be unique");
            }
        });
        if (adapterCopy.isEmpty()) throw new IllegalArgumentException("model adapters must not be empty");
        var adapterView = Map.copyOf(adapterCopy);
        adapters = adapterView;
        snapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
        LinkedHashMap<String, ResolvedModelSnapshot> snapshotCopy = new LinkedHashMap<>();
        Objects.requireNonNull(snapshots, "snapshots must not be null").forEach((id, value) -> {
            if (id == null
                    || id.isBlank()
                    || snapshotCopy.putIfAbsent(id.trim(), Objects.requireNonNull(value)) != null) {
                throw new IllegalArgumentException("model snapshot ids must be unique text");
            }
        });
        if (snapshotCopy.isEmpty() || !snapshotCopy.containsValue(snapshot)) {
            throw new IllegalArgumentException("model snapshots must contain the default snapshot");
        }
        snapshotCopy.forEach((id, value) -> {
            if (!id.equals(value.modelId().value())) {
                throw new IllegalArgumentException("model snapshot key must match internal model id");
            }
            if (!adapterView.containsKey(ModelAdapterCoordinate.from(value))) {
                throw new IllegalArgumentException("model snapshot references an unavailable adapter");
            }
        });
        snapshots = Map.copyOf(snapshotCopy);
    }
}
