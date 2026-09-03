package io.haifa.agent.git;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.common.id.IdentifierGenerator;
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
import io.haifa.agent.project.changeset.FileChangeType;
import io.haifa.agent.project.hostworkspace.HostGitInspectionStatus;
import io.haifa.agent.project.hostworkspace.HostRepositoryLocator;
import io.haifa.agent.project.hostworkspace.scope.AuthorizedHostDirectory;
import io.haifa.agent.project.hostworkspace.scope.HostDirectoryPermission;
import io.haifa.agent.project.hostworkspace.scope.HostWorkspaceScope;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitTopologyIntegrationTest {
    @TempDir
    Path tempDir;

    @Test
    void recognizesLinkedWorktreeGitFileAndReviewsItsOwnChange() throws Exception {
        Path repository = Files.createDirectories(tempDir.resolve("repository"));
        initializeRepository(repository, "tracked.txt", "initial\n");
        Path linked = tempDir.resolve("linked-worktree");
        git(repository, "worktree", "add", "-b", "linked-test", linked.toString())
                .requireSuccess();
        linked = linked.toRealPath();
        WorkspaceId workspaceId = new WorkspaceId("workspace-linked");
        AuthorizedHostDirectory boundary =
                AuthorizedHostDirectory.of(workspaceId, linked, HostDirectoryPermission.READ_WRITE);
        ExecutionBroker broker = realGitBroker(Map.of(workspaceId, linked));
        AtomicInteger ids = new AtomicInteger();
        IdentifierGenerator identifiers = () -> "linked-git-" + ids.incrementAndGet();
        GitCommandContext context = new GitCommandContext(trusted("run-linked"));
        var inspection = new ExecutionBrokerHostGitInspectionPort(
                broker, identifiers, new SandboxProfileRef("git-read", "1"), "git", context);
        var review =
                new ExecutionBrokerGitReviewProbe(broker, identifiers, new SandboxProfileRef("git-read", "1"), "git");

        assertThat(Files.isRegularFile(linked.resolve(".git"))).isTrue();
        assertThat(inspection.inspect(boundary, linked)).isEqualTo(HostGitInspectionStatus.WORKTREE_ROOT);
        GitRepositoryRef repositoryRef =
                new GitRepositoryRef(io.haifa.agent.project.path.WorkspacePath.root(workspaceId));
        GitReviewSnapshot baseline = review.captureBaseline(context, repositoryRef);
        Files.writeString(linked.resolve("tracked.txt"), "changed\n", StandardCharsets.UTF_8);

        GitReviewResult result = review.review(context, repositoryRef, baseline.dirtySnapshotDigest());

        assertThat(baseline.complete()).isTrue();
        assertThat(result.complete()).isTrue();
        assertThat(result.changes())
                .extracting(change -> change.type() + ":" + change.path())
                .containsExactly("REPLACE:tracked.txt");
    }

    @Test
    void locatesSubmoduleGitFileAndKeepsChildFilesOutOfParentReview() throws Exception {
        Path librarySource = Files.createDirectories(tempDir.resolve("library-source"));
        initializeRepository(librarySource, "lib.txt", "v1\n");
        Path parent = Files.createDirectories(tempDir.resolve("parent"));
        initializeRepository(parent, "README.md", "parent\n");
        git(parent, "-c", "protocol.file.allow=always", "submodule", "add", librarySource.toString(), "vendor/lib-x")
                .requireSuccess();
        git(parent, "commit", "-am", "test: add submodule").requireSuccess();
        Path submodule = parent.resolve("vendor/lib-x").toRealPath();
        configure(submodule);
        WorkspaceId workspaceId = new WorkspaceId("workspace-submodule");
        AuthorizedHostDirectory boundary =
                AuthorizedHostDirectory.of(workspaceId, parent.toRealPath(), HostDirectoryPermission.READ_WRITE);
        HostWorkspaceScope scope = HostWorkspaceScope.initial(boundary);
        ExecutionBroker broker = realGitBroker(Map.of(workspaceId, parent.toRealPath()));
        AtomicInteger ids = new AtomicInteger();
        IdentifierGenerator identifiers = () -> "submodule-git-" + ids.incrementAndGet();
        GitCommandContext context = new GitCommandContext(trusted("run-submodule"));
        var inspection = new ExecutionBrokerHostGitInspectionPort(
                broker, identifiers, new SandboxProfileRef("git-read", "1"), "git", context);
        var locator = new HostRepositoryLocator(inspection);
        var review =
                new ExecutionBrokerGitReviewProbe(broker, identifiers, new SandboxProfileRef("git-read", "1"), "git");
        GitRepositoryRef parentRef = new GitRepositoryRef(io.haifa.agent.project.path.WorkspacePath.root(workspaceId));
        GitReviewSnapshot baseline = review.captureBaseline(context, parentRef);

        Files.writeString(submodule.resolve("lib.txt"), "v2\n", StandardCharsets.UTF_8);
        git(submodule, "add", "lib.txt").requireSuccess();
        git(submodule, "commit", "-m", "test: advance library").requireSuccess();

        assertThat(Files.isRegularFile(submodule.resolve(".git"))).isTrue();
        assertThat(inspection.inspect(boundary, submodule)).isEqualTo(HostGitInspectionStatus.WORKTREE_ROOT);
        assertThat(locator.locate(scope.resolve(submodule.resolve("lib.txt").toString())))
                .get()
                .extracting(located -> located.workspaceRoot().projectPath())
                .isEqualTo(ProjectPath.of("vendor/lib-x"));
        GitReviewResult parentReview = review.review(context, parentRef, baseline.dirtySnapshotDigest());
        assertThat(parentReview.complete()).isTrue();
        assertThat(parentReview.changes())
                .extracting(change -> change.type() + ":" + change.path())
                .containsExactly(FileChangeType.REPLACE + ":vendor/lib-x")
                .noneMatch(value -> value.contains("lib.txt"));
    }

    private static void initializeRepository(Path repository, String fileName, String content) throws Exception {
        git(repository, "init").requireSuccess();
        configure(repository);
        Files.writeString(repository.resolve(fileName), content, StandardCharsets.UTF_8);
        git(repository, "add", fileName).requireSuccess();
        git(repository, "commit", "-m", "test: initialize repository").requireSuccess();
    }

    private static void configure(Path repository) throws Exception {
        git(repository, "config", "user.name", "Haifa Test").requireSuccess();
        git(repository, "config", "user.email", "haifa-test@example.invalid").requireSuccess();
    }

    private static CommandResult git(Path workdir, String... arguments) throws Exception {
        List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command)
                .directory(workdir.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new CommandResult(process.waitFor(), output);
    }

    private static ExecutionBroker realGitBroker(Map<WorkspaceId, Path> roots) {
        return new ExecutionBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request) {
                Path root = Optional.ofNullable(roots.get(request.workspaceId()))
                        .orElseThrow(() -> new AssertionError("unknown workspace: " + request.workspaceId()));
                Path workdir = root.resolve(
                                request.workingDirectory().projectPath().value())
                        .normalize();
                Instant started = Instant.now();
                try {
                    Process process = new ProcessBuilder(request.command().argv())
                            .directory(workdir.toFile())
                            .redirectErrorStream(true)
                            .start();
                    byte[] output = process.getInputStream().readAllBytes();
                    int exit = process.waitFor();
                    String summary = new String(output, StandardCharsets.UTF_8);
                    return new ExecutionResult(
                            request.id(),
                            exit == 0 ? ExecutionStatus.SUCCEEDED : ExecutionStatus.FAILED,
                            exit,
                            started,
                            Instant.now(),
                            new ExecutionOutput(summary, null, output.length, digest(output), false, false),
                            new ExecutionOutput("", null, 0, digest(new byte[0]), false, false),
                            "git-test",
                            new ResourceUsageSummary(Duration.between(started, Instant.now()), 1),
                            exit == 0 ? null : new ExecutionFailure("EXIT", "git command failed"),
                            false);
                } catch (IOException exception) {
                    throw new IllegalStateException(exception);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
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

    private static TrustedExecutionContext trusted(String runRef) {
        return new TrustedExecutionContext(
                runRef, new PrincipalRef("actor", "user"), Set.of("execution.run", "git.read"), "allow");
    }

    private static String digest(byte[] value) {
        try {
            return "sha256:"
                    + HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record CommandResult(int exitCode, String output) {
        private CommandResult requireSuccess() throws IOException {
            if (exitCode != 0) throw new IOException("git fixture command failed: " + output);
            return this;
        }
    }
}
