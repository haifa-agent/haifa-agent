package io.haifa.agent.core.step;

import static io.haifa.agent.core.support.DomainValues.immutableMap;
import static io.haifa.agent.core.support.DomainValues.requireText;

import io.haifa.agent.core.reference.ArtifactRef;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Compact, structured outcome of a persisted step. */
public record AgentStepResult(String summary, Map<String, Object> data, List<ArtifactRef> artifacts) {
    public AgentStepResult {
        summary = requireText(summary, "summary");
        data = immutableMap(data, "data");
        artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts must not be null"));
    }
}
