package io.haifa.agent.store.jsonl;

import java.util.List;

public record TranscriptReadResult(
        List<SafeTranscriptEvent> events, boolean truncatedTail, int duplicateCount, long repairOffset) {
    public TranscriptReadResult {
        events = List.copyOf(events);
        if (duplicateCount < 0) throw new IllegalArgumentException("duplicateCount must not be negative");
        if (repairOffset < 0) throw new IllegalArgumentException("repairOffset must not be negative");
    }
}
