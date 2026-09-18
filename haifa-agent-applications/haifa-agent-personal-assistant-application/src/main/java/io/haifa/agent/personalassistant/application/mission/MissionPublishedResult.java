package io.haifa.agent.personalassistant.application.mission;

import java.util.List;

/** Validated, user-safe final Mission delivery and its immutable Artifact references. */
public record MissionPublishedResult(
        String finalArtifactId,
        List<String> artifactIds,
        List<String> sources,
        String structuredResult,
        String finalMessage,
        String completionKind) {
    public MissionPublishedResult {
        artifactIds = List.copyOf(artifactIds);
        sources = List.copyOf(sources);
        if (!("COMPLETE".equals(completionKind) || "PARTIAL".equals(completionKind))) {
            throw new IllegalArgumentException("completionKind must be COMPLETE or PARTIAL");
        }
    }
}
