package io.haifa.agent.runtime.api;

import java.util.Objects;

/** Result of applying a Runtime control command. */
public record RuntimeCommandResult(RuntimeCommand command, RuntimeCommandStatus status, AgentRunSnapshot snapshot) {

    public RuntimeCommandResult {
        command = Objects.requireNonNull(command, "command must not be null");
        status = Objects.requireNonNull(status, "status must not be null");
        snapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
        if (!command.runId().equals(snapshot.runId())) {
            throw new IllegalArgumentException("command and snapshot must refer to the same run");
        }
    }
}
