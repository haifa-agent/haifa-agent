package io.haifa.agent.core.reference;

import static io.haifa.agent.core.support.DomainValues.requireText;

/** Store-neutral reference to immutable checkpoint payload data. */
public record CheckpointPayloadRef(String storeType, String location, String schemaId, String schemaVersion) {
    public CheckpointPayloadRef {
        storeType = requireText(storeType, "storeType");
        location = requireText(location, "location");
        schemaId = requireText(schemaId, "schemaId");
        schemaVersion = requireText(schemaVersion, "schemaVersion");
    }
}
