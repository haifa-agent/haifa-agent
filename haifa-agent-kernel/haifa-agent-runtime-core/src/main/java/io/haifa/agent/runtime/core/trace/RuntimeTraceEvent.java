package io.haifa.agent.runtime.core.trace;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.step.AgentStepId;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.runtime.core.attempt.ExecutionAttemptId;
import io.haifa.agent.runtime.core.middleware.RuntimePhase;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Provider-neutral, redaction-safe runtime trace envelope distinct from domain and audit events. */
public record RuntimeTraceEvent(
        String traceId,
        AgentRunId runId,
        Optional<ExecutionAttemptId> attemptId,
        AgentSessionId sessionId,
        Optional<AgentStepId> stepId,
        Optional<ToolCallId> toolCallId,
        Optional<String> workerId,
        int iteration,
        RuntimePhase phase,
        String operation,
        Map<String, Object> safeAttributes,
        Instant occurredAt) {
    public RuntimeTraceEvent {
        traceId = requireText(traceId, "traceId");
        runId = Objects.requireNonNull(runId, "runId must not be null");
        attemptId = Objects.requireNonNull(attemptId, "attemptId must not be null");
        sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        stepId = Objects.requireNonNull(stepId, "stepId must not be null");
        toolCallId = Objects.requireNonNull(toolCallId, "toolCallId must not be null");
        workerId = Objects.requireNonNull(workerId, "workerId must not be null")
                .map(value -> requireText(value, "workerId"));
        if (iteration < 0) throw new IllegalArgumentException("iteration must not be negative");
        phase = Objects.requireNonNull(phase, "phase must not be null");
        operation = requireText(operation, "operation");
        safeAttributes = Map.copyOf(Objects.requireNonNull(safeAttributes, "safeAttributes must not be null"));
        for (String key : safeAttributes.keySet()) {
            String normalized = key.toLowerCase(Locale.ROOT);
            if (normalized.contains("prompt")
                    || normalized.contains("secret")
                    || normalized.contains("apikey")
                    || normalized.contains("api_key")
                    || normalized.contains("arguments")
                    || normalized.contains("rawresponse")) {
                throw new IllegalArgumentException("unsafe trace attribute key: " + key);
            }
        }
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
