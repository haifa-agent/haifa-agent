package io.haifa.agent.application.project.product.coding.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.project.hostworkspace.HostGitInspectionStatus;
import io.haifa.agent.project.hostworkspace.scope.AuthorizedHostDirectory;
import io.haifa.agent.project.hostworkspace.scope.HostDirectoryPermission;
import io.haifa.agent.project.hostworkspace.scope.HostWorkspaceScope;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunRepositoryBaselineRegistryTest {
    @TempDir
    Path tempDir;

    private WorkspaceId workspaceId;
    private HostWorkspaceScope scope;
    private RepositoryRunContext context;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = tempDir.toRealPath();
        workspaceId = new WorkspaceId("workspace");
        context = new RepositoryRunContext(new TenantRef("local"), "run-1", new PrincipalRef("operator", "user"));
        scope = HostWorkspaceScope.initial(
                AuthorizedHostDirectory.of(workspaceId, tempDir, HostDirectoryPermission.READ_WRITE));
    }

    @Test
    void capturesNearestRepositoryOnceBeforeManagedWrites() throws Exception {
        Path repository = Files.createDirectories(tempDir.resolve("service"));
        Path first = Files.createDirectories(repository.resolve("src")).resolve("One.java");
        Path second = repository.resolve("src/Two.java");
        AtomicInteger captures = new AtomicInteger();
        var registry = new RunRepositoryBaselineRegistry(
                (ignored, candidate) -> candidate.equals(repository)
                        ? HostGitInspectionStatus.WORKTREE_ROOT
                        : HostGitInspectionStatus.NOT_WORKTREE_ROOT,
                (runContext, reference) -> {
                    captures.incrementAndGet();
                    return new RepositoryBaseline(
                            reference, "abc123", "sha256:" + "0".repeat(64), AttributionStatus.COMPLETE);
                });

        registry.beforeManagedWrite(context, scope.resolve(first.toString()));
        registry.beforeManagedWrite(context, scope.resolve(second.toString()));

        assertThat(captures).hasValue(1);
        assertThat(registry.baselines("run-1")).hasSize(1);
        assertThat(registry.targetAssignments("run-1").values()).allMatch(GitReviewTarget.class::isInstance);
        assertThat(registry.attributionStatus("run-1")).isEqualTo(AttributionStatus.COMPLETE);
    }

    @Test
    void routesNonGitPathToPlainLedgerReview() throws Exception {
        Path note = tempDir.resolve("notes.txt");
        var registry = new RunRepositoryBaselineRegistry(
                (ignored, candidate) -> HostGitInspectionStatus.NOT_WORKTREE_ROOT, (runContext, reference) -> {
                    throw new AssertionError("plain targets must not capture a Git baseline");
                });

        var target = scope.resolve(note.toString());
        var plainContext = new RepositoryRunContext(context.tenant(), "run-plain", context.actor());
        registry.beforeManagedWrite(plainContext, target);

        assertThat(registry.targetAssignments("run-plain"))
                .containsEntry(target.workspacePath(), new PlainReviewTarget(workspaceId));
    }

    @Test
    void captureFailurePropagatesBeforeCallerCanWrite() throws Exception {
        Path repository = Files.createDirectories(tempDir.resolve("service"));
        Path target = repository.resolve("App.java");
        var registry = new RunRepositoryBaselineRegistry(
                (ignored, candidate) -> candidate.equals(repository)
                        ? HostGitInspectionStatus.WORKTREE_ROOT
                        : HostGitInspectionStatus.NOT_WORKTREE_ROOT,
                (runContext, reference) -> {
                    throw new IllegalStateException("baseline unavailable");
                });

        var failingContext = new RepositoryRunContext(context.tenant(), "run-fail", context.actor());
        assertThatThrownBy(() -> registry.beforeManagedWrite(failingContext, scope.resolve(target.toString())))
                .isInstanceOf(RepositoryBaselineUnavailableException.class)
                .hasMessage("repository baseline is unavailable")
                .hasRootCauseMessage("baseline unavailable");
        assertThat(Files.exists(target)).isFalse();
    }

    @Test
    void executionAlwaysMarksAttributionPartialAndInvalidatesLocator() throws Exception {
        Path repository = Files.createDirectories(tempDir.resolve("service"));
        var repositories = new java.util.HashSet<Path>();
        var registry = new RunRepositoryBaselineRegistry(
                (ignored, candidate) -> repositories.contains(candidate)
                        ? HostGitInspectionStatus.WORKTREE_ROOT
                        : HostGitInspectionStatus.NOT_WORKTREE_ROOT,
                (runContext, reference) -> new RepositoryBaseline(
                        reference, "abc123", "sha256:" + "0".repeat(64), AttributionStatus.COMPLETE));
        var workdir = scope.resolve(repository.toString());

        var executionContext = new RepositoryRunContext(context.tenant(), "run-exec", context.actor());
        registry.beforeExecution(executionContext, workdir);
        repositories.add(repository);
        registry.afterExecution(executionContext, workspaceId);
        registry.beforeManagedWrite(
                executionContext,
                scope.resolve(repository.resolve("created.txt").toString()));

        assertThat(registry.baselines("run-exec")).hasSize(1);
        assertThat(registry.attributionStatus("run-exec")).isEqualTo(AttributionStatus.ATTRIBUTION_PARTIAL);
    }
}
