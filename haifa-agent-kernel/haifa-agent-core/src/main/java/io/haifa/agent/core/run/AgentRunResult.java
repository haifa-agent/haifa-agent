package io.haifa.agent.core.run;

import static io.haifa.agent.core.support.DomainValues.immutableMap;
import static io.haifa.agent.core.support.DomainValues.requireText;

import io.haifa.agent.core.reference.ArtifactRef;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Final structured result of a run; distinct from a message or Runtime snapshot. */
public record AgentRunResult(
        AgentRunOutcome outcome,
        String summary,
        String outputSchemaId,
        String outputSchemaVersion,
        Map<String, Object> structuredOutput,
        List<ArtifactRef> artifacts,
        List<String> warnings) {

    public AgentRunResult {
        outcome = Objects.requireNonNull(outcome, "outcome must not be null");
        summary = requireText(summary, "summary");
        outputSchemaId = requireText(outputSchemaId, "outputSchemaId");
        outputSchemaVersion = requireText(outputSchemaVersion, "outputSchemaVersion");
        structuredOutput = immutableMap(structuredOutput, "structuredOutput");
        artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts must not be null"));
        warnings = Objects.requireNonNull(warnings, "warnings must not be null").stream()
                .map(value -> requireText(value, "warning"))
                .toList();
    }
}
