package io.haifa.agent.store.sqlite.payload;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.runtime.api.RuntimeCommand;
import io.haifa.agent.runtime.api.RuntimeCommandArguments;
import io.haifa.agent.runtime.api.RuntimeCommandId;
import io.haifa.agent.runtime.api.RuntimeCommandResult;
import io.haifa.agent.runtime.api.RuntimeCommandStatus;
import io.haifa.agent.runtime.api.RuntimeCommandType;
import java.time.Instant;
import java.util.Map;

/** Closed, constructor-independent representation of a command result. */
public record CommandResultPayload(
        String commandId,
        String runId,
        String commandType,
        String argumentSchemaId,
        String argumentSchemaVersion,
        Map<String, Object> argumentValues,
        String idempotencyKey,
        long requestedAt,
        String status,
        String runStatus,
        long runVersion,
        long runUpdatedAt,
        RunResultPayload result,
        AgentErrorPayload error,
        String output,
        RunUsagePayload usage) {

    public static CommandResultPayload from(RuntimeCommandResult value) {
        RuntimeCommand command = value.command();
        AgentRunSnapshot snapshot = value.snapshot();
        return new CommandResultPayload(
                command.commandId().value(),
                command.runId().value(),
                command.type().name(),
                command.arguments().schemaId(),
                command.arguments().schemaVersion(),
                command.arguments().values(),
                command.idempotencyKey(),
                command.requestedAt().toEpochMilli(),
                value.status().name(),
                snapshot.status().name(),
                snapshot.version(),
                snapshot.updatedAt().toEpochMilli(),
                snapshot.result().map(RunResultPayload::from).orElse(null),
                snapshot.error().map(AgentErrorPayload::from).orElse(null),
                snapshot.output().orElse(null),
                RunUsagePayload.from(snapshot.usage()));
    }

    public RuntimeCommandResult toDomain() {
        AgentRunId id = new AgentRunId(runId);
        RuntimeCommand command = new RuntimeCommand(
                new RuntimeCommandId(commandId),
                id,
                RuntimeCommandType.valueOf(commandType),
                new RuntimeCommandArguments(argumentSchemaId, argumentSchemaVersion, argumentValues),
                idempotencyKey,
                Instant.ofEpochMilli(requestedAt));
        AgentRunSnapshot snapshot = new AgentRunSnapshot(
                id,
                AgentRunStatus.valueOf(runStatus),
                runVersion,
                Instant.ofEpochMilli(runUpdatedAt),
                java.util.Optional.ofNullable(result).map(RunResultPayload::toDomain),
                java.util.Optional.ofNullable(error).map(AgentErrorPayload::toDomain),
                java.util.Optional.ofNullable(output),
                usage == null ? io.haifa.agent.core.run.AgentRunUsage.ZERO : usage.toDomain());
        return new RuntimeCommandResult(command, RuntimeCommandStatus.valueOf(status), snapshot);
    }
}
