package io.haifa.agent.core.step;

import io.haifa.agent.core.error.AgentError;
import java.util.Objects;

/** Failure retained by a step without exposing provider exceptions. */
public record AgentStepError(AgentError error) {
    public AgentStepError {
        error = Objects.requireNonNull(error, "error must not be null");
    }
}
