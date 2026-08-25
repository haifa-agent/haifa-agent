package io.haifa.agent.runtime.core.trace;

import io.haifa.agent.runtime.core.attempt.ExecutionAttemptId;
import java.util.Objects;
import java.util.Optional;

/** Process-local correlation shared by every trace event emitted for one physical Attempt. */
public record RuntimeTraceContext(String traceId, Optional<ExecutionAttemptId> attemptId, Optional<String> workerId) {
    public RuntimeTraceContext {
        traceId = requireText(traceId, "traceId");
        attemptId = Objects.requireNonNull(attemptId, "attemptId must not be null");
        workerId = Objects.requireNonNull(workerId, "workerId must not be null")
                .map(value -> requireText(value, "workerId"));
    }

    public static RuntimeTraceContext forAttempt(
            String traceId, ExecutionAttemptId attemptId, Optional<String> workerId) {
        return new RuntimeTraceContext(traceId, Optional.of(attemptId), workerId);
    }

    public static RuntimeTraceContext detached(String traceId) {
        return new RuntimeTraceContext(traceId, Optional.empty(), Optional.empty());
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
