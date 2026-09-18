package io.haifa.agent.runtime.core.event;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RunEventPage;
import io.haifa.agent.runtime.api.RuntimeApiErrorCode;
import io.haifa.agent.runtime.api.RuntimeContractException;
import io.haifa.agent.runtime.core.storage.RuntimeEventAppender;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/** Exclusive-cursor, bounded client projection over the authoritative Runtime Journal. */
public final class RuntimeEventFeed {
    public static final int MAXIMUM_PAGE_SIZE = 1_000;
    private static final int MAXIMUM_SCAN_BUDGET = 10_000;

    private final RuntimeEventAppender journal;
    private final RuntimeClientEventProjector projector;

    public RuntimeEventFeed(RuntimeEventAppender journal, RuntimeClientEventProjector projector) {
        this.journal = Objects.requireNonNull(journal, "journal must not be null");
        this.projector = Objects.requireNonNull(projector, "projector must not be null");
    }

    public RunEventPage page(AgentRunId runId, RunEventCursor after, int limit) {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(after, "after must not be null");
        if (!runId.equals(after.runId())) {
            throw new RuntimeContractException(RuntimeApiErrorCode.CURSOR_INVALID, "The cursor belongs to another Run");
        }
        if (!"1".equals(after.feedVersion())) {
            throw new RuntimeContractException(
                    RuntimeApiErrorCode.CONTRACT_VERSION_UNSUPPORTED, "The Run Event Feed version is unsupported");
        }
        if (limit < 1 || limit > MAXIMUM_PAGE_SIZE) {
            throw new IllegalArgumentException("limit must be in 1.." + MAXIMUM_PAGE_SIZE);
        }
        long requested = after.exclusiveSequence().orElse(0L);
        int initialBatchLimit = Math.min(Math.min(limit + 32, MAXIMUM_PAGE_SIZE), MAXIMUM_SCAN_BUDGET);
        var slice = journal.eventsAfter(runId, requested, OptionalLong.empty(), initialBatchLimit);
        OptionalLong headValue = slice.headSequence();
        if (headValue.isEmpty()) {
            if (requested > 0) {
                throw new RuntimeContractException(
                        RuntimeApiErrorCode.CURSOR_INVALID, "The cursor is ahead of the Feed");
            }
            RunEventCursor empty = RunEventCursor.beforeFirst(runId);
            return new RunEventPage(List.of(), empty, empty, false);
        }
        long head = headValue.getAsLong();
        if (requested > head) {
            throw new RuntimeContractException(RuntimeApiErrorCode.CURSOR_INVALID, "The cursor is ahead of the Feed");
        }
        requireRetained(requested, slice.earliestSequence());

        List<io.haifa.agent.runtime.api.AgentRunEvent> items = new ArrayList<>(limit);
        long scanCursor = requested;
        int scanned = 0;
        while (scanCursor < head && items.size() < limit && scanned < MAXIMUM_SCAN_BUDGET) {
            if (slice.events().isEmpty()) {
                scanCursor = head;
                break;
            }
            for (var event : slice.events()) {
                projector.project(event).ifPresent(items::add);
                scanCursor = event.sequence();
                scanned++;
                if (items.size() == limit || scanned == MAXIMUM_SCAN_BUDGET) break;
            }
            if (scanCursor < head && items.size() < limit && scanned < MAXIMUM_SCAN_BUDGET) {
                int batchLimit =
                        Math.min(Math.min(limit - items.size() + 32, MAXIMUM_PAGE_SIZE), MAXIMUM_SCAN_BUDGET - scanned);
                slice = journal.eventsAfter(runId, scanCursor, OptionalLong.of(head), batchLimit);
                requireRetained(scanCursor, slice.earliestSequence());
            }
        }
        RunEventCursor next = cursor(runId, scanCursor);
        return new RunEventPage(List.copyOf(items), next, cursor(runId, head), scanCursor < head);
    }

    private static void requireRetained(long exclusiveSequence, OptionalLong earliestSequence) {
        if (earliestSequence.isPresent() && exclusiveSequence < earliestSequence.getAsLong() - 1L) {
            throw new RuntimeContractException(RuntimeApiErrorCode.CURSOR_EXPIRED, "The cursor is outside retention");
        }
    }

    private static RunEventCursor cursor(AgentRunId runId, long sequence) {
        return sequence == 0
                ? RunEventCursor.beforeFirst(runId)
                : new RunEventCursor(runId, "1", OptionalLong.of(sequence));
    }
}
