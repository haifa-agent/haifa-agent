package io.haifa.agent.runtime.api;

import io.haifa.agent.core.content.ContentPart;
import io.haifa.agent.core.run.AgentRunId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/** Durable steer input accepted independently from commands and interaction responses. */
public record RunInputSubmission(
        RunInputId inputId,
        AgentRunId runId,
        OptionalLong expectedRunVersion,
        List<ContentPart> contents,
        String idempotencyKey,
        Instant submittedAt) {
    public RunInputSubmission {
        inputId = Objects.requireNonNull(inputId, "inputId must not be null");
        runId = Objects.requireNonNull(runId, "runId must not be null");
        expectedRunVersion = Objects.requireNonNull(expectedRunVersion, "expectedRunVersion must not be null");
        if (expectedRunVersion.isPresent() && expectedRunVersion.getAsLong() < 0) {
            throw new IllegalArgumentException("expectedRunVersion must not be negative");
        }
        contents = List.copyOf(Objects.requireNonNull(contents, "contents must not be null"));
        if (contents.isEmpty() || contents.size() > 20 || contents.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("contents must contain 1..20 non-null parts");
        }
        long textCharacters = contents.stream()
                .filter(io.haifa.agent.core.content.TextPart.class::isInstance)
                .map(io.haifa.agent.core.content.TextPart.class::cast)
                .mapToLong(part -> part.text().length())
                .sum();
        if (textCharacters > 65_536) throw new IllegalArgumentException("steer text exceeds the public safety budget");
        if (contents.stream()
                .anyMatch(part -> part instanceof io.haifa.agent.core.content.ToolCallPart
                        || part instanceof io.haifa.agent.core.content.ToolResultPart)) {
            throw new IllegalArgumentException("steer input must not contain tool protocol parts");
        }
        idempotencyKey = InteractionOption.requireText(idempotencyKey, "idempotencyKey", 256);
        submittedAt = Objects.requireNonNull(submittedAt, "submittedAt must not be null");
    }
}
