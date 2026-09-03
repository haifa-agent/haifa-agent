package io.haifa.agent.application.project.product.coding.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.project.ledger.InMemorySessionChangeLedger;
import io.haifa.agent.project.ledger.SessionFileChangeRecord;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.path.WorkspacePath;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OnDemandChangeReviewServiceTest {

    private InMemorySessionChangeLedger ledger;
    private OnDemandChangeReviewService service;
    private Instant now;

    @BeforeEach
    void setUp() {
        ledger = new InMemorySessionChangeLedger();
        service = new OnDemandChangeReviewService(ledger);
        now = Instant.parse("2026-09-01T12:00:00Z");
    }

    @Test
    void generatesReviewAcrossGitAndPlainRoots() {
        WorkspaceId mainWs = new WorkspaceId("main");
        WorkspaceId docsWs = new WorkspaceId("docs");
        ledger.record(SessionFileChangeRecord.create(
                new WorkspacePath(mainWs, ProjectPath.of("src/App.java")),
                "sha256:0000000000000000000000000000000000000000000000000000000000000001",
                100,
                now));
        ledger.record(SessionFileChangeRecord.replace(
                new WorkspacePath(docsWs, ProjectPath.of("manual.md")),
                "sha256:0000000000000000000000000000000000000000000000000000000000000002",
                50,
                "sha256:0000000000000000000000000000000000000000000000000000000000000003",
                60,
                now));

        Optional<CodingChangeReviewArtifact> artifactOpt = service.generateReview(
                "run-test",
                "sha256:0000000000000000000000000000000000000000000000000000000000000000",
                "sha256:1111111111111111111111111111111111111111111111111111111111111111");

        assertThat(artifactOpt).isPresent();
        CodingChangeReviewArtifact artifact = artifactOpt.get();
        assertThat(artifact.totalFileCount()).isEqualTo(2);
        assertThat(artifact.counts()).containsEntry("created", 1).containsEntry("replaced", 1);
        assertThat(artifact.fileSummaries())
                .extracting(CodingChangeReviewArtifact.FileSummary::path)
                .containsExactlyInAnyOrder("main:src/App.java", "docs:manual.md");
    }

    @Test
    void generatesEmptyReviewWhenNoChangesRecorded() {
        Optional<CodingChangeReviewArtifact> artifactOpt = service.generateReview(
                "run-clean",
                "sha256:0000000000000000000000000000000000000000000000000000000000000000",
                "sha256:1111111111111111111111111111111111111111111111111111111111111111");

        assertThat(artifactOpt).isPresent();
        CodingChangeReviewArtifact artifact = artifactOpt.get();
        assertThat(artifact.totalFileCount()).isEqualTo(0);
        assertThat(artifact.fileSummaries()).isEmpty();
    }
}
