package io.haifa.agent.store.sqlite.payload;

import io.haifa.agent.core.step.AgentStepResult;
import java.util.List;
import java.util.Map;

public record StepResultPayload(String summary, Map<String, Object> data, List<ArtifactPayload> artifacts) {
    public static StepResultPayload from(AgentStepResult value) {
        return new StepResultPayload(
                value.summary(),
                value.data(),
                value.artifacts().stream().map(ArtifactPayload::from).toList());
    }

    public AgentStepResult toDomain() {
        return new AgentStepResult(
                summary, data, artifacts.stream().map(ArtifactPayload::toDomain).toList());
    }
}
