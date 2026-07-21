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
import io.haifa.agent.project.path.WorkspacePath;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GitAdapterTest {
    @Test
    void usesBrokerForBoundedReadOnlyInspectStatusAndDiff() {
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
                else if (argv.contains("status")) output = " M src/App.java\n?? new.txt\n";
                else if (argv.contains("diff")) output = "--- a/src/App.java\n+++ b/src/App.java\n";
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
        var adapter = new ExecutionBrokerGitRepositoryAdapter(
                broker, () -> "git-" + ids.incrementAndGet(), new SandboxProfileRef("git-read", "1"), "git");
        WorkspaceId workspaceId = new WorkspaceId("workspace-1");
        var repository = new GitRepositoryRef(WorkspacePath.root(workspaceId));
        var context = new GitCommandContext(new TrustedExecutionContext(
                "run-1", new PrincipalRef("actor", "user"), Set.of("execution.run", "git.read"), "allow-1"));

        assertThat(adapter.inspect(context, repository).branch()).isEqualTo("main");
        assertThat(adapter.status(context, repository, 10).entries())
                .extracting(entry -> entry.path().value())
                .containsExactly("src/App.java", "new.txt");
        assertThat(adapter.diff(context, repository, 4096).unifiedDiff()).contains("src/App.java");
        assertThat(commands).allSatisfy(argv -> {
            assertThat(argv).contains("-c", "credential.interactive=never");
            assertThat(argv).doesNotContain("commit", "push", "fetch", "reset", "clean", "merge");
        });
    }

    private static ExecutionResult result(ExecutionId id, String stdout, int exitCode) {
        Instant now = Instant.parse("2026-07-21T00:00:00Z");
        ExecutionOutput out = new ExecutionOutput(stdout, null, stdout.length(), "sha256:test", false, false);
        ExecutionOutput err = new ExecutionOutput("", null, 0, "sha256:empty", false, false);
        return new ExecutionResult(
                id,
                exitCode == 0 ? ExecutionStatus.SUCCEEDED : ExecutionStatus.FAILED,
                exitCode,
                now,
                now.plusMillis(1),
                out,
                err,
                null,
                "session",
                new ResourceUsageSummary(Duration.ofMillis(1), 1),
                exitCode == 0 ? null : new ExecutionFailure("EXIT", "non-zero"),
                false);
    }
}
