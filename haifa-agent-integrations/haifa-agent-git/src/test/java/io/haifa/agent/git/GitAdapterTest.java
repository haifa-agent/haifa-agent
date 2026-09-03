package io.haifa.agent.git;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.execution.api.ExecutionBroker;
import io.haifa.agent.execution.api.ExecutionFailure;
import io.haifa.agent.execution.api.ExecutionId;
import io.haifa.agent.execution.api.ExecutionOutput;
import io.haifa.agent.execution.api.ExecutionRequest;
import io.haifa.agent.execution.api.ExecutionResult;
import io.haifa.agent.execution.api.ExecutionStatus;
import io.haifa.agent.execution.api.ResourceUsageSummary;
import io.haifa.agent.execution.api.SandboxProfileRef;
import io.haifa.agent.execution.api.TrustedExecutionContext;
import io.haifa.agent.project.hostworkspace.HostGitInspectionStatus;
import io.haifa.agent.project.hostworkspace.scope.AuthorizedHostDirectory;
import io.haifa.agent.project.hostworkspace.scope.HostDirectoryPermission;
import io.haifa.agent.project.path.WorkspacePath;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitAdapterTest {
    @TempDir
    Path tempDir;

    @Test
    void usesBrokerForBoundedInternalRevisionProbe() {
        List<List<String>> commands = new ArrayList<>();
        ExecutionBroker broker = new ExecutionBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request) {
                commands.add(request.command().argv());
                List<String> argv = request.command().argv();
                String output;
                int exit = 0;
                if (argv.contains("--is-inside-work-tree")) output = "true\n";
                else if (argv.contains("HEAD") && argv.contains("rev-parse")) output = "abcdef1234567890\n";
                else if (argv.contains("symbolic-ref")) output = "main\n";
                else if (argv.contains("submodule")) output = "";
                else {
                    output = "";
                    exit = 1;
                }
                return result(request.id(), output, exit);
            }

            @Override
            public boolean cancel(ExecutionId id) {
                return false;
            }

            @Override
            public Optional<ExecutionResult> find(ExecutionId id) {
                return Optional.empty();
            }
        };
        AtomicInteger ids = new AtomicInteger();
        io.haifa.agent.common.id.IdentifierGenerator generator = () -> "git-" + ids.incrementAndGet();
        var adapter =
                new ExecutionBrokerGitRevisionProbe(broker, generator, new SandboxProfileRef("git-read", "1"), "git");
        WorkspaceId workspaceId = new WorkspaceId("workspace-1");
        var repository = new GitRepositoryRef(WorkspacePath.root(workspaceId));
        var context = new GitCommandContext(new TrustedExecutionContext(
                "run-1", new PrincipalRef("actor", "user"), Set.of("execution.run", "git.read"), "allow-1"));

        assertThat(adapter.inspectHead(context, repository).branch()).isEqualTo("main");
        assertThat(commands).hasSize(4);
        assertThat(commands).allSatisfy(argv -> {
            assertThat(argv).contains("-c", "credential.interactive=never");
            assertThat(argv.get(3))
                    .isNotIn("status", "diff", "log", "blame", "commit", "push", "fetch", "reset", "clean", "merge");
        });
    }

    @Test
    void bindsHostInspectionToAnExactlyAuthorizedBrokerRequest() throws Exception {
        Path root = tempDir.toRealPath();
        WorkspaceId workspaceId = new WorkspaceId("workspace-host");
        var boundary = AuthorizedHostDirectory.of(workspaceId, root, HostDirectoryPermission.READ_WRITE);
        List<ExecutionRequest> requests = new ArrayList<>();
        ExecutionBroker broker = broker(requests, request -> result(request.id(), root + System.lineSeparator(), 0));
        AtomicInteger ids = new AtomicInteger();
        var context = new GitCommandContext(trusted("run-host", "pending"), request -> {
            assertThat(request.context().runRef()).isEqualTo("run-host");
            return "generated-policy";
        });
        var adapter = new ExecutionBrokerHostGitInspectionPort(
                broker, () -> "host-" + ids.incrementAndGet(), new SandboxProfileRef("git-read", "1"), "git", context);

        assertThat(adapter.inspect(boundary, root)).isEqualTo(HostGitInspectionStatus.WORKTREE_ROOT);
        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.context().policyDecisionRef()).isEqualTo("generated-policy");
            assertThat(request.workingDirectory()).isEqualTo(WorkspacePath.root(workspaceId));
            assertThat(request.command().argv()).contains("rev-parse", "--show-toplevel");
        });
    }

    @Test
    void distinguishesPlainDirectoryFromUnavailableGitExecution() throws Exception {
        Path root = tempDir.toRealPath();
        WorkspaceId workspaceId = new WorkspaceId("workspace-inspection-failure");
        var boundary = AuthorizedHostDirectory.of(workspaceId, root, HostDirectoryPermission.READ_WRITE);
        AtomicInteger calls = new AtomicInteger();
        ExecutionBroker broker =
                broker(new ArrayList<>(), request -> result(request.id(), "", calls.incrementAndGet() == 1 ? 128 : 1));
        AtomicInteger ids = new AtomicInteger();
        var adapter = new ExecutionBrokerHostGitInspectionPort(
                broker,
                () -> "failure-" + ids.incrementAndGet(),
                new SandboxProfileRef("git-read", "1"),
                "git",
                new GitCommandContext(trusted("run-failure", "allow")));

        assertThat(adapter.inspect(boundary, root)).isEqualTo(HostGitInspectionStatus.NOT_WORKTREE_ROOT);
        assertThat(adapter.inspect(boundary, root)).isEqualTo(HostGitInspectionStatus.UNAVAILABLE);
    }

    @Test
    void capturesHeadAndDirtySnapshotWithoutPersistingStatusBody() {
        WorkspaceId workspaceId = new WorkspaceId("workspace-review");
        var repository = new GitRepositoryRef(WorkspacePath.root(workspaceId));
        AtomicInteger calls = new AtomicInteger();
        ExecutionBroker broker = broker(new ArrayList<>(), request -> {
            if (calls.incrementAndGet() == 1) {
                return result(request.id(), "abcdef\n", 0, "a".repeat(64), false);
            }
            return result(request.id(), " M src/App.java\n", 0, "sha256:" + "b".repeat(64), false);
        });
        AtomicInteger ids = new AtomicInteger();
        var probe = new ExecutionBrokerGitReviewProbe(
                broker, () -> "review-" + ids.incrementAndGet(), new SandboxProfileRef("git-read", "1"), "git");

        GitReviewSnapshot snapshot =
                probe.captureBaseline(new GitCommandContext(trusted("run-review", "allow")), repository);

        assertThat(snapshot.headRevision()).isEqualTo("abcdef");
        assertThat(snapshot.dirtySnapshotDigest()).isEqualTo("sha256:" + "b".repeat(64));
        assertThat(snapshot.complete()).isTrue();
    }

    @Test
    void derivesBoundedReviewFromStatusAndDiffAndRejectsInitialDirtyAttribution() {
        WorkspaceId workspaceId = new WorkspaceId("workspace-current-review");
        var repository = new GitRepositoryRef(WorkspacePath.root(workspaceId));
        AtomicInteger calls = new AtomicInteger();
        ExecutionBroker broker = broker(new ArrayList<>(), request -> {
            if (calls.incrementAndGet() == 1) {
                return result(
                        request.id(),
                        "?? new.txt\nR  source.txt -> destination.txt\n M nested-tool\n",
                        0,
                        "c".repeat(64),
                        false);
            }
            return result(request.id(), "1\t0\ttracked.txt\u0000", 0, "d".repeat(64), false);
        });
        AtomicInteger ids = new AtomicInteger();
        var probe = new ExecutionBrokerGitReviewProbe(
                broker, () -> "current-" + ids.incrementAndGet(), new SandboxProfileRef("git-read", "1"), "git");

        GitReviewResult clean = probe.review(
                new GitCommandContext(trusted("run-current", "allow")),
                repository,
                "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");

        assertThat(clean.complete()).isTrue();
        assertThat(clean.changes())
                .extracting(change -> change.type().name() + ":" + change.path())
                .containsExactly("CREATE:new.txt", "MOVE:source.txt", "REPLACE:nested-tool");

        calls.set(0);
        GitReviewResult initiallyDirty = probe.review(
                new GitCommandContext(trusted("run-dirty", "allow")), repository, "sha256:" + "f".repeat(64));
        assertThat(initiallyDirty.complete()).isFalse();
    }

    private static TrustedExecutionContext trusted(String runRef, String decisionRef) {
        return new TrustedExecutionContext(
                runRef, new PrincipalRef("actor", "user"), Set.of("execution.run", "git.read"), decisionRef);
    }

    private static ExecutionBroker broker(
            List<ExecutionRequest> requests, java.util.function.Function<ExecutionRequest, ExecutionResult> execution) {
        return new ExecutionBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request) {
                requests.add(request);
                return execution.apply(request);
            }

            @Override
            public boolean cancel(ExecutionId id) {
                return false;
            }

            @Override
            public Optional<ExecutionResult> find(ExecutionId id) {
                return Optional.empty();
            }
        };
    }

    private static ExecutionResult result(ExecutionId id, String stdout, int exitCode) {
        return result(id, stdout, exitCode, "0".repeat(64), false);
    }

    private static ExecutionResult result(
            ExecutionId id, String stdout, int exitCode, String stdoutSha256, boolean truncated) {
        Instant now = Instant.parse("2026-07-21T00:00:00Z");
        ExecutionOutput out = new ExecutionOutput(stdout, null, stdout.length(), stdoutSha256, truncated, false);
        ExecutionOutput err = new ExecutionOutput("", null, 0, "sha256:empty", false, false);
        return new ExecutionResult(
                id,
                exitCode == 0 ? ExecutionStatus.SUCCEEDED : ExecutionStatus.FAILED,
                exitCode,
                now,
                now.plusMillis(1),
                out,
                err,
                "session",
                new ResourceUsageSummary(Duration.ofMillis(1), 1),
                exitCode == 0 ? null : new ExecutionFailure("EXIT", "non-zero"),
                false);
    }
}
