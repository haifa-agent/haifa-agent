package io.haifa.agent.orchestration.core.spi;

import io.haifa.agent.orchestration.api.WorkflowEvent;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record WorkflowOutboxRecord(WorkflowEvent event, Optional<Instant> publishedAt) {
    public WorkflowOutboxRecord {
        Objects.requireNonNull(event, "event must not be null");
        publishedAt = Objects.requireNonNull(publishedAt, "publishedAt must not be null");
    }
}
