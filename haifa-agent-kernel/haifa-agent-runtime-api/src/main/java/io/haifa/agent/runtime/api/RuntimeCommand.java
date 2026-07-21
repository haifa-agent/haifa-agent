package io.haifa.agent.runtime.api;

import io.haifa.agent.core.run.AgentRunId;
import java.time.Instant;
import java.util.Objects;

/** Control command addressed to a running Agent execution. */
public record RuntimeCommand(
        RuntimeCommandId commandId,
        AgentRunId runId,
        RuntimeCommandType type,
        RuntimeCommandArguments arguments,
        String idempotencyKey,
        Instant requestedAt) {

    public RuntimeCommand {
        commandId = Objects.requireNonNull(commandId, "commandId must not be null");
        runId = Objects.requireNonNull(runId, "runId must not be null");
        type = Objects.requireNonNull(type, "type must not be null");
        arguments = Objects.requireNonNull(arguments, "arguments must not be null");
        if (!arguments.equals(RuntimeCommandArguments.NONE)) {
            throw new IllegalArgumentException(type + " does not accept arguments in Runtime API version 1.0");
        }
        idempotencyKey = requireText(idempotencyKey);
        requestedAt = Objects.requireNonNull(requestedAt, "requestedAt must not be null");
    }

    private static String requireText(String value) {
        String normalized =
                Objects.requireNonNull(value, "idempotencyKey must not be null").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        return normalized;
    }
}
