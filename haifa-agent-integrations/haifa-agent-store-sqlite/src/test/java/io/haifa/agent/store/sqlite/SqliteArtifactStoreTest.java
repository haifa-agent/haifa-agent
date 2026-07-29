package io.haifa.agent.store.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.artifact.Artifact;
import io.haifa.agent.artifact.ArtifactId;
import io.haifa.agent.artifact.ArtifactPayloadRef;
import io.haifa.agent.artifact.ArtifactProvenance;
import io.haifa.agent.artifact.ArtifactService;
import io.haifa.agent.artifact.ArtifactStatus;
import io.haifa.agent.artifact.ArtifactType;
import io.haifa.agent.artifact.ArtifactVersion;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.ProjectRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteArtifactStoreTest {
    @TempDir
    Path directory;

    @Test
    void publishesListsLoadsAndRecoversAfterReopen() {
        AtomicInteger ids = new AtomicInteger();
        Artifact published;
        byte[] source = "artifact body".getBytes(StandardCharsets.UTF_8);

        try (SqliteStoreFoundation foundation = SqliteTestSupport.foundation(directory)) {
            ArtifactService service = service(foundation, () -> "artifact-" + ids.incrementAndGet());
            published = service.publish(
                    new ArtifactType("document"), "First", source, "text/plain", provenance("project-1"));
            Artifact second = service.publish(
                    new ArtifactType("document"),
                    "Second",
                    "second body".getBytes(StandardCharsets.UTF_8),
                    "text/plain",
                    provenance("project-1"));
            source[0] = 'X';

            assertThat(foundation.artifacts().find(published.id(), published.version()))
                    .contains(published);
            assertThat(foundation.artifacts().findByProject("project-1")).containsExactly(published, second);
            byte[] loaded =
                    foundation.artifactPayloads().load(published.payload()).orElseThrow();
            assertThat(loaded).isEqualTo("artifact body".getBytes(StandardCharsets.UTF_8));
            loaded[0] = 'X';
            assertThat(foundation.artifactPayloads().load(published.payload()).orElseThrow())
                    .isEqualTo("artifact body".getBytes(StandardCharsets.UTF_8));
        }

        try (SqliteStoreFoundation reopened = SqliteTestSupport.foundation(directory)) {
            assertThat(reopened.artifacts().find(published.id(), published.version()))
                    .contains(published);
            assertThat(reopened.artifactPayloads().load(published.payload()).orElseThrow())
                    .isEqualTo("artifact body".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void compensatesDuplicateMetadataWithoutDeletingSharedPayload() throws Exception {
        try (SqliteStoreFoundation foundation = SqliteTestSupport.foundation(directory)) {
            ArtifactService service = service(foundation, () -> "artifact-shared");
            Artifact first = service.publish(
                    new ArtifactType("document"),
                    "First",
                    "shared".getBytes(StandardCharsets.UTF_8),
                    "text/plain",
                    provenance("project-1"));

            assertThatThrownBy(() -> service.publish(
                            new ArtifactType("document"),
                            "Duplicate",
                            "shared".getBytes(StandardCharsets.UTF_8),
                            "text/markdown",
                            provenance("project-1")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("ARTIFACT_VERSION_ALREADY_EXISTS")
                    .hasMessageNotContaining("shared");

            assertThat(foundation.artifactPayloads().load(first.payload()))
                    .contains("shared".getBytes(StandardCharsets.UTF_8));
            assertThat(referenceCount(foundation, first.payload().payloadId())).isEqualTo(1);
        }
    }

    @Test
    void rejectsOversizedMismatchedAndCorruptPayloads() throws Exception {
        try (SqliteStoreFoundation foundation = SqliteTestSupport.foundation(directory)) {
            SqliteArtifactPayloadStore payloads = foundation.artifactPayloads();
            assertThatThrownBy(() -> payloads.put(new byte[8_193], "application/octet-stream"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("ARTIFACT_PAYLOAD_TOO_LARGE");

            ArtifactPayloadRef reference = payloads.put("safe".getBytes(StandardCharsets.UTF_8), "text/plain");
            ArtifactPayloadRef mismatched =
                    new ArtifactPayloadRef(reference.payloadId(), "sha256:" + "0".repeat(64), 4, "text/plain");
            assertThatThrownBy(() -> payloads.load(mismatched))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("ARTIFACT_PAYLOAD_CORRUPT");

            updatePayload(foundation, reference.payloadId(), "evil".getBytes(StandardCharsets.UTF_8));
            assertThatThrownBy(() -> payloads.load(reference))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("ARTIFACT_PAYLOAD_CORRUPT")
                    .hasMessageNotContaining("evil");
        }
    }

    @Test
    void failsClosedForCorruptMetadataAndRollsBackNestedCreate() throws Exception {
        try (SqliteStoreFoundation foundation = SqliteTestSupport.foundation(directory)) {
            ArtifactService service = service(foundation, () -> "artifact-corrupt");
            Artifact published = service.publish(
                    new ArtifactType("document"),
                    "Valid",
                    "safe".getBytes(StandardCharsets.UTF_8),
                    "text/plain",
                    provenance("project-1"));
            updateTitle(foundation, published.id().value(), "");

            assertThatThrownBy(() -> foundation.artifacts().find(published.id(), published.version()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("ARTIFACT_METADATA_CORRUPT");

            ArtifactPayloadRef payload =
                    foundation.artifactPayloads().put("rollback".getBytes(StandardCharsets.UTF_8), "text/plain");
            Artifact rolledBack = new Artifact(
                    new ArtifactId("artifact-rollback"),
                    new ArtifactVersion(1),
                    new ArtifactType("document"),
                    "Rollback",
                    payload,
                    provenance("project-2"),
                    ArtifactStatus.PUBLISHED,
                    SqliteTestSupport.NOW);
            assertThatThrownBy(() -> foundation.unitOfWork().execute(() -> {
                        foundation.artifacts().create(rolledBack);
                        throw new IllegalStateException("force rollback");
                    }))
                    .isInstanceOf(SqliteStoreException.class);
            assertThat(foundation.artifacts().find(rolledBack.id(), rolledBack.version()))
                    .isEmpty();
        }
    }

    private static ArtifactService service(
            SqliteStoreFoundation foundation, io.haifa.agent.common.id.IdentifierGenerator ids) {
        return new ArtifactService(
                foundation.artifacts(), foundation.artifactPayloads(), ids, () -> SqliteTestSupport.NOW);
    }

    private static ArtifactProvenance provenance(String projectId) {
        return new ArtifactProvenance(
                new ProjectRef(projectId),
                "workspace-1",
                new AgentRunId("run-1"),
                new AgentSessionId("session-1"),
                "change-1",
                "execution-1",
                "outputs/result.txt",
                "sha256:source",
                "local-export-v1",
                new PrincipalRef("author-1", "user"));
    }

    private static long referenceCount(SqliteStoreFoundation foundation, String payloadId) throws Exception {
        try (Connection connection = foundation.connections().openConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT reference_count FROM artifact_payload WHERE payload_id=?")) {
            statement.setString(1, payloadId);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong(1);
            }
        }
    }

    private static void updatePayload(SqliteStoreFoundation foundation, String payloadId, byte[] payload)
            throws Exception {
        try (Connection connection = foundation.connections().openConnection();
                PreparedStatement statement =
                        connection.prepareStatement("UPDATE artifact_payload SET payload=? WHERE payload_id=?")) {
            statement.setBytes(1, payload);
            statement.setString(2, payloadId);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private static void updateTitle(SqliteStoreFoundation foundation, String artifactId, String title)
            throws Exception {
        try (Connection connection = foundation.connections().openConnection();
                PreparedStatement statement =
                        connection.prepareStatement("UPDATE artifact_record SET title=? WHERE artifact_id=?")) {
            statement.setString(1, title);
            statement.setString(2, artifactId);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }
}
