package io.haifa.agent.runtime.api;

import io.haifa.agent.core.error.AgentError;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunResult;
import io.haifa.agent.core.run.AgentRunStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Latest immutable-facing Runtime view; distinct from Core's final AgentRunResult. */
public record AgentRunSnapshot(
        AgentRunId runId,
        AgentRunStatus status,
        long version,
        Instant updatedAt,
        Optional<AgentRunResult> result,
        Optional<AgentError> error,
        Optional<String> output) {

    public AgentRunSnapshot {
        runId = Objects.requireNonNull(runId, "runId must not be null");
        status = Objects.requireNonNull(status, "status must not be null");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        result = Objects.requireNonNull(result, "result must not be null");
        error = Objects.requireNonNull(error, "error must not be null");
        if (result.isPresent() && error.isPresent()) {
            throw new IllegalArgumentException("snapshot cannot contain both result and error");
        }
        output = Objects.requireNonNull(output, "output must not be null")
                .map(String::trim)
                .filter(value -> !value.isEmpty());
    }

    public static AgentRunSnapshot from(AgentRun run) {
        return from(run, Optional.empty());
    }

    public static AgentRunSnapshot from(AgentRun run, Optional<String> output) {
        Objects.requireNonNull(run, "run must not be null");
        return new AgentRunSnapshot(
                run.id(), run.status(), run.version(), run.updatedAt(), run.result(), run.error(), output);
    }
}
