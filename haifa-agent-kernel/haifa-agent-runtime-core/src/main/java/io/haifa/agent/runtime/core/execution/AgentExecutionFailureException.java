package io.haifa.agent.runtime.core.execution;

import io.haifa.agent.core.error.AgentError;
import java.util.Objects;

/** Internal carrier that preserves one classified failure across Runtime layers. */
public final class AgentExecutionFailureException extends RuntimeException {
    private final AgentError error;

    public AgentExecutionFailureException(AgentError error, RuntimeException cause) {
        super(Objects.requireNonNull(error, "error must not be null").message(), Objects.requireNonNull(cause));
        this.error = error;
    }

    public AgentError error() {
        return error;
    }
}
