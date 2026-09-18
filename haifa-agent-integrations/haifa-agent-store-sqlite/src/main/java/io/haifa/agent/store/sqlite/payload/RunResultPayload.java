package io.haifa.agent.store.sqlite.payload;

import io.haifa.agent.core.run.AgentRunOutcome;
import io.haifa.agent.core.run.AgentRunResult;
import java.util.List;
import java.util.Map;

public record RunResultPayload(
        String outcome,
        String summary,
        String outputSchemaId,
        String outputSchemaVersion,
        Map<String, Object> structuredOutput,
        List<ArtifactPayload> artifacts,
        List<String> warnings) {

    public static RunResultPayload from(AgentRunResult result) {
        return new RunResultPayload(
                result.outcome().name(),
                result.summary(),
                result.outputSchemaId(),
                result.outputSchemaVersion(),
                result.structuredOutput(),
                result.artifacts().stream().map(ArtifactPayload::from).toList(),
                result.warnings());
    }

    public AgentRunResult toDomain() {
        return new AgentRunResult(
                AgentRunOutcome.valueOf(outcome),
                summary,
                outputSchemaId,
                outputSchemaVersion,
                structuredOutput,
                artifacts.stream().map(ArtifactPayload::toDomain).toList(),
                warnings);
    }
}
