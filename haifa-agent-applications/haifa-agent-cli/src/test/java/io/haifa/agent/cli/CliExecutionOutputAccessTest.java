package io.haifa.agent.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.application.project.product.coding.execution.CodingExecutionOutputAccess;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.execution.api.ExecutionCommand;
import io.haifa.agent.execution.api.ExecutionCommandMode;
import io.haifa.agent.execution.api.ExecutionEnvironmentRef;
import io.haifa.agent.execution.api.ExecutionId;
import io.haifa.agent.execution.api.ExecutionLimits;
import io.haifa.agent.execution.api.ExecutionOutput;
import io.haifa.agent.execution.api.ExecutionOutputChannel;
import io.haifa.agent.execution.api.ExecutionRequest;
import io.haifa.agent.execution.api.ExecutionResult;
import io.haifa.agent.execution.api.ExecutionStatus;
import io.haifa.agent.execution.api.ResourceUsageSummary;
import io.haifa.agent.execution.api.SandboxProfileRef;
import io.haifa.agent.execution.api.TrustedExecutionContext;
import io.haifa.agent.execution.core.store.InMemoryExecutionOutputStore;
import io.haifa.agent.execution.core.store.InMemoryExecutionStore;
import io.haifa.agent.project.path.WorkspacePath;
import io.haifa.agent.project.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CliExecutionOutputAccessTest {
    private static final TenantRef TENANT = new TenantRef("tenant-1");
    private static final PrincipalRef PRINCIPAL = new PrincipalRef("operator", "user");
    private static final AgentRunId RUN = new AgentRunId("run-1");

    @Test
    void readsUtf8WindowsAndLiteralSearchWithinTheSameTrustedRun() {
        Fixture fixture = fixture("first\n中间 needle\nlast needle\n", true);

        var first = fixture.access.read(window(fixture.outputRef, 0, 8, RUN, PRINCIPAL));
        assertThat(first.text()).isEqualTo("first\n");
        assertThat(first.hasMore()).isTrue();
        assertThat(first.nextOffsetBytes()).isEqualTo(6L);

        var second = fixture.access.read(window(fixture.outputRef, first.nextOffsetBytes(), 12, RUN, PRINCIPAL));
        assertThat(second.text()).startsWith("中间");
        assertThat(second.startOffsetBytes()).isEqualTo(6);

        int retainedBytes = "first\n中间 needle\nlast needle\n".getBytes(StandardCharsets.UTF_8).length;
        var eof = fixture.access.read(window(fixture.outputRef, retainedBytes, 32, RUN, PRINCIPAL));
        assertThat(eof.text()).isEmpty();
        assertThat(eof.hasMore()).isFalse();
        assertThat(eof.nextOffsetBytes()).isNull();

        var search = fixture.access.read(search(fixture.outputRef, "needle", 1, RUN, PRINCIPAL));
        assertThat(search.matches()).hasSize(1);
        assertThat(search.matches().getFirst().snippet()).contains("needle").doesNotContain("\n");
        assertThat(search.hasMore()).isTrue();
        assertThat(search.captureTruncated()).isTrue();
    }

    @Test
    void rejectsForgedScopeChannelBoundaryAndUnavailableOrBinaryContent() {
        Fixture fixture = fixture("alpha中omega", false);

        assertFailure(
                () -> fixture.access.read(window(fixture.outputRef, 0, 8, new AgentRunId("another-run"), PRINCIPAL)),
                CodingExecutionOutputAccess.FailureCode.EXECUTION_OUTPUT_NOT_FOUND);
        assertFailure(
                () -> fixture.access.read(new CodingExecutionOutputAccess.ReadRequest(
                        new TenantRef("another-tenant"),
                        PRINCIPAL,
                        RUN,
                        fixture.outputRef,
                        CodingExecutionOutputAccess.Mode.WINDOW,
                        0,
                        8,
                        "",
                        0)),
                CodingExecutionOutputAccess.FailureCode.EXECUTION_OUTPUT_NOT_FOUND);
        assertFailure(
                () -> fixture.access.read(window(fixture.outputRef, 0, 8, RUN, new PrincipalRef("another", "user"))),
                CodingExecutionOutputAccess.FailureCode.EXECUTION_OUTPUT_NOT_FOUND);
        assertFailure(
                () -> fixture.access.read(
                        window(fixture.outputRef.replace(":stdout", ":stderr"), 0, 8, RUN, PRINCIPAL)),
                CodingExecutionOutputAccess.FailureCode.EXECUTION_OUTPUT_NOT_FOUND);
        assertFailure(
                () -> fixture.access.read(window(fixture.outputRef, 6, 8, RUN, PRINCIPAL)),
                CodingExecutionOutputAccess.FailureCode.INVALID_TOOL_ARGUMENT);
        assertFailure(
                () -> fixture.access.read(window("not-an-output-reference", 0, 8, RUN, PRINCIPAL)),
                CodingExecutionOutputAccess.FailureCode.EXECUTION_OUTPUT_NOT_FOUND);
        assertFailure(
                () -> fixture.access.read(window("execution:missing:stdout", 0, 8, RUN, PRINCIPAL)),
                CodingExecutionOutputAccess.FailureCode.EXECUTION_OUTPUT_NOT_FOUND);

        var missingBytes = new CliExecutionOutputAccess(fixture.executions, new InMemoryExecutionOutputStore());
        assertFailure(
                () -> missingBytes.read(window(fixture.outputRef, 0, 8, RUN, PRINCIPAL)),
                CodingExecutionOutputAccess.FailureCode.EXECUTION_OUTPUT_UNAVAILABLE);

        Fixture binary = fixture(new byte[] {0, 1, 2}, false);
        assertFailure(
                () -> binary.access.read(window(binary.outputRef, 0, 2, RUN, PRINCIPAL)),
                CodingExecutionOutputAccess.FailureCode.EXECUTION_OUTPUT_BINARY_UNSUPPORTED);
    }

    @Test
    void escapesCrLfAndBoundsSearchSnippetsAcrossLongLines() {
        Fixture fixture = fixture(("x".repeat(600) + " ERROR\r\n").repeat(30), false);

        var result = fixture.access.read(search(fixture.outputRef, "ERROR", 20, RUN, PRINCIPAL));

        assertThat(result.matches()).hasSizeLessThanOrEqualTo(20);
        assertThat(result.matches()).allSatisfy(match -> {
            assertThat(match.snippet()).contains("ERROR").doesNotContain("\r", "\n");
            assertThat(match.snippet()).contains("\\r", "\\n");
        });
        assertThat(result.matches().stream()
                        .map(CodingExecutionOutputAccess.Match::snippet)
                        .mapToInt(value -> value.getBytes(StandardCharsets.UTF_8).length)
                        .sum())
                .isLessThanOrEqualTo(3072);
        assertThat(result.hasMore()).isTrue();
    }

    @Test
    void searchesRetainedMiddleAndRetainedTailWithoutConfusingCaptureTruncation() {
        Fixture complete = fixture("head\n" + "x".repeat(8000) + "COMPILATION ERROR\ntail", false);
        var hiddenMiddle = complete.access.read(search(complete.outputRef, "COMPILATION ERROR", 10, RUN, PRINCIPAL));
        assertThat(hiddenMiddle.matches()).singleElement().satisfies(match -> assertThat(match.snippet())
                .contains("COMPILATION ERROR"));
        assertThat(hiddenMiddle.captureTruncated()).isFalse();

        Fixture truncated = fixture("<retained head>\n... omitted ...\nFAILED test_case\n<retained tail>", true);
        var retainedTail = truncated.access.read(search(truncated.outputRef, "FAILED test_case", 10, RUN, PRINCIPAL));
        assertThat(retainedTail.matches()).hasSize(1);
        assertThat(retainedTail.captureTruncated()).isTrue();

        var lostRegion = truncated.access.read(search(truncated.outputRef, "only-in-lost-region", 10, RUN, PRINCIPAL));
        assertThat(lostRegion.matches()).isEmpty();
        assertThat(lostRegion.captureTruncated()).isTrue();
    }

    private static Fixture fixture(String output, boolean truncated) {
        return fixture(output.getBytes(StandardCharsets.UTF_8), truncated);
    }

    private static Fixture fixture(byte[] stdout, boolean truncated) {
        var executions = new InMemoryExecutionStore();
        var outputs = new InMemoryExecutionOutputStore();
        var id = new ExecutionId("execution-1");
        var workspace = new WorkspaceId("workspace-1");
        var request = new ExecutionRequest(
                id,
                "key-1",
                new TrustedExecutionContext(TENANT, RUN.value(), PRINCIPAL, Set.of("execution.run"), "policy-1"),
                workspace,
                WorkspacePath.root(workspace),
                new ExecutionCommand(ExecutionCommandMode.DIRECT, List.of("test")),
                new ExecutionEnvironmentRef(List.of("environment-1")),
                new ExecutionLimits(Duration.ofSeconds(5), 8192, 8192, 2),
                new SandboxProfileRef("test", "1"));
        executions.create(request);
        ExecutionOutput stored = outputs.store(id, ExecutionOutputChannel.STDOUT, stdout, 1, truncated);
        ExecutionOutput stderr = outputs.store(id, ExecutionOutputChannel.STDERR, new byte[0], 4096, false);
        executions.complete(
                request,
                new ExecutionResult(
                        id,
                        ExecutionStatus.SUCCEEDED,
                        0,
                        Instant.parse("2026-08-26T00:00:00Z"),
                        Instant.parse("2026-08-26T00:00:01Z"),
                        stored,
                        stderr,
                        null,
                        "sandbox-session",
                        new ResourceUsageSummary(Duration.ofSeconds(1), 1),
                        null,
                        false));
        return new Fixture(
                executions,
                new CliExecutionOutputAccess(executions, outputs),
                stored.assetRef().assetId());
    }

    private static CodingExecutionOutputAccess.ReadRequest window(
            String outputRef, long offset, int maximumBytes, AgentRunId run, PrincipalRef principal) {
        return new CodingExecutionOutputAccess.ReadRequest(
                TENANT,
                principal,
                run,
                outputRef,
                CodingExecutionOutputAccess.Mode.WINDOW,
                offset,
                maximumBytes,
                "",
                0);
    }

    private static CodingExecutionOutputAccess.ReadRequest search(
            String outputRef, String query, int maximumMatches, AgentRunId run, PrincipalRef principal) {
        return new CodingExecutionOutputAccess.ReadRequest(
                TENANT,
                principal,
                run,
                outputRef,
                CodingExecutionOutputAccess.Mode.SEARCH,
                0,
                0,
                query,
                maximumMatches);
    }

    private static void assertFailure(Runnable action, CodingExecutionOutputAccess.FailureCode code) {
        assertThatThrownBy(action::run)
                .isInstanceOf(CodingExecutionOutputAccess.AccessException.class)
                .extracting("code")
                .isEqualTo(code);
    }

    private record Fixture(InMemoryExecutionStore executions, CliExecutionOutputAccess access, String outputRef) {}
}
