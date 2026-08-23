package io.haifa.agent.application.project.product.coding.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.project.changeset.FileChange;
import io.haifa.agent.project.changeset.FileChangeSet;
import io.haifa.agent.project.changeset.FileChangeSetId;
import io.haifa.agent.project.changeset.FileChangeType;
import io.haifa.agent.project.changeset.FileVersion;
import io.haifa.agent.project.changeset.InMemoryFileChangeSetStore;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.filesystem.FileType;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.project.workspace.WorkspaceRevision;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CodingChangeReviewArtifactTest {
    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    @Test
    void deterministicallySummarizesNonGitCreateDeleteBinaryAndOversizeChanges() {
        var store = new InMemoryFileChangeSetStore();
        FileChangeSet changeSet = applied(
                "change-1",
                List.of(
                        new FileChange(FileChangeType.CREATE, ProjectPath.of("src/new.py"), null, null, file(32, "a")),
                        new FileChange(
                                FileChangeType.DELETE, ProjectPath.of("assets/old.bin"), null, file(64, "b"), null),
                        new FileChange(
                                FileChangeType.REPLACE,
                                ProjectPath.of("generated/large.txt"),
                                null,
                                file(2_000, "c"),
                                file(3_000, "d"))));
        store.create(changeSet);

        var factory = new CodingChangeReviewArtifactFactory(
                store,
                (ignoredSet, change) -> change.path().value().endsWith(".bin")
                        ? CodingChangeContentKind.BINARY
                        : CodingChangeContentKind.TEXT,
                1_024);

        CodingChangeReviewArtifact artifact =
                factory.create("run-1", List.of("change-1")).orElseThrow();

        assertThat(artifact.schemaVersion()).isEqualTo("coding-change-review/1");
        assertThat(artifact.artifactRef()).matches("sha256:[0-9a-f]{64}");
        assertThat(artifact.baseWorkspaceDigest()).isEqualTo(digest('1'));
        assertThat(artifact.resultWorkspaceDigest()).isEqualTo(digest('2'));
        assertThat(artifact.changeSetIds()).containsExactly("change-1");
        assertThat(artifact.fileSummaries())
                .extracting(CodingChangeReviewArtifact.FileSummary::changeType)
                .containsExactly(FileChangeType.CREATE, FileChangeType.DELETE, FileChangeType.REPLACE);
        assertThat(artifact.counts())
                .containsEntry("created", 1)
                .containsEntry("deleted", 1)
                .containsEntry("binary", 1)
                .containsEntry("oversize", 1);
        assertThat(CodingChangeReviewArtifact.fromStructuredData(artifact.toStructuredData()))
                .contains(artifact);

        var tampered = new java.util.LinkedHashMap<String, Object>(artifact.toStructuredData());
        tampered.put("resultWorkspaceDigest", digest('9'));
        assertThat(CodingChangeReviewArtifact.fromStructuredData(tampered)).isEmpty();
    }

    @Test
    void rejectsMissingNonTerminalOrCrossRunChangeSetsInsteadOfManufacturingEvidence() {
        var store = new InMemoryFileChangeSetStore();
        store.create(FileChangeSet.pending(
                new FileChangeSetId("pending"),
                new ProjectId("project-1"),
                new WorkspaceId("workspace-1"),
                "operation-pending",
                "run-1",
                "tool-1",
                new WorkspaceRevision(1, digest('1')),
                new PrincipalRef("principal", "user"),
                "policy-1",
                NOW));
        store.create(applied(
                "other-run",
                "run-2",
                List.of(new FileChange(
                        FileChangeType.CREATE, ProjectPath.of("src/new.py"), null, null, file(1, "e")))));

        var factory = new CodingChangeReviewArtifactFactory(store);

        assertThat(factory.create("run-1", List.of("missing"))).isEmpty();
        assertThat(factory.create("run-1", List.of("pending"))).isEmpty();
        assertThat(factory.create("run-1", List.of("other-run"))).isEmpty();
    }

    @Test
    void hashesAnUnusuallyLongPathInsteadOfDroppingOtherwiseValidReviewEvidence() {
        String longPath = String.join("/", "a".repeat(200), "b".repeat(200), "c".repeat(200), "source.java");
        var store = new InMemoryFileChangeSetStore();
        store.create(applied(
                "change-long-path",
                List.of(new FileChange(FileChangeType.CREATE, ProjectPath.of(longPath), null, null, file(1, "f")))));

        CodingChangeReviewArtifact artifact = new CodingChangeReviewArtifactFactory(store)
                .create("run-1", List.of("change-long-path"))
                .orElseThrow();

        assertThat(artifact.fileSummaries())
                .singleElement()
                .extracting(CodingChangeReviewArtifact.FileSummary::path)
                .asString()
                .matches("path-sha256:[0-9a-f]{64}");
    }

    private static FileChangeSet applied(String id, List<FileChange> changes) {
        return applied(id, "run-1", changes);
    }

    private static FileChangeSet applied(String id, String runRef, List<FileChange> changes) {
        FileChangeSet pending = FileChangeSet.pending(
                new FileChangeSetId(id),
                new ProjectId("project-1"),
                new WorkspaceId("workspace-1"),
                "operation-" + id,
                runRef,
                "tool-1",
                new WorkspaceRevision(1, digest('1')),
                new PrincipalRef("principal", "user"),
                "policy-1",
                NOW);
        return pending.applied(new WorkspaceRevision(2, digest('2')), changes, true, NOW.plusSeconds(1));
    }

    private static FileVersion file(long size, String seed) {
        return new FileVersion(FileType.FILE, size, "sha256:" + seed.repeat(64));
    }

    private static String digest(char seed) {
        return "sha256:" + String.valueOf(seed).repeat(64);
    }
}
