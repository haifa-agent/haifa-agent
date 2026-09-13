package io.haifa.agent.application.project.tool;

import static io.haifa.agent.application.project.tool.ProjectExecutionTestSupport.WORKSPACE_ID;
import static io.haifa.agent.application.project.tool.ProjectExecutionTestSupport.access;
import static io.haifa.agent.application.project.tool.ProjectExecutionTestSupport.chunk;
import static io.haifa.agent.application.project.tool.ProjectExecutionTestSupport.invocation;
import static io.haifa.agent.application.project.tool.ProjectExecutionTestSupport.operations;
import static io.haifa.agent.application.project.tool.ProjectExecutionTestSupport.operationsWithSanitizer;
import static io.haifa.agent.application.project.tool.ProjectExecutionTestSupport.result;
import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.application.project.product.coding.delivery.CodingValidationScope;
import io.haifa.agent.application.project.product.coding.verification.CodingSessionVerificationConfiguration;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationCandidate;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationCost;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationProfile;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationSource;
import io.haifa.agent.application.project.product.coding.verification.CodingVerificationTrigger;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.execution.api.ExecutionBroker;
import io.haifa.agent.execution.api.ExecutionOrigin;
import io.haifa.agent.execution.api.ExecutionOutputObserver;
import io.haifa.agent.execution.api.ExecutionRequest;
import io.haifa.agent.execution.api.ExecutionResult;
import io.haifa.agent.execution.api.ExecutionStatus;
import io.haifa.agent.policy.api.PolicyDigest;
import io.haifa.agent.tool.api.ToolDispatchEvidence;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import io.haifa.agent.tool.api.ToolReconciliationRequest;
import io.haifa.agent.tool.api.ToolReconciliationStatus;
import io.haifa.agent.tool.core.JsonSchema202012Validator;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ProjectExecutionPolicyTest {

    @Test
    void validatesExactFrozenCandidateScopeAcrossDirectAndReconciledResults() {
        AtomicReference<ExecutionResult> completed = new AtomicReference<>();
        ExecutionBroker broker = new ProjectExecutionTestSupport.StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                observer.onOutput(chunk("1 passed, 7 deselected in 0.25s\n"));
                ExecutionResult result = result(request.id(), ExecutionStatus.SUCCEEDED, 0);
                completed.set(result);
                return result;
            }

            @Override
            public Optional<ExecutionResult> findByIdempotencyKey(String idempotencyKey) {
                return Optional.ofNullable(completed.get());
            }
        };

        String command = "python -m pytest focused.py";
        CodingVerificationCandidate candidate = new CodingVerificationCandidate(
                command,
                CodingVerificationCost.LOW,
                Duration.ofMinutes(2),
                CodingVerificationTrigger.ADJACENT_CHANGE,
                CodingVerificationSource.USER_EXPLICIT,
                "trusted-host",
                CodingValidationScope.SELECTED);
        CodingSessionVerificationConfiguration configuration =
                CodingSessionVerificationConfiguration.freeze(new CodingVerificationProfile(List.of(candidate)));
        ToolInvocationRequest invocation =
                invocation(Map.of("command", command, "operationFamily", "TEST"), () -> false);
        var operations = operations(broker, 4096, 100, ignored -> configuration);
        ToolResult result = operations.execute(invocation, access());
        String expectedValidationAttemptRef = PolicyDigest.sha256Fields(List.of(
                "coding-validation-evidence/2",
                "PASSED",
                configuration.digest(),
                configuration.candidateDigest(candidate),
                "TRUSTED_SELECTED_SCOPE"));

        assertThat(result.structuredData().get("validationEvidence"))
                .isInstanceOfSatisfying(Map.class, evidence -> assertThat(evidence)
                        .containsEntry("status", "PASSED")
                        .containsEntry("scope", "SELECTED")
                        .containsEntry("countSource", "COUNTS_UNAVAILABLE")
                        .containsEntry("verificationSource", "USER_EXPLICIT")
                        .containsEntry("claimCode", "TRUSTED_SELECTED_SCOPE")
                        .doesNotContainKeys("discoveredTestCount", "selectedTestCount", "ignoredTestCount"));
        assertThat(result.structuredData()).containsEntry("validationAttemptRef", expectedValidationAttemptRef);
        var validator = new JsonSchema202012Validator();
        assertThat(validator
                        .validate(invocation.binding().definition().outputSchema(), result.structuredData())
                        .valid())
                .isTrue();

        var reconciled = operations.reconcile(
                new ToolReconciliationRequest(
                        invocation.binding(),
                        invocation.toolCallId(),
                        invocation.runId(),
                        invocation.tenant(),
                        invocation.principal(),
                        invocation.arguments(),
                        invocation.idempotencyKey().orElseThrow(),
                        Optional.of(new ToolDispatchEvidence(
                                completed.get().id().value(),
                                OptionalLong.empty(),
                                PolicyDigest.sha256Fields(
                                        List.of("execution-working-directory-v1", WORKSPACE_ID.value(), ".")))),
                        Optional.empty()),
                access());

        assertThat(reconciled.status()).isEqualTo(ToolReconciliationStatus.RESOLVED);
        assertThat(reconciled.result()).hasValueSatisfying(reconciledResult -> {
            assertThat(reconciledResult.structuredData())
                    .containsEntry("reconcileStatus", "RESOLVED")
                    .containsEntry("replayAllowed", false)
                    .containsEntry("validationAttemptRef", expectedValidationAttemptRef);
            assertThat(validator
                            .validate(
                                    invocation.binding().definition().outputSchema(), reconciledResult.structuredData())
                            .valid())
                    .isTrue();
        });
    }

    @Test
    void projectsTrustedDeliveryActionAndVerificationEvidence() {
        AtomicInteger calls = new AtomicInteger();
        ExecutionBroker broker = new ProjectExecutionTestSupport.StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                if (calls.getAndIncrement() == 0) observer.onOutput(chunk("D:/workspace/project\n"));
                return result(request.id(), ExecutionStatus.SUCCEEDED, 0);
            }
        };

        ToolResult root = operations(broker, 4096, 100)
                .execute(invocation(Map.of("command", "git rev-parse --show-toplevel"), () -> false), access());
        ToolResult upstream = operations(broker, 4096, 100)
                .execute(
                        invocation(
                                Map.of(
                                        "command",
                                        "git for-each-ref '--format=%(upstream:short)' refs/heads/feat-delivery"),
                                () -> false),
                        access());
        ToolResult staged = operations(broker, 4096, 100)
                .execute(invocation(Map.of("command", "git add src/Main.java"), () -> false), access());

        assertThat(root.structuredData())
                .containsEntry("deliveryAction", "NONE")
                .containsEntry("deliveryVerification", "REPOSITORY_ROOT")
                .containsEntry("deliveryEvidenceCode", "REPOSITORY_ROOT_VERIFIED")
                .containsKey("deliveryRepositoryScopeDigest")
                .containsKey("deliveryEvidenceRef");
        assertThat(upstream.structuredData())
                .containsEntry("deliveryVerification", "UPSTREAM")
                .containsEntry("deliveryEvidenceCode", "UPSTREAM_INSPECTED")
                .containsKey("deliveryRepositoryScopeDigest");
        assertThat(staged.structuredData())
                .containsEntry("deliveryAction", "STAGE")
                .containsEntry("deliveryVerification", "NONE")
                .containsEntry("deliveryEvidenceCode", "STAGE_COMPLETED")
                .containsKey("deliveryEvidenceRef");
    }

    @Test
    void dispatchesGitDeliveryCommandsWithoutAProductSpecificGuard() {
        AtomicBoolean invoked = new AtomicBoolean();
        ExecutionBroker broker = new ProjectExecutionTestSupport.StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                invoked.set(true);
                observer.onStarted();
                return result(request.id(), ExecutionStatus.SUCCEEDED, 0);
            }
        };

        ToolResult result = operations(broker, 1024, 2000)
                .execute(
                        invocation(
                                Map.of("command", "git push origin feat-delivery", "relativeWorkdir", "."),
                                () -> false),
                        access());

        assertThat(invoked).isTrue();
        assertThat(result.successful()).isTrue();
        assertThat(result.structuredData()).containsEntry("deliveryAction", "PUSH");
    }

    @Test
    void userInitiatedCommandUsesTheSameBrokerWithAProductOwnedOrigin() {
        AtomicReference<ExecutionRequest> captured = new AtomicReference<>();
        ExecutionBroker broker = new ProjectExecutionTestSupport.StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                captured.set(request);
                observer.onOutput(chunk("terminal output"));
                return result(request.id(), ExecutionStatus.SUCCEEDED, 0);
            }
        };

        var result = operations(broker, 1024, 2000)
                .executeUserInitiated(
                        new AgentRunId("terminal-audit-1"),
                        new TenantRef("tenant-1"),
                        new PrincipalRef("operator", "user"),
                        access(),
                        "git status --short",
                        ".",
                        Duration.ofSeconds(5),
                        "terminal-key");

        assertThat(captured.get().context().origin()).isEqualTo(ExecutionOrigin.PRODUCT_USER_COMMAND);
        assertThat(captured.get().context().sourceToolCallId()).isEmpty();
        assertThat(captured.get().context().runRef()).isEqualTo("terminal-audit-1");
        assertThat(captured.get().workingDirectory().projectPath().isRoot()).isTrue();
        assertThat(result.summary()).contains("Command succeeded", "terminal output");
    }

    @Test
    void preservesRealExecutionPathInSummaryAndStructuredOutputByDefault() {
        String realPath = Path.of(System.getProperty("java.io.tmpdir"), "haifa 空格", "workspace", "src", "Main.java")
                .toAbsolutePath()
                .toString();
        ExecutionBroker broker = new ProjectExecutionTestSupport.StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                observer.onOutput(chunk("built " + realPath + "\n"));
                return result(request.id(), ExecutionStatus.SUCCEEDED, 0);
            }
        };

        var result = operations(broker, 4096, 2000)
                .execute(invocation(Map.of("command", "representative build command"), () -> false), access());

        assertThat(result.summary()).contains(realPath).doesNotContain("<workspace>");
        assertThat(result.structuredData().get("output").toString())
                .contains(realPath)
                .doesNotContain("<workspace>");
    }

    @Test
    void supportsAnExplicitGenericOutputSanitizer() {
        ExecutionBroker broker = new ProjectExecutionTestSupport.StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                observer.onOutput(chunk("failure at D:\\private\\workspace\\src\\Main.java\n"));
                return result(request.id(), ExecutionStatus.FAILED, 1);
            }
        };
        var operations = operationsWithSanitizer(
                broker, 1024, 2000, value -> value.replace("D:\\private\\workspace", "<workspace>"));

        var result = operations.execute(
                invocation(Map.of("command", "representative failing command"), () -> false), access());

        assertThat(result.summary()).contains("<workspace>\\src\\Main.java").doesNotContain("D:\\private\\workspace");
        assertThat(result.structuredData().get("output").toString())
                .contains("<workspace>\\src\\Main.java")
                .doesNotContain("D:\\private\\workspace");
    }

    @Test
    void defaultCodingAssemblyChainPropagatesNoneScratchAndUnboundedProcessesToBroker() {
        AtomicReference<ExecutionRequest> captured = new AtomicReference<>();
        ExecutionBroker broker = new ProjectExecutionTestSupport.StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                captured.set(request);
                return result(request.id(), ExecutionStatus.SUCCEEDED, 0);
            }
        };

        var catalog = new ProjectToolCatalog()
                .freeze(
                        java.util.Set.of("execution_run"),
                        java.util.Set.of("execution_run"),
                        true,
                        ProjectExecutionTestSupport.provider(),
                        ProjectExecutionTestSupport.executionProfile());
        var toolDef = catalog.findByAlias(new io.haifa.agent.tool.api.ToolAlias("execution_run"))
                .orElseThrow()
                .definition();
        assertThat(toolDef.inputSchema().document())
                .containsEntry(
                        "x-haifa-scratch-spec-digest",
                        io.haifa.agent.execution.api.ExecutionScratchSpaceSpec.none()
                                .canonicalDigest());

        var operations = operations(broker, 4096, 2000);
        var result = operations.execute(
                invocation(
                        Map.of(
                                "command", "git status",
                                "relativeWorkdir", ".",
                                "timeoutMillis", 5000,
                                "operationFamily", "TEST"),
                        () -> false),
                access());

        assertThat(result.successful()).isTrue();
        ExecutionRequest request = captured.get();
        assertThat(request).isNotNull();
        assertThat(request.scratchSpace().isEmpty()).isTrue();
        assertThat(request.limits().maxProcesses()).isEmpty();
    }
}
