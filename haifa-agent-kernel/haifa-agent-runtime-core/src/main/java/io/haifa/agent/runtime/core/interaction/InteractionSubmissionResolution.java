package io.haifa.agent.runtime.core.interaction;

import java.util.Objects;

public record InteractionSubmissionResolution(InteractionRecord record, boolean newlyRecorded) {
    public InteractionSubmissionResolution {
        record = Objects.requireNonNull(record, "record must not be null");
    }
}
