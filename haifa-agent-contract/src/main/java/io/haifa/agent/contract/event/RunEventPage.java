package io.haifa.agent.contract.event;

import java.util.List;
import java.util.Objects;

public record RunEventPage(
        List<RunEventEnvelope<? extends RunEventPayload>> items,
        RunEventCursor nextCursor,
        RunEventCursor headCursor,
        boolean hasMore) {
    public RunEventPage {
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
        nextCursor = Objects.requireNonNull(nextCursor, "nextCursor must not be null");
        headCursor = Objects.requireNonNull(headCursor, "headCursor must not be null");
    }
}
