package io.haifa.agent.runtime.api;

import io.haifa.agent.core.run.RunId;
import java.util.Objects;

/** Control command addressed to a running Agent execution. */
public record RuntimeCommand(RunId runId, RuntimeCommandType type) {

    public RuntimeCommand {
        runId = Objects.requireNonNull(runId, "runId must not be null");
        type = Objects.requireNonNull(type, "type must not be null");
    }
}
