package io.haifa.agent.execution.api;

import java.util.Objects;

/** A long-running, bidirectional process request authorized through the existing broker. */
public record ManagedProcessRequest(ExecutionRequest execution) {
    public ManagedProcessRequest {
        Objects.requireNonNull(execution, "execution");
    }
}
