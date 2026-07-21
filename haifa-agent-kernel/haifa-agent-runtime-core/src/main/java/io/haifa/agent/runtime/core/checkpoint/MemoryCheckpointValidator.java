package io.haifa.agent.runtime.core.checkpoint;

import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.memory.api.MemoryAuditEvent;
import io.haifa.agent.memory.api.MemoryAuditSink;
import io.haifa.agent.memory.api.MemoryRetriever;
import java.util.Map;
import java.util.Optional;

/** Reauthorizes checkpoint Memory references and emits safe audit evidence when rebuilt Context changes. */
public final class MemoryCheckpointValidator {
    private final MemoryRetriever memories;
    private final MemoryAuditSink audit;
    private final TimeProvider time;

    public MemoryCheckpointValidator(MemoryRetriever memories, MemoryAuditSink audit, TimeProvider time) {
        this.memories = java.util.Objects.requireNonNull(memories);
        this.audit = java.util.Objects.requireNonNull(audit);
        this.time = java.util.Objects.requireNonNull(time);
    }

    public void validate(AgentRun run, RuntimeCheckpointState checkpoint) {
        checkpoint.selectedMemories().forEach(reference -> {
            boolean available = memories.findAuthorized(
                            reference.id(), reference.version(), run.tenant(), run.principal(), time.now())
                    .isPresent();
            if (!available) {
                audit.record(new MemoryAuditEvent(
                        "checkpoint.memory-selection-changed",
                        Optional.empty(),
                        Optional.of(new io.haifa.agent.memory.api.MemoryRef(reference.id(), reference.version())),
                        reference.scope(),
                        run.principal().principalId(),
                        Map.of(
                                "reason", "memory-no-longer-authorized-or-active",
                                "queryDigest", checkpoint.memoryQueryDigest(),
                                "policyVersion", checkpoint.memoryRetrievalPolicyVersion()),
                        time.now()));
            }
        });
    }
}
