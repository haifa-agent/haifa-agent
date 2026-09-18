package io.haifa.agent.sdk.api;

import io.haifa.agent.core.error.AgentError;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.sdk.internal.StructuredOutputRecords;
import java.util.Objects;
import java.util.Optional;

/** Terminal response whose value was schema-validated by Runtime and decoded from the persisted Run result. */
public final class AgentResponse<T extends Record> {
    private final AgentSessionId sessionId;
    private final AgentRunId runId;
    private final AgentRunStatus status;
    private final Optional<T> value;
    private final Optional<AgentError> error;

    private AgentResponse(
            AgentSessionId sessionId,
            AgentRunId runId,
            AgentRunStatus status,
            Optional<T> value,
            Optional<AgentError> error) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        this.runId = Objects.requireNonNull(runId, "runId must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.value = Objects.requireNonNull(value, "value must not be null");
        this.error = Objects.requireNonNull(error, "error must not be null");
        if (!status.isTerminal()) throw new IllegalArgumentException("agent response must be terminal");
    }

    static <T extends Record> AgentResponse<T> from(
            AgentSessionId sessionId, AgentRunSnapshot snapshot, Class<T> responseType) {
        Optional<T> value = snapshot.result().map(result -> {
            var requirement = StructuredOutputRecords.requirement(responseType);
            if (!requirement.schemaId().equals(result.outputSchemaId())
                    || !requirement.schemaVersion().equals(result.outputSchemaVersion())) {
                throw new HaifaAgentException(
                        "STRUCTURED_OUTPUT_UNAVAILABLE",
                        "chat.structured-output",
                        snapshot.runId().value(),
                        "STRUCTURED_OUTPUT_UNAVAILABLE");
            }
            try {
                return StructuredOutputRecords.decode(responseType, result.structuredOutput());
            } catch (IllegalArgumentException exception) {
                throw new HaifaAgentException(
                        "STRUCTURED_OUTPUT_INVALID",
                        "chat.structured-output",
                        snapshot.runId().value(),
                        "STRUCTURED_OUTPUT_INVALID");
            }
        });
        return new AgentResponse<>(sessionId, snapshot.runId(), snapshot.status(), value, snapshot.error());
    }

    public AgentSessionId sessionId() {
        return sessionId;
    }

    public AgentRunId runId() {
        return runId;
    }

    public AgentRunStatus status() {
        return status;
    }

    public T value() {
        return value.orElseThrow(() -> new HaifaAgentException(
                "STRUCTURED_OUTPUT_UNAVAILABLE",
                "chat.structured-output",
                runId.value(),
                "STRUCTURED_OUTPUT_UNAVAILABLE"));
    }

    public Optional<AgentError> error() {
        return error;
    }
}
