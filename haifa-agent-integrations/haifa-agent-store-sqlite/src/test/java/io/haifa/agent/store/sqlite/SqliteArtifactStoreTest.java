package io.haifa.agent.store.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.artifact.ArtifactProvenance;
import io.haifa.agent.artifact.ArtifactService;
import io.haifa.agent.artifact.ArtifactType;
import io.haifa.agent.common.id.UuidV7IdentifierGenerator;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.ProjectRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteArtifactStoreTest {
    @Test
    void persistsVerifiedMetadataAndOpaquePayload(@TempDir Path directory) throws Exception {
        Path database = directory.resolve("runtime.sqlite").toAbsolutePath();
        try (SqliteStoreFoundation foundation = SqliteStoreFoundation.initialize(
                SqliteStoreConfiguration.defaults(database), SqliteTestSupport.CLOCK)) {
            var metadata = foundation.artifacts();
            var payloads = foundation.artifactPayloads();
            var service = new ArtifactService(
                    metadata, payloads, new UuidV7IdentifierGenerator(), SqliteTestSupport.CLOCK::instant);
            byte[] content = "# Verified report".getBytes(StandardCharsets.UTF_8);
            var artifact = service.publish(
                    new ArtifactType("research-report"),
                    "research-report.md",
                    content,
                    "text/markdown; charset=utf-8",
                    provenance());

            assertThat(metadata.find(artifact.id(), artifact.version())).contains(artifact);
            assertThat(metadata.findByProject("mission-test")).containsExactly(artifact);
            assertThat(payloads.load(artifact.payload())).contains(content);
            assertThat(Files.list(directory.resolve("artifacts")).toList()).hasSize(1);
            assertThatThrownBy(() -> payloads.put(content, "text/html")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> payloads.put(new byte[1024 * 1024 + 1], "application/json"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exceeds limit");
        }
    }

    private static ArtifactProvenance provenance() {
        return new ArtifactProvenance(
                new ProjectRef("mission-test"),
                "personal-assistant",
                new AgentRunId("run-test"),
                new AgentSessionId("session-test"),
                null,
                "mission:test:synthesis:v1",
                "research-report.md",
                "sha256:source",
                "owner-only",
                new PrincipalRef("public-user", "user"));
    }
}
