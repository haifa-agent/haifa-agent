package io.haifa.agent.runtime.api;

import java.util.List;
import java.util.Objects;

/** Bounded projection of committed assistant/tool message facts. */
public record RunMessagePage(
        List<RunMessageView> items, RunMessageCursor nextCursor, RunMessageCursor headCursor, boolean hasMore) {
    public RunMessagePage {
        items = List.copyOf(items);
        Objects.requireNonNull(nextCursor, "nextCursor must not be null");
        Objects.requireNonNull(headCursor, "headCursor must not be null");
        if (!nextCursor.runId().equals(headCursor.runId())) throw new IllegalArgumentException("Run cursors differ");
    }
}
