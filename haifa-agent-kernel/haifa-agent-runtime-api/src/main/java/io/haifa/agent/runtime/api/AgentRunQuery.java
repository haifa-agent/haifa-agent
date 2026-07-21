package io.haifa.agent.runtime.api;

import io.haifa.agent.core.run.AgentRunId;
import java.util.Objects;

/** Query for the latest immutable snapshot of an Agent run. */
public record AgentRunQuery(AgentRunId runId) {

    public AgentRunQuery {
        runId = Objects.requireNonNull(runId, "runId must not be null");
    }
}
