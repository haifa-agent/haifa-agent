package io.haifa.agent.runtime.api;

import java.util.List;
import java.util.Objects;

/** Cursor page; intentionally separate from page-number based contracts. */
public record RunEventPage(
        List<AgentRunEvent> items, RunEventCursor nextCursor, RunEventCursor headCursor, boolean hasMore) {
    public RunEventPage {
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
        nextCursor = Objects.requireNonNull(nextCursor, "nextCursor must not be null");
        headCursor = Objects.requireNonNull(headCursor, "headCursor must not be null");
        if (!nextCursor.runId().equals(headCursor.runId())) {
            throw new IllegalArgumentException("page cursors must belong to the same run");
        }
    }
}
