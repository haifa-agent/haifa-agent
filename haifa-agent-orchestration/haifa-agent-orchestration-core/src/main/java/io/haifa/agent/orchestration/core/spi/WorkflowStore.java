package io.haifa.agent.orchestration.core.spi;

import io.haifa.agent.orchestration.api.WorkflowEvent;
import io.haifa.agent.orchestration.api.WorkflowRunId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Authoritative workflow persistence port. Implementations join the caller's unit of work. */
public interface WorkflowStore {
    Optional<StoredWorkflowRun> find(WorkflowRunId runId);

    List<StoredWorkflowRun> recoverable();

    Optional<StoredWorkflowCommand> findCommand(String operation, String scope, String idempotencyKeyDigest);

    void create(StoredWorkflowRun run, StoredWorkflowCommand startCommand, List<WorkflowEvent> events);

    void save(
            long expectedStorageVersion,
            StoredWorkflowRun run,
            List<WorkflowEvent> events,
            Optional<StoredWorkflowCommand> command);

    List<WorkflowEvent> events(WorkflowRunId runId, long afterSequence, int limit);

    List<WorkflowOutboxRecord> pendingOutbox(int limit);

    void markOutboxPublished(WorkflowRunId runId, long sequence, Instant publishedAt);
}
