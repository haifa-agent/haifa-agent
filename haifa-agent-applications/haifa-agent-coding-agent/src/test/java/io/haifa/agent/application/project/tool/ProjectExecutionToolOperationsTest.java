package io.haifa.agent.application.project.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.error.AgentError;
import io.haifa.agent.core.error.AgentErrorCode;
import io.haifa.agent.core.reference.AssetRef;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.step.AgentStepId;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.RuntimeIdempotencyKey;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.core.tool.ToolExecutionError;
import io.haifa.agent.core.tool.ToolResult;
import io.haifa.agent.execution.api.ExecutionBroker;
import io.haifa.agent.execution.api.ExecutionCommandMode;
import io.haifa.agent.execution.api.ExecutionEnvironmentRef;
import io.haifa.agent.execution.api.ExecutionFailure;
import io.haifa.agent.execution.api.ExecutionId;
import io.haifa.agent.execution.api.ExecutionOutput;
import io.haifa.agent.execution.api.ExecutionOutputChannel;
import io.haifa.agent.execution.api.ExecutionOutputObserver;
import io.haifa.agent.execution.api.ExecutionPreflightException;
import io.haifa.agent.execution.api.ExecutionRequest;
import io.haifa.agent.execution.api.ExecutionResult;
import io.haifa.agent.execution.api.ExecutionStatus;
import io.haifa.agent.execution.api.ProcessOutputChunk;
import io.haifa.agent.execution.api.ResourceUsageSummary;
import io.haifa.agent.execution.api.SandboxProfileRef;
import io.haifa.agent.project.changeset.FileChangeSetId;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.runtime.core.storage.InMemoryRuntimeStore;
import io.haifa.agent.sandbox.api.SandboxException;
import io.haifa.agent.tool.api.ToolInvocationException;
import io.haifa.agent.tool.api.ToolInvocationObserver;
import io.haifa.agent.tool.api.ToolInvocationRequest;
import io.haifa.agent.tool.api.ToolProvider;
import io.haifa.agent.tool.api.ToolProviderId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ProjectExecutionToolOperationsTest {
    private static final Instant NOW = Instant.parse("2026-07-22T00:00:00Z");
    private static final WorkspaceId WORKSPACE_ID = new WorkspaceId("workspace-execution-tool");

    @Test
    void constructsTrustedShellRequestAndMapsBoundedStructuredResult() {
        AtomicReference<ExecutionRequest> captured = new AtomicReference<>();
        ExecutionBroker broker = new StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                captured.set(request);
                observer.onOutput(chunk("\u001B[31mfirst\u001B[0m\nsecond\nthird\n"));
                return result(request.id(), ExecutionStatus.FAILED, 7);
            }
        };
        var operations = operations(broker, 1024, 2);

        var result = operations.execute(
                invocation(
                        Map.of(
                                "command", "printf 'first\\nsecond\\nthird\\n' | cat > result.txt",
                                "workdir", "src",
                                "timeoutMillis", 5000,
                                "operationFamily", "TEST",
                                "description", "Write representative output"),
                        () -> false),
                access());

        assertThat(captured.get().command().mode()).isEqualTo(ExecutionCommandMode.SHELL);
        assertThat(captured.get().command().shellCommand()).contains("| cat > result.txt");
        assertThat(captured.get().workingDirectory().projectPath().value()).isEqualTo("src");
        assertThat(captured.get().limits().timeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(captured.get().context().frozenCapabilities()).contains("execution.run");
        assertThat(captured.get().scratchSpace()).isEqualTo(CodingToolchainEnvironmentProfile.defaultScratchSpace());
        assertThat(captured.get().scratchSpace().rootEnvironmentNames()).contains("GOTMPDIR");
        assertThat(captured.get().scratchSpace().childBindings())
                .singleElement()
                .satisfies(binding -> {
                    assertThat(binding.environmentName()).isEqualTo("GOCACHE");
                    assertThat(binding.relativeDirectory()).isEqualTo("go-build");
                });
        assertThat(result.successful()).isFalse();
        assertThat(result.summary())
                .contains("Command failed (exit 7)", "first", "1 lines omitted", "third")
                .doesNotContain("second");
        assertThat(result.structuredData())
                .containsEntry("toolCallId", "tool-call-1")
                .containsEntry("status", "FAILED")
                .containsEntry("exitCode", 7)
                .containsEntry("truncated", true)
                .containsEntry("outputRef", "stdout-asset")
                .containsEntry("fileChangeSetId", "changes-1")
                .containsEntry("failureCode", "PROCESS_EXIT_NONZERO")
                .containsEntry("operationFamily", "TEST")
                .containsEntry("failureCategory", "COMMAND_FAILED")
                .containsEntry("stableFailureCode", "PROCESS_EXIT_NONZERO")
                .containsEntry("resourceClass", "COMMAND")
                .containsEntry(
                        "scratchSpecDigest",
                        CodingToolchainEnvironmentProfile.defaultScratchSpace().canonicalDigest());
        assertThat(result.assets()).extracting(AssetRef::assetId).containsExactly("stdout-asset");
    }

    @Test
    void treatsDocumentedGitNonzeroVariantsAsSuccessfulToolResults() {
        ExecutionBroker broker = new StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                return result(request.id(), ExecutionStatus.FAILED, 1);
            }
        };

        var differences = operations(broker, 4096, 100)
                .execute(
                        invocation(Map.of("command", "git diff --exit-code", "operationFamily", "DIFF"), () -> false),
                        access());
        var noMatches = operations(broker, 4096, 100)
                .execute(
                        invocation(Map.of("command", "git grep missing", "operationFamily", "INSPECT"), () -> false),
                        access());

        assertThat(differences.successful()).isTrue();
        assertThat(differences.summary()).contains("expected result variant", "exit 1");
        assertThat(differences.structuredData())
                .containsEntry("status", "FAILED")
                .containsEntry("semanticOutcome", "EXPECTED_VARIANT")
                .containsEntry("semanticReasonCode", "DIFFERENCES_FOUND")
                .containsEntry("semanticInterpreterVersion", "1")
                .doesNotContainKeys("failureCategory", "stableFailureCode", "failureCode");
        assertThat(noMatches.successful()).isTrue();
        assertThat(noMatches.structuredData())
                .containsEntry("semanticOutcome", "EMPTY_RESULT")
                .containsEntry("semanticReasonCode", "NO_MATCHES");
    }

    @Test
    void keepsMissingGitRevisionsAsActionableCommandFailures() {
        ExecutionBroker broker = new StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                observer.onOutput(chunk("fatal: bad revision 'missing-ref'\n"));
                return result(request.id(), ExecutionStatus.FAILED, 128);
            }
        };

        var result = operations(broker, 4096, 100)
                .execute(
                        invocation(
                                Map.of("command", "git show missing-ref", "operationFamily", "INSPECT"), () -> false),
                        access());

        assertThat(result.successful()).isFalse();
        assertThat(result.structuredData())
                .containsEntry("semanticOutcome", "COMMAND_FAILED")
                .containsEntry("stableFailureCode", "GIT_REVISION_NOT_FOUND")
                .containsEntry("resourceClass", "REPOSITORY_REF");
        assertThat(result.structuredData().get("failureAction").toString())
                .contains("authoritative repository refs", "retrying once");
    }

    @Test
    void classifiesCrossShellMissingCommandsAsRecoverableToolchainFailures() {
        AtomicReference<ExecutionRequest> captured = new AtomicReference<>();
        ExecutionBroker broker = new StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                captured.set(request);
                observer.onOutput(chunk("The term 'fast-search' is not recognized as the name of a cmdlet. "
                        + "CommandNotFoundException\n"));
                return result(request.id(), ExecutionStatus.FAILED, 1);
            }
        };

        var result = operations(broker, 4096, 100)
                .execute(
                        invocation(
                                Map.of(
                                        "command", "fast-search needle",
                                        "operationFamily", "INSPECT"),
                                () -> false),
                        access());

        assertThat(result.structuredData())
                .containsEntry("failureCategory", "DEPENDENCY_UNAVAILABLE")
                .containsEntry("stableFailureCode", "DEPENDENCY_UNAVAILABLE")
                .containsEntry("resourceClass", "TOOLCHAIN");
        assertThat(captured.get().limits().maxStdoutBytes()).isEqualTo(4096);
        assertThat(captured.get().limits().maxStderrBytes()).isEqualTo(4096);
        assertThat(captured.get().limits().outputOverflowPolicy())
                .isEqualTo(io.haifa.agent.execution.api.ExecutionOutputOverflowPolicy.TERMINATE);
    }

    @Test
    void classifiesIsolatedSystemGitAuthenticationAsAnEligibleStableFailure() {
        ExecutionBroker broker = new StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                observer.onOutput(chunk("git@github.com: Permission denied (publickey).\n"));
                return result(request.id(), ExecutionStatus.FAILED, 128);
            }
        };

        var result = operations(broker, 4096, 100)
                .execute(
                        invocation(
                                Map.of("command", "git ls-remote origin", "operationFamily", "INSPECT"), () -> false),
                        access());

        assertThat(result.structuredData())
                .containsEntry("failureCategory", "AUTHENTICATION_UNAVAILABLE")
                .containsEntry("stableFailureCode", "GIT_AUTHENTICATION_UNAVAILABLE")
                .containsEntry("resourceClass", "AUTHENTICATION")
                .containsEntry(
                        "failureAction",
                        "Verify the current OS user's Git credential helper or SSH agent, then retry the command.");
    }

    @Test
    void reportsStableGithubCliAvailabilityAndLoginActions() {
        AtomicInteger invocation = new AtomicInteger();
        ExecutionBroker broker = new StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                if (invocation.getAndIncrement() == 0) {
                    observer.onOutput(chunk("gh: command not found\n"));
                } else {
                    observer.onOutput(chunk("You are not logged into any GitHub hosts.\n"));
                }
                return result(request.id(), ExecutionStatus.FAILED, 1);
            }
        };

        var missing = operations(broker, 4096, 100)
                .execute(
                        invocation(
                                Map.of("command", "gh pr list --repo owner/repo", "operationFamily", "INSPECT"),
                                () -> false),
                        access());
        var loggedOut = operations(broker, 4096, 100)
                .execute(
                        invocation(Map.of("command", "gh auth status", "operationFamily", "INSPECT"), () -> false),
                        access());

        assertThat(missing.structuredData())
                .containsEntry("stableFailureCode", "GH_CLI_UNAVAILABLE")
                .containsEntry("failureAction", "Install GitHub CLI and make gh available on the trusted host PATH.");
        assertThat(loggedOut.structuredData())
                .containsEntry("stableFailureCode", "GH_AUTHENTICATION_UNAVAILABLE")
                .containsEntry("failureAction", "Run gh auth login in your system terminal, then retry the command.");
    }

    @Test
    void ignoresOperationHintsForAuthorizationButStillRejectsHardBoundaries() {
        ExecutionBroker broker = new StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                return result(request.id(), ExecutionStatus.SUCCEEDED, 0);
            }
        };
        var operations = operations(broker, 4096, 100);

        var writeAsRead = operations.execute(
                invocation(Map.of("command", "git push origin feature", "operationFamily", "INSPECT"), () -> false),
                access());
        var tokenOverride = operations.execute(
                invocation(
                        Map.of("command", "env GH_TOKEN=value gh pr list", "operationFamily", "UNKNOWN"), () -> false),
                access());
        var statusAsDiff = operations.execute(
                invocation(Map.of("command", "git status --short", "operationFamily", "DIFF"), () -> false), access());

        assertThat(writeAsRead.structuredData())
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("commandRisk", "EXTERNAL_WRITE")
                .containsEntry("commandTarget", "GIT")
                .containsEntry("declaredOperationFamily", "INSPECT")
                .containsEntry("effectiveOperationFamily", "MUTATE");
        assertThat(tokenOverride.structuredData())
                .containsEntry("stableFailureCode", "COMMAND_CLASSIFICATION_REJECTED")
                .containsEntry("commandRisk", "DENIED");
        assertThat(statusAsDiff.structuredData())
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("declaredOperationFamily", "DIFF")
                .containsEntry("effectiveOperationFamily", "INSPECT")
                .containsEntry("commandOperation", "INSPECT")
                .containsEntry("commandClassificationReason", "GIT_STATUS");
    }

    @Test
    void acceptsMissingOperationFamilyAsAnUnknownDeclaredHint() {
        ExecutionBroker broker = new StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                return result(request.id(), ExecutionStatus.SUCCEEDED, 0);
            }
        };

        var result = operations(broker, 4096, 100)
                .execute(invocation(Map.of("command", "git status --short"), () -> false), access());

        assertThat(result.structuredData())
                .containsEntry("declaredOperationFamily", "UNKNOWN")
                .containsEntry("effectiveOperationFamily", "INSPECT")
                .containsEntry("operationFamily", "UNKNOWN");
    }

    @Test
    void sendsCompoundGitCommandsToTheBrokerAsHighRiskInsteadOfRejectingShellComposition() {
        AtomicReference<ExecutionRequest> captured = new AtomicReference<>();
        ExecutionBroker broker = new StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                captured.set(request);
                return result(request.id(), ExecutionStatus.SUCCEEDED, 0);
            }
        };

        var result = operations(broker, 4096, 100)
                .execute(
                        invocation(
                                Map.of(
                                        "command", "git status --short && git diff --stat",
                                        "operationFamily", "INSPECT"),
                                () -> false),
                        access());

        assertThat(captured.get().command().shellCommand()).contains("&&");
        assertThat(result.successful()).isTrue();
        assertThat(result.structuredData())
                .containsEntry("commandTarget", "GIT")
                .containsEntry("commandRisk", "UNKNOWN")
                .containsEntry("effectiveRisk", "HIGH")
                .containsEntry("commandOperation", "UNKNOWN")
                .containsEntry("commandClassificationReason", "COMPOUND_OR_WRAPPED_COMMAND")
                .containsEntry("riskResolverVersion", "2");
    }

    @Test
    void propagatesToolCancellationToTheBroker() throws Exception {
        AtomicBoolean cancellation = new AtomicBoolean();
        CountDownLatch executing = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        ExecutionBroker broker = new StubBroker() {
            private ExecutionId active;

            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                active = request.id();
                executing.countDown();
                try {
                    assertThat(cancelled.await(2, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
                return result(request.id(), ExecutionStatus.CANCELLED, null);
            }

            @Override
            public boolean cancel(ExecutionId id) {
                if (!id.equals(active)) return false;
                cancelled.countDown();
                return true;
            }
        };
        var operations = operations(broker, 1024, 2000);
        var future = java.util.concurrent.CompletableFuture.supplyAsync(() -> operations.execute(
                invocation(Map.of("command", "long-running representative command"), cancellation::get), access()));

        assertThat(executing.await(1, TimeUnit.SECONDS)).isTrue();
        cancellation.set(true);

        assertThat(future.get(3, TimeUnit.SECONDS).structuredData()).containsEntry("status", "CANCELLED");
        assertThat(cancelled.getCount()).isZero();
    }

    @Test
    void rejectsWorkspaceTraversalBeforeCallingTheBroker() {
        AtomicBoolean invoked = new AtomicBoolean();
        ExecutionBroker broker = new StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                invoked.set(true);
                return result(request.id(), ExecutionStatus.SUCCEEDED, 0);
            }
        };

        var result = operations(broker, 1024, 2000)
                .execute(
                        invocation(Map.of("command", "representative command", "workdir", "../outside"), () -> false),
                        access());

        assertThat(result.successful()).isFalse();
        assertThat(result.structuredData())
                .containsEntry("failureCategory", "INVALID_INPUT")
                .containsEntry("stableFailureCode", "WORKDIR_INVALID")
                .containsEntry("failureAction", "Use a normalized path relative to the authorized workspace root.");
        assertThat(invoked).isFalse();
    }

    @Test
    void rejectsLeadingAbsoluteDirectoryChangeBeforePolicyBoundExecution() {
        AtomicBoolean invoked = new AtomicBoolean();
        ExecutionBroker broker = new StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                invoked.set(true);
                return result(request.id(), ExecutionStatus.SUCCEEDED, 0);
            }
        };

        for (String command : List.of(
                "cd /workspace && go test ./...", " cd '/home/user' && make test", "cd C:\\temp && gradlew test")) {
            var result = operations(broker, 1024, 2000)
                    .execute(invocation(Map.of("command", command, "operationFamily", "TEST"), () -> false), access());

            assertThat(result.successful()).isFalse();
            assertThat(result.structuredData())
                    .containsEntry("failureCategory", "INVALID_INPUT")
                    .containsEntry("stableFailureCode", "ABSOLUTE_WORKDIR_FORBIDDEN");
        }
        assertThat(invoked).isFalse();
    }

    @Test
    void rejectsAbsoluteWorkdirBeforeCallingTheBroker() {
        AtomicBoolean invoked = new AtomicBoolean();
        ExecutionBroker broker = new StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                invoked.set(true);
                return result(request.id(), ExecutionStatus.SUCCEEDED, 0);
            }
        };

        var result = operations(broker, 1024, 2000)
                .execute(
                        invocation(
                                Map.of("command", "go test ./...", "workdir", "/workspace", "operationFamily", "TEST"),
                                () -> false),
                        access());

        assertThat(result.successful()).isFalse();
        assertThat(result.structuredData())
                .containsEntry("failureCategory", "INVALID_INPUT")
                .containsEntry("stableFailureCode", "ABSOLUTE_WORKDIR_FORBIDDEN");
        assertThat(invoked).isFalse();
    }

    @Test
    void providerAdapterDoesNotAcknowledgeKnownRejectionBeforeDispatch() {
        AtomicInteger dispatches = new AtomicInteger();
        AtomicInteger acknowledgements = new AtomicInteger();
        var executor = new ProjectToolExecutor(
                (runId, principal) -> access(),
                (toolName, workspaceId, principal, runRef, policyDecisionRef, arguments) -> {
                    throw new AssertionError("file operations must not run");
                },
                operations(new StubBroker() {}, 1024, 2000));
        var result = executor.invoke(invocation(
                Map.of("command", "git status --short", "workdir", "C:\\outside", "operationFamily", "INSPECT"),
                () -> false,
                new ToolInvocationObserver() {
                    @Override
                    public void dispatched() {
                        dispatches.incrementAndGet();
                    }

                    @Override
                    public void acknowledged() {
                        acknowledgements.incrementAndGet();
                    }
                }));

        assertThat(result.successful()).isFalse();
        assertThat(result.structuredData()).containsEntry("stableFailureCode", "ABSOLUTE_WORKDIR_FORBIDDEN");
        assertThat(dispatches).hasValue(0);
        assertThat(acknowledgements).hasValue(0);
    }

    @Test
    void userInitiatedCommandUsesTheSameBrokerAndPolicyReference() {
        AtomicReference<ExecutionRequest> captured = new AtomicReference<>();
        ExecutionBroker broker = new StubBroker() {
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
                        "terminal-key",
                        "policy-terminal-1");

        assertThat(captured.get().context().policyDecisionRef()).isEqualTo("policy-terminal-1");
        assertThat(captured.get().context().runRef()).isEqualTo("terminal-audit-1");
        assertThat(captured.get().workingDirectory().projectPath().isRoot()).isTrue();
        assertThat(result.summary()).contains("Command succeeded", "terminal output");
    }

    @Test
    void preservesRealExecutionPathInSummaryAndStructuredOutputByDefault() {
        String realPath = Path.of(System.getProperty("java.io.tmpdir"), "haifa 空格", "workspace", "src", "Main.java")
                .toAbsolutePath()
                .toString();
        ExecutionBroker broker = new StubBroker() {
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
        ExecutionBroker broker = new StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                observer.onOutput(chunk("failure at D:\\private\\workspace\\src\\Main.java\n"));
                return result(request.id(), ExecutionStatus.FAILED, 1);
            }
        };
        var operations = new ProjectExecutionToolOperations(
                broker,
                () -> "execution-1",
                () -> NOW,
                new ExecutionEnvironmentRef(List.of("environment-1")),
                new SandboxProfileRef("shell", "1"),
                Duration.ofMinutes(2),
                Duration.ofMinutes(30),
                1024,
                2000,
                8,
                ExecutionOutputObserver.noop(),
                value -> value.replace("D:\\private\\workspace", "<workspace>"));

        var result = operations.execute(
                invocation(Map.of("command", "representative failing command"), () -> false), access());

        assertThat(result.summary()).contains("<workspace>\\src\\Main.java").doesNotContain("D:\\private\\workspace");
        assertThat(result.structuredData().get("output").toString())
                .contains("<workspace>\\src\\Main.java")
                .doesNotContain("D:\\private\\workspace");
    }

    @Test
    void marksDispatchOnlyAfterTheBrokerReportsProcessStart() {
        AtomicInteger dispatches = new AtomicInteger();
        AtomicInteger acknowledgements = new AtomicInteger();
        ExecutionBroker broker = new StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                assertThat(dispatches).hasValue(0);
                assertThat(acknowledgements).hasValue(0);
                observer.onStarted();
                assertThat(dispatches).hasValue(1);
                assertThat(acknowledgements).hasValue(0);
                return result(request.id(), ExecutionStatus.SUCCEEDED, 0);
            }
        };

        operations(broker, 1024, 2000)
                .execute(
                        invocation(
                                Map.of("command", "representative command"), () -> false, new ToolInvocationObserver() {
                                    @Override
                                    public void dispatched() {
                                        dispatches.incrementAndGet();
                                    }

                                    @Override
                                    public void acknowledged() {
                                        acknowledgements.incrementAndGet();
                                    }
                                }),
                        access());

        assertThat(dispatches).hasValue(1);
        assertThat(acknowledgements).hasValue(1);
    }

    @Test
    void mapsPreExecutionObserverFailureAsKnownNotDispatchedFailure() {
        ExecutionBroker broker = new StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                throw new ExecutionPreflightException(
                        "WORKSPACE_CHANGE_OBSERVER_UNAVAILABLE",
                        "workspace change observation could not be established before execution",
                        new IllegalStateException("observer failed"));
            }
        };

        assertThatThrownBy(() -> operations(broker, 1024, 2000)
                        .execute(invocation(Map.of("command", "representative command"), () -> false), access()))
                .isInstanceOfSatisfying(ToolInvocationException.class, exception -> {
                    assertThat(exception.failureCode()).isEqualTo("WORKSPACE_CHANGE_OBSERVER_UNAVAILABLE");
                    assertThat(exception.dispatchState())
                            .isEqualTo(io.haifa.agent.tool.api.ToolDispatchState.NOT_DISPATCHED);
                });
    }

    @Test
    void preservesStableSandboxFailureCodeBeforeProcessDispatch() {
        ExecutionBroker broker = new StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                throw new SandboxException("SANDBOX_PROVISION_FAILED", "sandbox setup failed");
            }
        };

        assertThatThrownBy(() -> operations(broker, 1024, 2000)
                        .execute(invocation(Map.of("command", "git status --short"), () -> false), access()))
                .isInstanceOfSatisfying(ToolInvocationException.class, exception -> {
                    assertThat(exception.failureCode()).isEqualTo("SANDBOX_PROVISION_FAILED");
                    assertThat(exception.dispatchState())
                            .isEqualTo(io.haifa.agent.tool.api.ToolDispatchState.NOT_DISPATCHED);
                });
    }

    @Test
    void controlledPermissionRequestRerunsOnlyTheExactEligibleRemoteFailure() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        store.appendToolCall(failedExecutionCall(
                "prior-tool-call",
                Map.of(
                        "command", "git ls-remote origin",
                        "workdir", ".",
                        "operationFamily", "INSPECT"),
                "GIT_AUTHENTICATION_UNAVAILABLE"));
        AtomicReference<ExecutionRequest> captured = new AtomicReference<>();
        ExecutionBroker broker = new StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                captured.set(request);
                return result(request.id(), ExecutionStatus.SUCCEEDED, 0);
            }
        };
        var permissionOperations = new ProjectPermissionRequestOperations(
                store, operations(broker, 4096, 100), deniedExecutionProfile(), executionProfile());

        var result = permissionOperations.execute(
                permissionInvocation(Map.of(
                        "priorToolCallId",
                        "prior-tool-call",
                        "requestedPermission",
                        ProjectPermissionRequestOperations.HOST_NETWORK_ACCESS,
                        "justification",
                        "Read the configured Git remote",
                        "command",
                        "git ls-remote origin",
                        "workdir",
                        ".")),
                access());

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().command().shellCommand()).isEqualTo("git ls-remote origin");
        assertThat(result.successful()).isTrue();
        assertThat(result.structuredData())
                .containsEntry("toolCallId", "permission-tool-call")
                .containsEntry("priorToolCallId", "prior-tool-call")
                .containsEntry("requestedPermission", ProjectPermissionRequestOperations.HOST_NETWORK_ACCESS)
                .containsEntry("permissionEscalated", true)
                .containsEntry("declaredOperationFamily", "UNKNOWN")
                .containsEntry("effectiveOperationFamily", "INSPECT");
    }

    @Test
    void controlledPermissionRequestCannotInventOrChangeThePriorIntent() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        store.appendToolCall(failedExecutionCall(
                "prior-tool-call",
                Map.of("command", "git ls-remote origin", "operationFamily", "INSPECT"),
                "NETWORK_UNAVAILABLE"));
        ExecutionBroker broker = new StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                throw new AssertionError("an ineligible request must not reach elevated execution");
            }
        };
        var permissionOperations = new ProjectPermissionRequestOperations(
                store, operations(broker, 4096, 100), deniedExecutionProfile(), executionProfile());

        var result = permissionOperations.execute(
                permissionInvocation(Map.of(
                        "priorToolCallId",
                        "prior-tool-call",
                        "requestedPermission",
                        ProjectPermissionRequestOperations.HOST_NETWORK_ACCESS,
                        "justification",
                        "Change the command",
                        "command",
                        "git fetch origin",
                        "operationFamily",
                        "MUTATE")),
                access());

        assertThat(result.successful()).isFalse();
        assertThat(result.structuredData()).containsEntry("stableFailureCode", "PERMISSION_REQUEST_INTENT_MISMATCH");
    }

    @Test
    void controlledPermissionRequestCannotReuseAnAlreadyConsumedAttempt() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        store.appendToolCall(failedExecutionCall(
                "prior-tool-call",
                Map.of("command", "git ls-remote origin", "operationFamily", "INSPECT"),
                "NETWORK_UNAVAILABLE"));
        store.appendToolCall(completedPermissionCall("first-permission-call", "prior-tool-call"));
        ExecutionBroker broker = new StubBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                throw new AssertionError("a consumed permission attempt must not execute again");
            }
        };
        var permissionOperations = new ProjectPermissionRequestOperations(
                store, operations(broker, 4096, 100), deniedExecutionProfile(), executionProfile());

        var result = permissionOperations.execute(
                permissionInvocation(Map.of(
                        "priorToolCallId",
                        "prior-tool-call",
                        "requestedPermission",
                        ProjectPermissionRequestOperations.HOST_NETWORK_ACCESS,
                        "justification",
                        "Retry again",
                        "command",
                        "git ls-remote origin",
                        "operationFamily",
                        "INSPECT")),
                access());

        assertThat(result.successful()).isFalse();
        assertThat(result.structuredData()).containsEntry("stableFailureCode", "PERMISSION_REQUEST_ALREADY_USED");
    }

    @Test
    void controlledPermissionRequestRejectsNonGitAndDestructiveCommands() {
        for (String command : List.of("curl https://example.test", "git clean -fd")) {
            InMemoryRuntimeStore store = new InMemoryRuntimeStore();
            store.appendToolCall(failedExecutionCall(
                    "prior-tool-call", Map.of("command", command, "operationFamily", "MUTATE"), "NETWORK_UNAVAILABLE"));
            ExecutionBroker broker = new StubBroker() {
                @Override
                public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                    throw new AssertionError("an ineligible command must not reach elevated execution");
                }
            };
            var permissionOperations = new ProjectPermissionRequestOperations(
                    store, operations(broker, 4096, 100), deniedExecutionProfile(), executionProfile());

            var result = permissionOperations.execute(
                    permissionInvocation(Map.of(
                            "priorToolCallId",
                            "prior-tool-call",
                            "requestedPermission",
                            ProjectPermissionRequestOperations.HOST_NETWORK_ACCESS,
                            "justification",
                            "Try an ineligible command",
                            "command",
                            command,
                            "operationFamily",
                            "MUTATE")),
                    access());

            assertThat(result.successful()).as(command).isFalse();
            assertThat(result.structuredData())
                    .as(command)
                    .containsEntry("stableFailureCode", "PERMISSION_REQUEST_NOT_ELIGIBLE");
        }
    }

    private static ProjectExecutionToolOperations operations(
            ExecutionBroker broker, int maximumOutputBytes, int maximumOutputLines) {
        return new ProjectExecutionToolOperations(
                broker,
                () -> "execution-1",
                () -> NOW,
                new ExecutionEnvironmentRef(List.of("environment-1")),
                new SandboxProfileRef("shell", "1"),
                Duration.ofMinutes(2),
                Duration.ofMinutes(30),
                maximumOutputBytes,
                maximumOutputLines,
                8,
                ExecutionOutputObserver.noop(),
                java.util.function.UnaryOperator.identity(),
                CodingToolchainEnvironmentProfile.defaultScratchSpace());
    }

    private static ToolInvocationRequest invocation(
            Map<String, Object> arguments, io.haifa.agent.tool.api.ToolCancellation cancellation) {
        return invocation(arguments, cancellation, ToolInvocationObserver.noop());
    }

    private static ToolInvocationRequest invocation(
            Map<String, Object> arguments,
            io.haifa.agent.tool.api.ToolCancellation cancellation,
            ToolInvocationObserver observer) {
        var binding = new ProjectToolCatalog()
                .freeze(Set.of("execution.run"), Set.of("execution.run"), true, provider(), executionProfile())
                .snapshot()
                .bindings()
                .getFirst();
        return new ToolInvocationRequest(
                binding,
                new ToolCallId("tool-call-1"),
                new AgentRunId("run-1"),
                new TenantRef("tenant-1"),
                new PrincipalRef("operator", "user"),
                new ToolArguments("haifa.execution.run.input", "1.0.0", arguments),
                NOW.plusSeconds(30),
                Optional.of("execution-key"),
                Optional.of("policy-1"),
                cancellation,
                List.of(),
                observer);
    }

    private static ToolInvocationRequest permissionInvocation(Map<String, Object> arguments) {
        var binding = new ProjectToolCatalog()
                .freeze(
                        Set.of(ProjectPermissionRequestOperations.TOOL_NAME),
                        Set.of("execution.run"),
                        true,
                        provider(),
                        List.of(),
                        List.of(),
                        List.of(),
                        deniedExecutionProfile(),
                        executionProfile(),
                        CodingToolchainEnvironmentProfile.defaultScratchSpace())
                .snapshot()
                .bindings()
                .getFirst();
        return new ToolInvocationRequest(
                binding,
                new ToolCallId("permission-tool-call"),
                new AgentRunId("run-1"),
                new TenantRef("tenant-1"),
                new PrincipalRef("operator", "user"),
                new ToolArguments("haifa.execution.request_permissions.input", "1.0.0", arguments),
                NOW.plusSeconds(30),
                Optional.of("permission-key"),
                Optional.of("permission-policy-1"),
                () -> false,
                List.of(),
                ToolInvocationObserver.noop());
    }

    private static ToolCall failedExecutionCall(
            String toolCallId, Map<String, Object> arguments, String stableFailureCode) {
        ToolCall call = new ToolCall(
                new ToolCallId(toolCallId),
                new AgentRunId("run-1"),
                new AgentStepId("step-1"),
                new ProviderToolCallCorrelationId("provider-" + toolCallId),
                new RuntimeIdempotencyKey("idempotency-" + toolCallId),
                "execution.run",
                "1.0.0",
                new ToolArguments("haifa.execution.run.input", "1.0.0", arguments),
                NOW.minusSeconds(2));
        call.beginValidation();
        call.beginPolicyCheck();
        call.start(NOW.minusSeconds(1));
        call.fail(
                new ToolExecutionError(new AgentError(
                        AgentErrorCode.TOOL_BUSINESS_FAILURE,
                        Map.of("stableFailureCode", stableFailureCode),
                        "diagnostic-tool-failure",
                        NOW)),
                NOW);
        return call;
    }

    private static ToolCall completedPermissionCall(String toolCallId, String priorToolCallId) {
        ToolCall call = new ToolCall(
                new ToolCallId(toolCallId),
                new AgentRunId("run-1"),
                new AgentStepId("permission-step-1"),
                new ProviderToolCallCorrelationId("provider-" + toolCallId),
                new RuntimeIdempotencyKey("idempotency-" + toolCallId),
                ProjectPermissionRequestOperations.TOOL_NAME,
                "1.0.0",
                new ToolArguments(
                        "haifa.execution.request_permissions.input",
                        "1.0.0",
                        Map.of("priorToolCallId", priorToolCallId)),
                NOW.minusSeconds(2));
        call.beginValidation();
        call.beginPolicyCheck();
        call.start(NOW.minusSeconds(1));
        call.complete(new ToolResult(true, "done", Map.of(), List.of(), List.of(), false), NOW);
        return call;
    }

    private static RunWorkspaceAccess access() {
        return new RunWorkspaceAccess(WORKSPACE_ID, Set.of("execution.run"));
    }

    private static io.haifa.agent.sandbox.api.SandboxProfile executionProfile() {
        return new io.haifa.agent.sandbox.api.SandboxProfile(
                new SandboxProfileRef("shell", "1"),
                "host-guarded",
                io.haifa.agent.sandbox.api.SandboxConfigurationDigest.sha256Fields(List.of("test")),
                Set.of(),
                Set.of(),
                true,
                io.haifa.agent.sandbox.api.NetworkPolicy.ALLOW,
                io.haifa.agent.sandbox.api.SandboxFilesystemPolicy.hostCompatible(),
                new io.haifa.agent.sandbox.api.SandboxCapabilities(true, false, false, false, false));
    }

    private static io.haifa.agent.sandbox.api.SandboxProfile deniedExecutionProfile() {
        return new io.haifa.agent.sandbox.api.SandboxProfile(
                new SandboxProfileRef("shell-denied", "1"),
                "local-native",
                io.haifa.agent.sandbox.api.SandboxConfigurationDigest.sha256Fields(List.of("denied")),
                Set.of(),
                Set.of(),
                true,
                io.haifa.agent.sandbox.api.NetworkPolicy.DENY,
                io.haifa.agent.sandbox.api.SandboxFilesystemPolicy.hostCompatible(),
                new io.haifa.agent.sandbox.api.SandboxCapabilities(true, false, true, false, false));
    }

    private static ProcessOutputChunk chunk(String value) {
        return new ProcessOutputChunk(
                ExecutionOutputChannel.STDOUT, value.getBytes(StandardCharsets.UTF_8), false, false);
    }

    private static ExecutionResult result(ExecutionId id, ExecutionStatus status, Integer exitCode) {
        var asset = new AssetRef("stdout-asset", "text/plain", "stdout.txt");
        return new ExecutionResult(
                id,
                status,
                exitCode,
                NOW,
                NOW.plusSeconds(1),
                new ExecutionOutput("stored stdout", asset, 13, "sha-stdout", false, false),
                new ExecutionOutput("", null, 0, "sha-stderr", false, false),
                new FileChangeSetId("changes-1"),
                "session-1",
                new ResourceUsageSummary(Duration.ofSeconds(1), 1),
                status == ExecutionStatus.FAILED
                        ? new ExecutionFailure("PROCESS_EXIT_NONZERO", "process exited with a non-zero code")
                        : null,
                false);
    }

    private static ToolProvider provider() {
        return new ToolProvider() {
            @Override
            public ToolProviderId id() {
                return ProjectToolExecutor.PROVIDER_ID;
            }

            @Override
            public io.haifa.agent.core.tool.ToolResult invoke(ToolInvocationRequest request) {
                throw new AssertionError("catalog-only provider");
            }
        };
    }

    private abstract static class StubBroker implements ExecutionBroker {
        @Override
        public ExecutionResult execute(ExecutionRequest request) {
            return execute(request, ExecutionOutputObserver.noop());
        }

        @Override
        public boolean cancel(ExecutionId id) {
            return false;
        }

        @Override
        public Optional<ExecutionResult> find(ExecutionId id) {
            return Optional.empty();
        }
    }
}
