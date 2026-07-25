package io.haifa.agent.store.jsonl;

import io.haifa.agent.runtime.core.storage.OutboxMessage;
import io.haifa.agent.runtime.core.storage.RuntimeOutboxPublisher;
import io.haifa.agent.runtime.core.storage.RuntimeUnitOfWork;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** At-least-once Outbox projector. SQLite acknowledgement always follows a durable JSONL line. */
public final class JsonlTranscriptProjector {
    public static final String CONSUMER_ID = "jsonl-transcript";

    private final RuntimeOutboxPublisher outbox;
    private final RuntimeUnitOfWork unitOfWork;
    private final SafeTranscriptMapperRegistry mappers;
    private final TranscriptRedactor redactor;
    private final JsonlTranscriptWriter writer;

    public JsonlTranscriptProjector(
            RuntimeOutboxPublisher outbox,
            RuntimeUnitOfWork unitOfWork,
            SafeTranscriptMapperRegistry mappers,
            TranscriptRedactor redactor,
            JsonlTranscriptWriter writer) {
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        this.mappers = Objects.requireNonNull(mappers, "mappers must not be null");
        this.redactor = Objects.requireNonNull(redactor, "redactor must not be null");
        this.writer = Objects.requireNonNull(writer, "writer must not be null");
    }

    public synchronized int projectPending() {
        final List<OutboxMessage> pending;
        try {
            pending = outbox.pending().stream()
                    .sorted(Comparator.comparing(
                                    (OutboxMessage message) -> message.runId().value())
                            .thenComparingLong(OutboxMessage::sequence))
                    .toList();
        } catch (RuntimeException exception) {
            throw new TranscriptProjectionException(
                    TranscriptDiagnosticCode.SOURCE_UNAVAILABLE,
                    "runtime Outbox is unavailable; JSONL cannot act as a source",
                    exception);
        }
        int projected = 0;
        for (OutboxMessage message : pending) {
            SafeTranscriptEvent safe = redactor.redact(mappers.map(message));
            writer.appendAndForce(safe);
            try {
                unitOfWork.execute(() -> {
                    outbox.markConsumed(CONSUMER_ID, message.id());
                    outbox.markPublished(message.id());
                    return null;
                });
            } catch (RuntimeException exception) {
                throw new TranscriptProjectionException(
                        TranscriptDiagnosticCode.SOURCE_UNAVAILABLE,
                        "durable transcript line was not acknowledged by the Runtime source",
                        exception);
            }
            projected++;
        }
        return projected;
    }
}
