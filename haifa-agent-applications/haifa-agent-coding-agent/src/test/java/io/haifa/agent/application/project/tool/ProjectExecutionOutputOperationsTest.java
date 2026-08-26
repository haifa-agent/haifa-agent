package io.haifa.agent.application.project.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.application.project.product.coding.execution.CodingExecutionOutputAccess;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.execution.api.SandboxProfileRef;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.sandbox.api.NetworkPolicy;
import io.haifa.agent.sandbox.api.SandboxCapabilities;
import io.haifa.agent.sandbox.api.SandboxConfigurationDigest;
import io.haifa.agent.sandbox.api.SandboxFilesystemPolicy;
import io.haifa.agent.sandbox.api.SandboxProfile;
import io.haifa.agent.tool.api.ToolInvocationObserver;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import io.haifa.agent.tool.api.ToolProvider;
import io.haifa.agent.tool.api.ToolProviderId;
import io.haifa.agent.tool.core.JsonSchema202012Validator;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ProjectExecutionOutputOperationsTest {
    @Test
    void exposesBoundedSearchEvidenceDirectlyInCanonicalSummary() {
        AtomicReference<CodingExecutionOutputAccess.ReadRequest> captured = new AtomicReference<>();
        var operations = new ProjectExecutionOutputOperations(request -> {
            captured.set(request);
            return new CodingExecutionOutputAccess.ReadResult(
                    request.outputRef(),
                    request.mode(),
                    "",
                    0,
                    0,
                    null,
                    false,
                    9000,
                    true,
                    List.of(new CodingExecutionOutputAccess.Match(42, "FAILED\\nnext")));
        });
        ToolInvocationRequest invocation = invocation(Map.of(
                "outputRef", "execution:execution-1:stdout",
                "mode", "SEARCH",
                "query", "FAILED",
                "maximumMatches", 3));

        var result = operations.execute(invocation);

        assertThat(captured.get().tenant()).isEqualTo(invocation.tenant());
        assertThat(captured.get().principal()).isEqualTo(invocation.principal());
        assertThat(captured.get().runId()).isEqualTo(invocation.runId());
        assertThat(result.successful()).isTrue();
        assertThat(result.truncated()).isFalse();
        assertThat(result.summary())
                .contains(
                        "outputRef=execution:execution-1:stdout",
                        "matchesReturned=1",
                        "captureTruncated=true",
                        "byteOffset=42: FAILED\\nnext");
        assertThat(result.structuredData()).containsEntry("matchesReturned", 1);
        assertThat(new JsonSchema202012Validator()
                        .validate(invocation.binding().definition().outputSchema(), result.structuredData())
                        .valid())
                .isTrue();
    }

    @Test
    void rejectsParametersFromTheOtherModeWithoutCallingTrustedAccess() {
        var operations = new ProjectExecutionOutputOperations(request -> {
            throw new AssertionError("invalid model arguments must not reach trusted access");
        });

        var result = operations.execute(invocation(Map.of(
                "outputRef", "execution:execution-1:stdout",
                "mode", "WINDOW",
                "query", "unexpected")));

        assertThat(result.successful()).isFalse();
        assertThat(result.structuredData()).containsEntry("stableFailureCode", "INVALID_TOOL_ARGUMENT");
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void exposesWindowOffsetsAndMarksTruncatedZeroMatchAsInconclusive() {
        var operations = new ProjectExecutionOutputOperations(request -> request.mode()
                        == CodingExecutionOutputAccess.Mode.WINDOW
                ? new CodingExecutionOutputAccess.ReadResult(
                        request.outputRef(), request.mode(), "bounded body", 10, 22, 22L, true, 100, false, List.of())
                : new CodingExecutionOutputAccess.ReadResult(
                        request.outputRef(), request.mode(), "", 0, 0, null, false, 100, true, List.of()));

        var window = operations.execute(invocation(Map.of(
                "outputRef", "execution:execution-1:stdout", "mode", "WINDOW", "offsetBytes", 10, "maximumBytes", 12)));
        assertThat(window.summary()).contains("bytes=10..22", "nextOffsetBytes=22", "bounded body", "hasMore=true");
        assertThat(window.truncated()).isFalse();

        var zeroMatch = operations.execute(
                invocation(Map.of("outputRef", "execution:execution-1:stdout", "mode", "SEARCH", "query", "missing")));
        assertThat(zeroMatch.summary())
                .contains("matchesReturned=0", "captureTruncated=true", "absence is inconclusive");
        assertThat(zeroMatch.truncated()).isFalse();
        assertThat(zeroMatch.summary()).hasSizeLessThan(4000);
    }

    @Test
    void executorFailsClosedWhenCompanionOperationsAreNotAssembled() {
        ToolInvocationRequest request =
                invocation(Map.of("outputRef", "execution:execution-1:stdout", "mode", "SEARCH", "query", "failure"));
        var executor = new ProjectToolExecutor(
                (runId, principal) -> new RunWorkspaceAccess(new WorkspaceId("workspace-1"), Set.of("execution.run")),
                (toolName, workspaceId, principal, runRef, policyDecisionRef, arguments) -> {
                    throw new AssertionError("generic operations must not handle the companion tool");
                });

        assertThatThrownBy(() -> executor.invoke(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("execution.output.read is not configured");
    }

    @Test
    void mapsTrustedAccessFailuresToStableModelVisibleCodes() {
        for (var code : List.of(
                CodingExecutionOutputAccess.FailureCode.EXECUTION_OUTPUT_NOT_FOUND,
                CodingExecutionOutputAccess.FailureCode.EXECUTION_OUTPUT_UNAVAILABLE,
                CodingExecutionOutputAccess.FailureCode.EXECUTION_OUTPUT_BINARY_UNSUPPORTED)) {
            var operations = new ProjectExecutionOutputOperations(request -> {
                throw new CodingExecutionOutputAccess.AccessException(code, "safe failure");
            });

            var result = operations.execute(invocation(
                    Map.of("outputRef", "execution:execution-1:stdout", "mode", "SEARCH", "query", "failure")));

            assertThat(result.successful()).isFalse();
            assertThat(result.structuredData()).containsEntry("stableFailureCode", code.name());
            assertThat(result.summary()).isEqualTo("safe failure");
            assertThat(result.truncated()).isFalse();
        }
    }

    private static ToolInvocationRequest invocation(Map<String, Object> arguments) {
        ToolProvider provider = new ToolProvider() {
            @Override
            public ToolProviderId id() {
                return ProjectToolExecutor.PROVIDER_ID;
            }

            @Override
            public io.haifa.agent.core.tool.ToolResult invoke(ToolInvocationRequest request) {
                throw new UnsupportedOperationException();
            }
        };
        var binding = new ProjectToolCatalog()
                        .freeze(Set.of("execution.run"), Set.of("execution.run"), true, provider, executionProfile())
                        .snapshot()
                        .bindings()
                        .stream()
                        .filter(value ->
                                value.definition().name().value().equals(ProjectExecutionOutputOperations.TOOL_NAME))
                        .findFirst()
                        .orElseThrow();
        return new ToolInvocationRequest(
                binding,
                new ToolCallId("tool-call-1"),
                new AgentRunId("run-1"),
                new TenantRef("tenant-1"),
                new PrincipalRef("operator", "user"),
                new ToolArguments(binding.definition().inputSchema().id(), "1.0.0", arguments),
                Instant.parse("2026-08-26T00:01:00Z"),
                Optional.empty(),
                Optional.empty(),
                () -> false,
                List.of(),
                ToolInvocationObserver.noop());
    }

    private static SandboxProfile executionProfile() {
        return new SandboxProfile(
                new SandboxProfileRef("test", "1"),
                "host-guarded",
                SandboxConfigurationDigest.sha256Fields(List.of("test")),
                Set.of(),
                Set.of(),
                true,
                NetworkPolicy.ALLOW,
                SandboxFilesystemPolicy.hostCompatible(),
                new SandboxCapabilities(true, false, false, false, false));
    }
}
