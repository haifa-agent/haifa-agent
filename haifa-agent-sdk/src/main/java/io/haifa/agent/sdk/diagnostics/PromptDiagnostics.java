package io.haifa.agent.sdk.diagnostics;

import io.haifa.agent.core.run.AgentRunId;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/** Redacted, read-only and process-local Prompt composition evidence. */
public record PromptDiagnostics(
        AgentRunId runId,
        boolean available,
        String statusCode,
        OptionalInt iteration,
        List<PromptDiagnosticComponent> components) {
    public static final String AVAILABLE = "PROMPT_DIAGNOSTICS_AVAILABLE";
    public static final String UNAVAILABLE = "PROMPT_DIAGNOSTICS_UNAVAILABLE";

    public PromptDiagnostics {
        runId = Objects.requireNonNull(runId, "runId must not be null");
        statusCode = Objects.requireNonNull(statusCode, "statusCode must not be null")
                .trim();
        iteration = Objects.requireNonNull(iteration, "iteration must not be null");
        components = List.copyOf(Objects.requireNonNull(components, "components must not be null"));
        if (available != iteration.isPresent()) {
            throw new IllegalArgumentException("available diagnostics must include an iteration");
        }
        if (!available && !components.isEmpty()) {
            throw new IllegalArgumentException("unavailable diagnostics cannot include components");
        }
    }

    public static PromptDiagnostics unavailable(AgentRunId runId) {
        return new PromptDiagnostics(runId, false, UNAVAILABLE, OptionalInt.empty(), List.of());
    }

    public static PromptDiagnostics available(
            AgentRunId runId, int iteration, List<PromptDiagnosticComponent> components) {
        return new PromptDiagnostics(runId, true, AVAILABLE, OptionalInt.of(iteration), components);
    }
}
