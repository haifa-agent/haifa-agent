package io.haifa.agent.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.ProjectRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ArtifactServiceTest {
    @Test
    void publishesImmutableContentAddressedPayloadWithProvenance() {
        var artifacts = new InMemoryArtifactStore();
        var payloads = new InMemoryArtifactPayloadStore();
        var ids = new AtomicInteger();
        var service = new ArtifactService(
                artifacts,
                payloads,
                () -> "artifact-" + ids.incrementAndGet(),
                () -> Instant.parse("2026-07-21T00:00:00Z"));
        byte[] source = "review patch".getBytes(StandardCharsets.UTF_8);

        Artifact artifact = service.publish(
                new ArtifactType("patch"),
                "Review patch",
                source,
                "text/x-diff",
                new ArtifactProvenance(
                        new ProjectRef("project-1"),
                        "workspace-1",
                        new AgentRunId("run-1"),
                        new AgentSessionId("session-1"),
                        "change-1",
                        "execution-1",
                        "changes/review.patch",
                        "sha256:source",
                        "review-export-v1",
                        new PrincipalRef("author-1", "user")));
        source[0] = 'X';

        assertThat(artifact.status()).isEqualTo(ArtifactStatus.PUBLISHED);
        assertThat(artifact.reference().artifactType()).isEqualTo("patch");
        assertThat(payloads.load(artifact.payload()).orElseThrow())
                .isEqualTo("review patch".getBytes(StandardCharsets.UTF_8));
        assertThat(artifacts.findByProject("project-1")).containsExactly(artifact);
    }
}
