package io.haifa.agent.core.content;

import static io.haifa.agent.core.support.DomainValues.requireText;

import io.haifa.agent.core.reference.ArtifactRef;
import java.util.Objects;

/** Compact child-Agent or tool deliverable content. */
public record ArtifactRefPart(ArtifactRef artifact, String summary) implements ContentPart {
    public ArtifactRefPart {
        artifact = Objects.requireNonNull(artifact, "artifact must not be null");
        summary = requireText(summary, "summary");
    }

    @Override
    public String contentType() {
        return "artifact-ref";
    }
}
