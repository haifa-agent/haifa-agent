package io.haifa.agent.runtime.core.decision;

import io.haifa.agent.core.reference.ArtifactRef;
import io.haifa.agent.core.run.AgentRunOutcome;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record FinalAnswerDecision(
        AgentRunOutcome outcome,
        String summary,
        String outputSchemaId,
        String outputSchemaVersion,
        Map<String, Object> structuredOutput,
        List<ArtifactRef> artifacts,
        List<String> warnings)
        implements AgentDecision {
    public FinalAnswerDecision {
        outcome = Objects.requireNonNull(outcome, "outcome must not be null");
        summary = requireText(summary, "summary");
        outputSchemaId = requireText(outputSchemaId, "outputSchemaId");
        outputSchemaVersion = requireText(outputSchemaVersion, "outputSchemaVersion");
        structuredOutput = Map.copyOf(Objects.requireNonNull(structuredOutput, "structuredOutput must not be null"));
        artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts must not be null"));
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings must not be null"));
    }

    private static String requireText(String value, String field) {
        String normalized =
                Objects.requireNonNull(value, field + " must not be null").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
