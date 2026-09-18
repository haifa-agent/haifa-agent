package io.haifa.agent.store.jsonl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.core.storage.OutboxMessage;
import io.haifa.agent.runtime.core.storage.RuntimeOutboxPublisher;
import io.haifa.agent.runtime.core.storage.RuntimeUnitOfWork;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonlTranscriptProjectorTest {
    private static final Instant NOW = Instant.parse("2026-07-25T08:00:00Z");

    @TempDir
    Path directory;

    @Test
    void marksConsumedAndPublishedOnlyAfterForce() {
        RecordingOutbox outbox = new RecordingOutbox(message("event-1", "run.completed"));
        AtomicBoolean forced = new AtomicBoolean();
        JsonlTranscriptProjector projector =
                projector(outbox, new JsonlTranscriptWriter(directory, new TranscriptWriteHook() {
                    @Override
                    public void afterForce(SafeTranscriptEvent event) {
                        forced.set(true);
                    }
                }));
        outbox.onMark = () -> assertThat(forced).isTrue();

        assertThat(projector.projectPending()).isEqualTo(1);
        assertThat(outbox.consumed).containsExactly("event-1");
        assertThat(outbox.published).containsExactly("event-1");
        assertThat(outbox.transactionCalls).hasValue(1);
    }

    @Test
    void failureBeforeFsyncDoesNotAcknowledge() {
        RecordingOutbox outbox = new RecordingOutbox(message("event-1", "run.completed"));
        JsonlTranscriptProjector projector =
                projector(outbox, new JsonlTranscriptWriter(directory, new TranscriptWriteHook() {
                    @Override
                    public void afterWriteBeforeForce(SafeTranscriptEvent event) {
                        throw new IllegalStateException("injected before fsync");
                    }
                }));

        assertThatThrownBy(projector::projectPending).hasMessage("injected before fsync");
        assertThat(outbox.consumed).isEmpty();
        assertThat(outbox.published).isEmpty();
    }

    @Test
    void crashAfterForceBeforeAcknowledgementProducesReaderDeduplicatedRetry() {
        RecordingOutbox outbox = new RecordingOutbox(message("event-1", "run.completed"));
        AtomicInteger forceCalls = new AtomicInteger();
        JsonlTranscriptProjector crashing =
                projector(outbox, new JsonlTranscriptWriter(directory, new TranscriptWriteHook() {
                    @Override
                    public void afterForce(SafeTranscriptEvent event) {
                        if (forceCalls.incrementAndGet() == 1) throw new IllegalStateException("crash after force");
                    }
                }));

        assertThatThrownBy(crashing::projectPending).hasMessage("crash after force");
        assertThat(outbox.published).isEmpty();
        assertThat(projector(outbox, new JsonlTranscriptWriter(directory)).projectPending())
                .isEqualTo(1);
        TranscriptReadResult result = new JsonlTranscriptReader(directory).read("run-1");
        assertThat(result.events()).hasSize(1);
        assertThat(result.duplicateCount()).isEqualTo(1);
    }

    @Test
    void unknownEventIsNeitherWrittenNorAcknowledged() {
        RecordingOutbox outbox = new RecordingOutbox(message("event-1", "provider.raw"));
        JsonlTranscriptProjector projector = projector(outbox, new JsonlTranscriptWriter(directory));

        assertThatThrownBy(projector::projectPending)
                .isInstanceOf(TranscriptProjectionException.class)
                .extracting(exception -> ((TranscriptProjectionException) exception).code())
                .isEqualTo(TranscriptDiagnosticCode.UNKNOWN_EVENT_TYPE);
        assertThat(outbox.consumed).isEmpty();
        assertThat(outbox.published).isEmpty();
    }

    private JsonlTranscriptProjector projector(RecordingOutbox outbox, JsonlTranscriptWriter writer) {
        return new JsonlTranscriptProjector(
                outbox, outbox.unitOfWork(), SafeTranscriptMapperRegistry.defaults(), new TranscriptRedactor(), writer);
    }

    private static OutboxMessage message(String id, String type) {
        return new OutboxMessage(
                id, new AgentRunId("run-1"), 1, type, "1", Map.of("status", "COMPLETED", "version", 4), NOW);
    }

    private static final class RecordingOutbox implements RuntimeOutboxPublisher {
        private final List<OutboxMessage> pending = new ArrayList<>();
        private final List<String> consumed = new ArrayList<>();
        private final List<String> published = new ArrayList<>();
        private final AtomicInteger transactionCalls = new AtomicInteger();
        private Runnable onMark = () -> {};

        private RecordingOutbox(OutboxMessage message) {
            pending.add(message);
        }

        private RuntimeUnitOfWork unitOfWork() {
            return new RuntimeUnitOfWork() {
                @Override
                public <T> T execute(java.util.function.Supplier<T> work) {
                    transactionCalls.incrementAndGet();
                    return work.get();
                }
            };
        }

        @Override
        public void append(OutboxMessage message) {
            pending.add(message);
        }

        @Override
        public List<OutboxMessage> pending() {
            return List.copyOf(pending);
        }

        @Override
        public void markPublished(String eventId) {
            onMark.run();
            published.add(eventId);
            pending.removeIf(message -> message.id().equals(eventId));
        }

        @Override
        public boolean markConsumed(String consumerId, String eventId) {
            onMark.run();
            consumed.add(eventId);
            return true;
        }
    }
}
