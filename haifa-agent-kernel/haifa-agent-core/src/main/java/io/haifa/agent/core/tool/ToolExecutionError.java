package io.haifa.agent.core.tool;

import io.haifa.agent.core.error.AgentError;
import java.util.Objects;

/** Safe provider-neutral error recorded for a failed tool call. */
public record ToolExecutionError(AgentError error) {
    public ToolExecutionError {
        error = Objects.requireNonNull(error, "error must not be null");
    }
}
