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
import java.util.OptionalInt;

/** Provider-neutral, redaction-safe runtime trace envelope distinct from domain and audit events. */
public record RuntimeTraceEvent(
        String traceId,
        AgentRunId runId,
        Optional<ExecutionAttemptId> attemptId,
        AgentSessionId sessionId,
        Optional<AgentStepId> stepId,
        Optional<ToolCallId> toolCallId,
        Optional<String> workerId,
        OptionalInt iteration,
        RuntimePhase phase,
        String operation,
        RuntimeTraceScope scope,
        RuntimeTraceStatus status,
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
        iteration = Objects.requireNonNull(iteration, "iteration must not be null");
        if (iteration.isPresent() && iteration.getAsInt() < 1) {
            throw new IllegalArgumentException("iteration must be positive when present");
        }
        phase = Objects.requireNonNull(phase, "phase must not be null");
        operation = requireText(operation, "operation");
        scope = Objects.requireNonNull(scope, "scope must not be null");
        status = Objects.requireNonNull(status, "status must not be null");
        if ((scope == RuntimeTraceScope.ITERATION
                        || scope == RuntimeTraceScope.STEP
                        || scope == RuntimeTraceScope.TOOL_CALL)
                && iteration.isEmpty()) {
            throw new IllegalArgumentException("iteration must be present for iteration, step and tool-call scopes");
        }
        if ((scope == RuntimeTraceScope.STEP || scope == RuntimeTraceScope.TOOL_CALL) && stepId.isEmpty()) {
            throw new IllegalArgumentException("stepId must be present for step and tool-call scopes");
        }
        if (scope == RuntimeTraceScope.TOOL_CALL && toolCallId.isEmpty()) {
            throw new IllegalArgumentException("toolCallId must be present for tool-call scope");
        }
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
