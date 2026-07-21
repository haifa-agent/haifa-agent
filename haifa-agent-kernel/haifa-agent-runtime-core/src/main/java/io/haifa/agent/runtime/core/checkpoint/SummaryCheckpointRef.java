package io.haifa.agent.runtime.core.checkpoint;

import io.haifa.agent.context.compression.SummaryId;
import io.haifa.agent.context.compression.SummaryVersion;
import io.haifa.agent.core.message.MessageCursor;
import java.util.Objects;

public record SummaryCheckpointRef(SummaryId id, SummaryVersion version, MessageCursor coveredThrough) {
    public SummaryCheckpointRef {
        id = Objects.requireNonNull(id, "id must not be null");
        version = Objects.requireNonNull(version, "version must not be null");
        coveredThrough = Objects.requireNonNull(coveredThrough, "coveredThrough must not be null");
    }
}
