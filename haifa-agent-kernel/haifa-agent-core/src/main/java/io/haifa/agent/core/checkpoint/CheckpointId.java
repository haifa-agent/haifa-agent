package io.haifa.agent.core.checkpoint;

import io.haifa.agent.common.id.Identifier;
import io.haifa.agent.common.id.Identifiers;

/** Strongly typed immutable checkpoint identifier. */
public record CheckpointId(String value) implements Identifier {
    public CheckpointId {
        value = Identifiers.requireValid(value, "checkpointId");
    }
}
