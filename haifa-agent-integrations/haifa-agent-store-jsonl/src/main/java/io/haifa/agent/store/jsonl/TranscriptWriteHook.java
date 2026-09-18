package io.haifa.agent.store.jsonl;

/** Fault-injection and observability seam. Production callers should use {@link #none()}. */
public interface TranscriptWriteHook {
    default void beforeWrite(SafeTranscriptEvent event) {}

    default void afterWriteBeforeForce(SafeTranscriptEvent event) {}

    default void afterForce(SafeTranscriptEvent event) {}

    static TranscriptWriteHook none() {
        return new TranscriptWriteHook() {};
    }
}
