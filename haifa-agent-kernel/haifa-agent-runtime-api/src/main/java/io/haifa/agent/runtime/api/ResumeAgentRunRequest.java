package io.haifa.agent.runtime.api;

import io.haifa.agent.core.run.AgentRunId;
import java.util.Objects;
import java.util.Optional;

/** Request to resume a paused run, optionally with additional human input. */
public record ResumeAgentRunRequest(AgentRunId runId, Optional<String> input) {

    public ResumeAgentRunRequest {
        runId = Objects.requireNonNull(runId, "runId must not be null");
        input = Objects.requireNonNull(input, "input must not be null")
                .map(String::trim)
                .filter(value -> !value.isEmpty());
    }

    public static ResumeAgentRunRequest withoutInput(AgentRunId runId) {
        return new ResumeAgentRunRequest(runId, Optional.empty());
    }
}
