package io.haifa.agent.application.project.product.coding.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.git.GitReviewChange;
import io.haifa.agent.git.GitReviewResult;
import io.haifa.agent.project.changeset.FileChangeType;
import io.haifa.agent.project.hostworkspace.HostGitInspectionStatus;
import io.haifa.agent.project.hostworkspace.scope.AuthorizedHostDirectory;
import io.haifa.agent.project.hostworkspace.scope.HostDirectoryPermission;
import io.haifa.agent.project.hostworkspace.scope.HostWorkspaceScope;
import io.haifa.agent.project.ledger.InMemorySessionChangeLedger;
import io.haifa.agent.project.ledger.SessionFileChangeRecord;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.path.WorkspacePath;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OnDemandChangeReviewServiceTest {

    @TempDir
    Path root;

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

    @Test
    void keepsPlainMoveSourceAndDestinationDirection() {
        WorkspaceId workspaceId = new WorkspaceId("plain-move");
        ledger.record(SessionFileChangeRecord.move(
                new WorkspacePath(workspaceId, ProjectPath.of("before.txt")),
                new WorkspacePath(workspaceId, ProjectPath.of("after.txt")),
                "before",
                6,
                "after",
                5,
                now));

        CodingChangeReviewArtifact artifact =
                service.generateReview("run-move", null, null).orElseThrow();

        assertThat(artifact.fileSummaries()).singleElement().satisfies(summary -> {
            assertThat(summary.path()).isEqualTo("plain-move:before.txt");
            assertThat(summary.destination()).isEqualTo("plain-move:after.txt");
        });
    }

    @Test
    void routesPlainAndPeerRepositoriesWithoutMixingLedgerAndGitEvidence() throws Exception {
        root = root.toRealPath();
        Path docs = Files.createDirectories(root.resolve("docs"));
        Path serviceA = Files.createDirectories(root.resolve("service-a"));
        Path serviceB = Files.createDirectories(root.resolve("service-b"));
        WorkspaceId workspaceId = new WorkspaceId("workspace-case-a");
        HostWorkspaceScope scope = HostWorkspaceScope.initial(
                AuthorizedHostDirectory.of(workspaceId, root, HostDirectoryPermission.READ_WRITE));
        RepositoryRunContext context =
                new RepositoryRunContext(new TenantRef("tenant"), "run-case-a", new PrincipalRef("actor", "user"));
        var registry = new RunRepositoryBaselineRegistry(
                (boundary, candidate) -> candidate.equals(serviceA) || candidate.equals(serviceB)
                        ? HostGitInspectionStatus.WORKTREE_ROOT
                        : HostGitInspectionStatus.NOT_WORKTREE_ROOT,
                (ignored, repository) -> cleanBaseline(repository));
        Path docsTarget = docs.resolve("guide.md");
        Path aTarget = serviceA.resolve("src/A.java");
        Path bTarget = serviceB.resolve("src/B.java");
        registry.beforeManagedWrite(context, scope.resolve(docsTarget.toString()));
        registry.beforeManagedWrite(context, scope.resolve(aTarget.toString()));
        registry.beforeManagedWrite(context, scope.resolve(bTarget.toString()));

        ledger.record(SessionFileChangeRecord.create(
                new WorkspacePath(workspaceId, ProjectPath.of("docs/guide.md")), "a", 1, now));
        ledger.record(SessionFileChangeRecord.create(
                new WorkspacePath(workspaceId, ProjectPath.of("service-a/src/A.java")), "b", 1, now));
        ledger.record(SessionFileChangeRecord.create(
                new WorkspacePath(workspaceId, ProjectPath.of("service-b/src/B.java")), "c", 1, now));
        service = new OnDemandChangeReviewService(ledger, registry, (runRef, baseline) -> {
            String name = baseline.repository().root().projectPath().value();
            String relative = name.equals("service-a") ? "src/A.java" : "src/B.java";
            return new GitReviewResult(
                    "sha256:" + (name.equals("service-a") ? "1" : "2").repeat(64),
                    List.of(new GitReviewChange(FileChangeType.CREATE, ProjectPath.of(relative), null, false)),
                    false,
                    true);
        });

        CodingChangeReviewArtifact artifact =
                service.generateReview("run-case-a", null, null).orElseThrow();

        assertThat(artifact.complete()).isTrue();
        assertThat(artifact.totalFileCount()).isEqualTo(3);
        assertThat(artifact.fileSummaries())
                .extracting(CodingChangeReviewArtifact.FileSummary::path)
                .containsExactlyInAnyOrder(
                        "workspace-case-a:docs/guide.md",
                        "workspace-case-a:service-a/src/A.java",
                        "workspace-case-a:service-b/src/B.java");
    }

    @Test
    void marksReviewPartialWhenInitialDirtyStateCannotBeAttributed() throws Exception {
        root = root.toRealPath();
        Path repository = Files.createDirectories(root.resolve("project-a"));
        WorkspaceId workspaceId = new WorkspaceId("workspace-dirty");
        HostWorkspaceScope scope = HostWorkspaceScope.initial(
                AuthorizedHostDirectory.of(workspaceId, root, HostDirectoryPermission.READ_WRITE));
        RepositoryRunContext context =
                new RepositoryRunContext(new TenantRef("tenant"), "run-dirty", new PrincipalRef("actor", "user"));
        var registry = new RunRepositoryBaselineRegistry(
                (boundary, candidate) -> candidate.equals(repository)
                        ? HostGitInspectionStatus.WORKTREE_ROOT
                        : HostGitInspectionStatus.NOT_WORKTREE_ROOT,
                (ignored, repo) ->
                        new RepositoryBaseline(repo, "abc", "sha256:" + "f".repeat(64), AttributionStatus.COMPLETE));
        registry.beforeManagedWrite(
                context, scope.resolve(repository.resolve("changed.txt").toString()));
        service = new OnDemandChangeReviewService(
                ledger,
                registry,
                (runRef, baseline) -> new GitReviewResult(
                        "sha256:" + "3".repeat(64),
                        List.of(new GitReviewChange(
                                FileChangeType.REPLACE, ProjectPath.of("changed.txt"), null, false)),
                        false,
                        false));

        CodingChangeReviewArtifact artifact =
                service.generateReview("run-dirty", null, null).orElseThrow();

        assertThat(artifact.attributionStatus()).isEqualTo(AttributionStatus.ATTRIBUTION_PARTIAL);
    }

    private static RepositoryBaseline cleanBaseline(io.haifa.agent.git.GitRepositoryRef repository) {
        return new RepositoryBaseline(
                repository,
                "abc",
                "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                AttributionStatus.COMPLETE);
    }
}
