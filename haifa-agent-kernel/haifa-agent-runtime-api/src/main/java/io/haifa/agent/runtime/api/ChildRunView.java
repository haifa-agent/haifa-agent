package io.haifa.agent.runtime.api;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.run.AgentRunUsage;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Read-only projection of one child run delegated by a parent run.
 *
 * <p>It is derived from the child {@code AgentRun} on every query; the Runtime keeps no separate child status.
 * The child's own events are read with {@code events(runId, ...)} and its usage is not included in the parent's.
 */
public record ChildRunView(
        AgentRunId runId,
        AgentRunId parentRunId,
        AgentDefinitionId agentDefinitionId,
        String objective,
        AgentRunStatus status,
        Instant createdAt,
        Optional<Instant> startedAt,
        Optional<Instant> completedAt,
        AgentRunUsage usage) {
    public ChildRunView {
        runId = Objects.requireNonNull(runId, "runId must not be null");
        parentRunId = Objects.requireNonNull(parentRunId, "parentRunId must not be null");
        agentDefinitionId = Objects.requireNonNull(agentDefinitionId, "agentDefinitionId must not be null");
        objective = Objects.requireNonNull(objective, "objective must not be null");
        status = Objects.requireNonNull(status, "status must not be null");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        startedAt = Objects.requireNonNull(startedAt, "startedAt must not be null");
        completedAt = Objects.requireNonNull(completedAt, "completedAt must not be null");
        usage = Objects.requireNonNull(usage, "usage must not be null");
    }

    public static ChildRunView from(AgentRun child) {
        Objects.requireNonNull(child, "child must not be null");
        return new ChildRunView(
                child.id(),
                child.parentRunId().orElseThrow(() -> new IllegalArgumentException("run is not a child run")),
                child.agentDefinitionId(),
                child.objective(),
                child.status(),
                child.createdAt(),
                child.startedAt(),
                child.completedAt(),
                child.usage());
    }
}
