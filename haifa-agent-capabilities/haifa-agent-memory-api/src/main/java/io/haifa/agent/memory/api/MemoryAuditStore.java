package io.haifa.agent.memory.api;

import java.util.Optional;

/** Internal audit/idempotency port. It is intentionally not an administrative query API. */
public interface MemoryAuditStore extends MemoryAuditSink {
    Optional<MemoryAuditEvent> findByIdempotency(MemoryScope scope, String operation, String idempotencyKeyDigest);
}
