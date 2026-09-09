package io.haifa.agent.application.project.artifact;

import io.haifa.agent.artifact.ArtifactId;
import io.haifa.agent.artifact.ArtifactStatus;
import io.haifa.agent.artifact.ArtifactStore;
import io.haifa.agent.artifact.ArtifactVersion;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.runtime.core.completion.CompletionBlocker;
import io.haifa.agent.runtime.core.completion.CompletionPolicy;
import io.haifa.agent.runtime.core.completion.CompletionPolicyResult;
import io.haifa.agent.runtime.core.decision.FinalAnswerDecision;
import java.util.List;
import java.util.Objects;

/** Completion gate backed by authoritative published Artifact state. */
public final class PublishedArtifactRequiredChecker implements CompletionPolicy {
    private final ArtifactStore artifacts;

    public PublishedArtifactRequiredChecker(ArtifactStore artifacts) {
        this.artifacts = Objects.requireNonNull(artifacts);
    }

    @Override
    public CompletionPolicyResult evaluate(AgentRun run, FinalAnswerDecision decision) {
        boolean satisfied = decision.artifacts().stream().allMatch(reference -> {
            long version;
            try {
                version = Long.parseLong(reference.version());
            } catch (NumberFormatException exception) {
                return false;
            }
            return artifacts
                    .find(new ArtifactId(reference.artifactId()), new ArtifactVersion(version))
                    .filter(value -> value.status() == ArtifactStatus.PUBLISHED)
                    .filter(value -> value.type().value().equals(reference.artifactType()))
                    .filter(value -> value.title().equals(reference.title()))
                    .isPresent();
        });
        return satisfied
                ? CompletionPolicyResult.accepted()
                : CompletionPolicyResult.blocked(
                        List.of(CompletionBlocker.recoverable(
                                "REQUIRED_ARTIFACT_MISSING", "A required artifact is missing.", "REQUIRED_ARTIFACT")),
                        List.of());
    }
}
