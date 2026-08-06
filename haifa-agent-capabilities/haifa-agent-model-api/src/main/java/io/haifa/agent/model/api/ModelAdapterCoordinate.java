package io.haifa.agent.model.api;

/** Exact model adapter implementation coordinate frozen into a model snapshot. */
public record ModelAdapterCoordinate(String type, String version) {
    public ModelAdapterCoordinate {
        type = ModelValues.text(type, "type");
        version = ModelValues.text(version, "version");
    }

    public static ModelAdapterCoordinate from(ResolvedModelSnapshot snapshot) {
        if (snapshot == null) throw new IllegalArgumentException("snapshot must not be null");
        return new ModelAdapterCoordinate(snapshot.adapterType(), snapshot.adapterVersion());
    }
}
