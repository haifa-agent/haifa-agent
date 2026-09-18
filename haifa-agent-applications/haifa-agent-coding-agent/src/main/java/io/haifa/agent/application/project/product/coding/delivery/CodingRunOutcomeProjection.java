package io.haifa.agent.application.project.product.coding.delivery;

import io.haifa.agent.core.run.AgentRunId;
import java.util.List;
import java.util.Objects;

/** Product projection over authoritative Run facts; it introduces no second Core Run terminal state. */
public record CodingRunOutcomeProjection(
        AgentRunId runId, CodingRunProtocolStatus protocolStatus, List<String> diagnosticCodes) {
    public CodingRunOutcomeProjection {
        runId = Objects.requireNonNull(runId, "runId must not be null");
        protocolStatus = Objects.requireNonNull(protocolStatus, "protocolStatus must not be null");
        diagnosticCodes = bounded(diagnosticCodes, "diagnosticCodes");
    }

    private static List<String> bounded(List<String> values, String field) {
        List<String> copy = List.copyOf(Objects.requireNonNull(values, field + " must not be null"));
        if (copy.size() > 32
                || copy.stream().anyMatch(value -> value == null || !value.matches("[A-Z0-9_.:-]{1,96}"))) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return copy;
    }
}
