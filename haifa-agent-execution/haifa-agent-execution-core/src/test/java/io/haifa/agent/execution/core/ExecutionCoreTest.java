package io.haifa.agent.execution.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.execution.api.ExecutionCommand;
import io.haifa.agent.execution.api.ExecutionCommandMode;
import io.haifa.agent.execution.api.ExecutionEnvironmentRef;
import io.haifa.agent.execution.api.ExecutionId;
import io.haifa.agent.execution.api.ExecutionLimits;
import io.haifa.agent.execution.api.ExecutionOutputChannel;
import io.haifa.agent.execution.api.ExecutionOutputObserver;
import io.haifa.agent.execution.api.ExecutionPreflightException;
import io.haifa.agent.execution.api.ExecutionRequest;
import io.haifa.agent.execution.api.ExecutionStatus;
import io.haifa.agent.execution.api.ManagedProcessRequest;
import io.haifa.agent.execution.api.ProcessInputChunk;
import io.haifa.agent.execution.api.ResolvedExecutionEnvironment;
import io.haifa.agent.execution.api.SandboxProfileRef;
import io.haifa.agent.execution.api.TrustedExecutionContext;
import io.haifa.agent.execution.core.change.LocalIncrementalWorkspaceChangeObserver;
import io.haifa.agent.execution.core.change.WorkspaceChangeIgnorePolicy;
import io.haifa.agent.execution.core.change.WorkspaceChangeObservation;
import io.haifa.agent.execution.core.change.WorkspaceChangeObserver;
import io.haifa.agent.execution.core.change.WorkspaceChangeObserverException;
import io.haifa.agent.execution.core.manifest.ManifestBudget;
import io.haifa.agent.execution.core.manifest.ManifestDiffService;
import io.haifa.agent.execution.core.manifest.WorkspaceManifestService;
import io.haifa.agent.execution.core.store.InMemoryExecutionOutputStore;
import io.haifa.agent.execution.core.store.InMemoryExecutionStore;
import io.haifa.agent.project.binding.WorkspaceBinding;
import io.haifa.agent.project.binding.WorkspaceBindingId;
import io.haifa.agent.project.binding.WorkspaceBindingMode;
import io.haifa.agent.project.binding.WorkspaceLocationRef;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.hostworkspace.HostWorkspaceFileService;
import io.haifa.agent.project.hostworkspace.HostWorkspaceLocationStore;
import io.haifa.agent.project.hostworkspace.SensitivePathPolicy;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.path.WorkspacePath;
import io.haifa.agent.project.store.InMemoryWorkspaceBindingStore;
import io.haifa.agent.project.store.InMemoryWorkspaceStore;
import io.haifa.agent.project.workspace.Workspace;
import io.haifa.agent.project.workspace.WorkspaceCapabilitySet;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.project.workspace.WorkspacePermissionSet;
import io.haifa.agent.project.workspace.WorkspacePurpose;
import io.haifa.agent.project.workspace.WorkspaceRevision;
import io.haifa.agent.project.workspace.WorkspaceRoot;
import io.haifa.agent.sandbox.api.NetworkPolicy;
import io.haifa.agent.sandbox.api.SandboxCapabilities;
import io.haifa.agent.sandbox.api.SandboxException;
import io.haifa.agent.sandbox.api.SandboxExecution;
import io.haifa.agent.sandbox.api.SandboxFilesystemPolicy;
import io.haifa.agent.sandbox.api.SandboxManagedProcess;
import io.haifa.agent.sandbox.api.SandboxProcessResult;
import io.haifa.agent.sandbox.api.SandboxProcessStatus;
import io.haifa.agent.sandbox.api.SandboxProfile;
import io.haifa.agent.sandbox.api.SandboxProvider;
import io.haifa.agent.sandbox.api.SandboxSession;
import io.haifa.agent.sandbox.api.SandboxSessionId;
import io.haifa.agent.sandbox.api.SandboxWorkspaceAccess;
import io.haifa.agent.sandbox.api.WorkspaceMount;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExecutionCoreTest {
    private static final Instant NOW = Instant.parse("2026-07-21T00:00:00Z");

    @TempDir
    Path root;

    @Test
    void brokerFreezesAuthorizationCapturesChangesRedactsOutputAndReplays() throws Exception {
        Files.writeString(root.resolve("before.txt"), "before\n");
        Fixture fixture = fixture();
        AtomicInteger policyCalls = new AtomicInteger();
        SandboxProvider provider = fakeProvider(
                () -> {
                    try {
                        Files.writeString(root.resolve("created.txt"), "created\n");
                    } catch (java.io.IOException exception) {
                        throw new RuntimeException(exception);
                    }
                },
                ("secret-token\nhttps://user:remote-secret@github.example/repo.git\n" + "x".repeat(5000))
                        .getBytes(StandardCharsets.UTF_8));
        DefaultExecutionBroker broker = fixture.broker(provider, request -> policyCalls.incrementAndGet());
        ExecutionRequest request = fixture.request("execution-1", "key-1", Set.of("execution.run"), List.of("fake"));

        var result = broker.execute(request);

        assertThat(result.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(result.stdout().summary()).doesNotContain("secret-token", "remote-secret");
        assertThat(result.stdout().optionalAssetRef()).isPresent();
        byte[] stored = fixture.outputs.load(result.stdout().assetRef()).orElseThrow();
        assertThat(new String(stored, StandardCharsets.UTF_8))
                .doesNotContain("secret-token", "remote-secret")
                .contains("***", "https://***@github.example/repo.git");
        assertThat(broker.execute(request).replayed()).isTrue();
        assertThat(policyCalls).hasValue(2);

        assertThatThrownBy(() -> broker.execute(
                        fixture.request("execution-2", "key-1", Set.of("execution.run"), List.of("different"))))
                .isInstanceOf(ExecutionRejectedException.class);
        assertThatThrownBy(() -> broker.execute(fixture.request("execution-3", "key-3", Set.of(), List.of("fake"))))
                .isInstanceOfSatisfying(ExecutionRejectedException.class, exception -> assertThat(exception.code())
                        .isEqualTo("CAPABILITY_DENIED"));
    }

    @Test
    void brokerPreservesConfirmedProcessLimitAsATerminalResourceFailure() {
        Fixture fixture = fixture();
        SandboxProvider provider = fakeProvider(() -> {}, new byte[0], SandboxProcessStatus.PROCESS_LIMIT_EXCEEDED);
        DefaultExecutionBroker broker = fixture.broker(provider, request -> {});

        var result = broker.execute(
                fixture.request("process-limit", "process-limit-key", Set.of("execution.run"), List.of("fake")));

        assertThat(result.status()).isEqualTo(ExecutionStatus.PROCESS_LIMIT_EXCEEDED);
        assertThat(result.exitCode()).isNull();
        assertThat(result.optionalFailure())
                .hasValueSatisfying(failure -> assertThat(failure.code()).isEqualTo("PROCESS_LIMIT_EXCEEDED"));
    }

    @Test
    void streamingObserverRedactsSecretsSplitAcrossChunks() {
        Fixture fixture = fixture();
        SandboxProvider provider = new SandboxProvider() {
            @Override
            public String providerId() {
                return "streaming-fake";
            }

            @Override
            public SandboxCapabilities capabilities() {
                return new SandboxCapabilities(true, true, true, true, true);
            }

            @Override
            public SandboxSession open(SandboxProfile profile, WorkspaceMount mount) {
                return new SandboxSession() {
                    @Override
                    public SandboxSessionId id() {
                        return new SandboxSessionId("streaming-session");
                    }

                    @Override
                    public SandboxProcessResult execute(SandboxExecution execution) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public SandboxProcessResult execute(SandboxExecution execution, ExecutionOutputObserver observer) {
                        observer.onStarted();
                        observer.onOutput(new io.haifa.agent.execution.api.ProcessOutputChunk(
                                ExecutionOutputChannel.STDOUT,
                                "secret-".getBytes(StandardCharsets.UTF_8),
                                false,
                                false));
                        observer.onOutput(new io.haifa.agent.execution.api.ProcessOutputChunk(
                                ExecutionOutputChannel.STDOUT,
                                "token\n".getBytes(StandardCharsets.UTF_8),
                                true,
                                false));
                        return new SandboxProcessResult(
                                SandboxProcessStatus.EXITED,
                                0,
                                "secret-token\n".getBytes(StandardCharsets.UTF_8),
                                new byte[0],
                                NOW,
                                NOW.plusSeconds(1),
                                false,
                                false,
                                true,
                                1);
                    }

                    @Override
                    public boolean cancel() {
                        return true;
                    }

                    @Override
                    public void close() {}
                };
            }
        };
        ResolvedExecutionEnvironment resolvedEnv =
                ResolvedExecutionEnvironment.of(Map.of("SECRET", "secret-token"), Set.of("SECRET"));
        DefaultExecutionBroker broker = fixture.broker(provider, request -> {}, resolvedEnv);
        var streamed = new java.io.ByteArrayOutputStream();
        AtomicInteger starts = new AtomicInteger();

        var result = broker.execute(
                fixture.request("streamed", "streamed-key", Set.of("execution.run"), List.of("fake")),
                new ExecutionOutputObserver() {
                    @Override
                    public void onStarted() {
                        starts.incrementAndGet();
                    }

                    @Override
                    public void onOutput(io.haifa.agent.execution.api.ProcessOutputChunk chunk) {
                        streamed.writeBytes(chunk.bytes());
                    }
                });

        assertThat(starts).hasValue(1);
        assertThat(new String(streamed.toByteArray(), StandardCharsets.UTF_8)).isEqualTo("***\n");
        assertThat(result.stdout().summary()).isEqualTo("***\n");

        var observerFailureResult = broker.execute(
                fixture.request("observer-failure", "observer-failure-key", Set.of("execution.run"), List.of("fake")),
                chunk -> {
                    throw new IllegalStateException("presentation failed");
                });
        assertThat(observerFailureResult.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
    }

    @Test
    void keepsBaselineEnvironmentValuesVisibleWhileRedactingSecretLikeValues() {
        Fixture fixture = fixture();
        Map<String, String> environment = Map.of(
                "PATH", "C:\\Windows\\System32",
                "USERPROFILE", "C:\\Users\\dev",
                "GIT_PAGER", "cat",
                "GIT_TERMINAL_PROMPT", "0",
                "PORT", "8080",
                "BUILD_ID", "1234",
                "COLORTERM", "truecolor",
                "SHORT_SECRET", "k9x",
                "CUSTOM_TOKENISH", "supersecret1");
        ResolvedExecutionEnvironment resolvedEnv =
                ResolvedExecutionEnvironment.of(environment, Set.of("SHORT_SECRET", "CUSTOM_TOKENISH"));
        byte[] stdout =
                ("port 8080 build 1234 color truecolor; cat in C:\\Users\\dev and C:\\Windows\\System32; key: k9x; token: supersecret1\n"
                                + "clone url: https://user:secret-pass@github.example/repo.git\n")
                        .getBytes(StandardCharsets.UTF_8);
        DefaultExecutionBroker broker = fixture.broker(fakeProvider(() -> {}, stdout), request -> {}, resolvedEnv);

        var result = broker.execute(
                fixture.request("redaction-policy", "redaction-policy-key", Set.of("execution.run"), List.of("fake")));

        assertThat(result.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(result.stdout().summary())
                .contains(
                        "port 8080 build 1234 color truecolor; cat in C:\\Users\\dev and C:\\Windows\\System32; key: ***; token: ***\n")
                .doesNotContain("supersecret1", "secret-pass")
                .contains("https://***@github.example/repo.git");

        var streamed = new java.io.ByteArrayOutputStream();
        var observer = new RedactingExecutionOutputObserver(
                chunk -> streamed.writeBytes(chunk.bytes()),
                RedactingExecutionOutputObserver.extractSecrets(resolvedEnv));
        observer.onOutput(new io.haifa.agent.execution.api.ProcessOutputChunk(
                ExecutionOutputChannel.STDOUT, stdout, true, false));
        assertThat(new String(streamed.toByteArray(), StandardCharsets.UTF_8))
                .contains(
                        "port 8080 build 1234 color truecolor; cat in C:\\Users\\dev and C:\\Windows\\System32; key: ***; token: ***\n")
                .doesNotContain("supersecret1", "secret-pass")
                .contains("https://***@github.example/repo.git");
    }

    @Test
    void rejectsUnavailablePreExecutionObserverBeforeOpeningTheProcess() {
        Fixture fixture = fixture();
        AtomicInteger processStarts = new AtomicInteger();
        SandboxProvider provider = fakeProvider(processStarts::incrementAndGet, new byte[0]);
        WorkspaceChangeObserver unavailable = ignored -> {
            throw WorkspaceChangeObserverException.resyncFailed(new IllegalStateException("observer unavailable"));
        };
        DefaultExecutionBroker broker = fixture.broker(provider, ignored -> {}, fixture.profile(provider), unavailable);

        assertThatThrownBy(() -> broker.execute(fixture.request(
                        "observer-failure", "observer-failure-key", Set.of("execution.run"), List.of("fake"))))
                .isInstanceOfSatisfying(ExecutionPreflightException.class, exception -> assertThat(exception.code())
                        .isEqualTo("WORKSPACE_CHANGE_OBSERVER_UNAVAILABLE"));
        assertThat(processStarts).hasValue(0);
    }

    @Test
    void reportsPostExecutionObserverConvergenceFailureSeparately() {
        Fixture fixture = fixture();
        SandboxProvider provider = fakeProvider(() -> {}, new byte[0]);
        WorkspaceChangeObserver failingCompletion = ignored -> new WorkspaceChangeObservation() {
            @Override
            public List<io.haifa.agent.project.changeset.FileChange> complete() {
                throw new IllegalStateException("observer resync failed");
            }
        };
        DefaultExecutionBroker broker =
                fixture.broker(provider, ignored -> {}, fixture.profile(provider), failingCompletion);

        var result = broker.execute(
                fixture.request("observer-resync", "observer-resync-key", Set.of("execution.run"), List.of("fake")));

        assertThat(result.status()).isEqualTo(ExecutionStatus.UNKNOWN);
        assertThat(result.failure().code()).isEqualTo("WORKSPACE_CHANGE_OBSERVER_RESYNC_FAILED");
    }

    @Test
    void manifestDiffRecognizesMoveAsOneCorrelatedChange() throws Exception {
        Files.writeString(root.resolve("old.txt"), "same\n");
        Fixture fixture = fixture();
        var before = fixture.manifests.capture(fixture.workspaceId);
        Files.move(root.resolve("old.txt"), root.resolve("new.txt"));
        var after = fixture.manifests.capture(fixture.workspaceId);
        var changes = new ManifestDiffService().diff(before, after);
        assertThat(changes).singleElement().satisfies(change -> {
            assertThat(change.type()).isEqualTo(io.haifa.agent.project.changeset.FileChangeType.MOVE);
            assertThat(change.path().value()).isEqualTo("old.txt");
            assertThat(change.destination().value()).isEqualTo("new.txt");
        });
    }

    @Test
    void managedSessionUsesTheSameAuthorizationRedactionAuditAndCompletionPath() throws Exception {
        Fixture fixture = fixture();
        var provider = managedProvider();
        DefaultExecutionBroker broker = fixture.broker(provider, request -> {});
        ExecutionRequest request =
                fixture.request("managed-execution", "managed-key", Set.of("execution.run"), List.of("fake"));

        try (var session = broker.openManagedSession(new ManagedProcessRequest(request))) {
            session.write(new ProcessInputChunk("request\n".getBytes(StandardCharsets.UTF_8)));
            var output1 = session.read(Duration.ofSeconds(1));
            var output2 = session.read(Duration.ofSeconds(1));
            String accumulated = (output1.map(chunk -> new String(chunk.bytes(), StandardCharsets.UTF_8))
                            .orElse("")
                    + output2.map(chunk -> new String(chunk.bytes(), StandardCharsets.UTF_8))
                            .orElse(""));
            assertThat(accumulated).doesNotContain("remote-secret").contains("https://***@github.example/repo.git");
            assertThat(session.exit()
                            .get(2, java.util.concurrent.TimeUnit.SECONDS)
                            .status())
                    .isEqualTo(ExecutionStatus.SUCCEEDED);
        }

        var result = broker.find(request.id()).orElseThrow();
        assertThat(result.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(result.stdout().summary()).doesNotContain("remote-secret");
    }

    @Test
    void managedSessionFlushesTrailingCarryoverOnProcessExitWithoutDataLoss() throws Exception {
        Fixture fixture = fixture();
        var provider = managedProvider((exit, ignored) -> new SandboxManagedProcess() {
            private boolean readOnce = false;

            @Override
            public Instant startedAt() {
                return NOW;
            }

            @Override
            public void write(ProcessInputChunk input) {}

            @Override
            public Optional<io.haifa.agent.execution.api.ProcessOutputChunk> read(Duration timeout) {
                if (!readOnce) {
                    readOnce = true;
                    java.util.concurrent.CompletableFuture.delayedExecutor(
                                    20, java.util.concurrent.TimeUnit.MILLISECONDS)
                            .execute(() -> exit.complete(new io.haifa.agent.execution.api.ProcessExit(
                                    ExecutionStatus.SUCCEEDED, 0, true, NOW.plusSeconds(1))));
                    return Optional.of(new io.haifa.agent.execution.api.ProcessOutputChunk(
                            ExecutionOutputChannel.STDOUT,
                            "{\"result\":\"partial-sec".getBytes(StandardCharsets.UTF_8),
                            false,
                            false));
                }
                return Optional.empty();
            }

            @Override
            public java.util.concurrent.CompletableFuture<io.haifa.agent.execution.api.ProcessExit> exit() {
                return exit;
            }

            @Override
            public int observedProcessCount() {
                return 1;
            }

            @Override
            public boolean cancel() {
                return true;
            }

            @Override
            public void close() {}
        });

        ResolvedExecutionEnvironment env =
                ResolvedExecutionEnvironment.of(Map.of("LEAS_KEY", "secret-token"), Set.of("LEAS_KEY"));
        DefaultExecutionBroker broker = fixture.broker(provider, request -> {}, env);
        ExecutionRequest request =
                fixture.request("flush-exit", "flush-exit-key", Set.of("execution.run"), List.of("fake"));

        try (var session = broker.openManagedSession(new ManagedProcessRequest(request))) {
            var accumulated = new StringBuilder();
            while (!session.exit().isDone()) {
                session.read(Duration.ofMillis(50))
                        .ifPresent(chunk -> accumulated.append(new String(chunk.bytes(), StandardCharsets.UTF_8)));
            }
            while (true) {
                var chunk = session.read(Duration.ofMillis(10));
                if (chunk.isEmpty()) break;
                accumulated.append(new String(chunk.get().bytes(), StandardCharsets.UTF_8));
            }
            assertThat(accumulated.toString()).isEqualTo("{\"result\":\"partial-sec");
            assertThat(session.exit()
                            .get(2, java.util.concurrent.TimeUnit.SECONDS)
                            .status())
                    .isEqualTo(ExecutionStatus.SUCCEEDED);
        }

        var result = broker.find(request.id()).orElseThrow();
        assertThat(result.stdout().summary()).isEqualTo("{\"result\":\"partial-sec");
    }

    @Test
    void rejectsMissingProviderGuaranteesBeforeOpeningTheSandbox() {
        Fixture fixture = fixture();
        AtomicInteger opens = new AtomicInteger();
        SandboxProvider provider = new SandboxProvider() {
            @Override
            public String providerId() {
                return "insufficient";
            }

            @Override
            public SandboxCapabilities capabilities() {
                return new SandboxCapabilities(true, false, false, false, false);
            }

            @Override
            public SandboxSession open(SandboxProfile profile, WorkspaceMount mount) {
                opens.incrementAndGet();
                throw new AssertionError("open must not be called");
            }
        };
        SandboxProfile profile = new SandboxProfile(
                new SandboxProfileRef("test", "1"),
                provider.providerId(),
                provider.configurationDigest(),
                Set.of("fake"),
                Set.of("SECRET"),
                false,
                NetworkPolicy.ALLOW,
                new SandboxFilesystemPolicy(SandboxWorkspaceAccess.READ_WRITE, true, Set.of()),
                new SandboxCapabilities(true, true, false, false, false));
        DefaultExecutionBroker broker = fixture.broker(provider, request -> {}, profile);

        assertThatThrownBy(() -> broker.execute(fixture.request(
                        "capability-missing", "capability-key", Set.of("execution.run"), List.of("fake"))))
                .isInstanceOfSatisfying(SandboxException.class, exception -> assertThat(exception.code())
                        .isEqualTo("CAPABILITY_UNAVAILABLE"));
        assertThat(opens).hasValue(0);
    }

    private Fixture fixture() {
        WorkspaceId workspaceId = new WorkspaceId("workspace-1");
        WorkspaceBindingId bindingId = new WorkspaceBindingId("binding-1");
        WorkspaceLocationRef locationRef = new WorkspaceLocationRef("location-1");
        var workspaces = new InMemoryWorkspaceStore();
        var bindings = new InMemoryWorkspaceBindingStore();
        var locations = new HostWorkspaceLocationStore();
        locations.register(locationRef, root);
        WorkspaceBinding binding = WorkspaceBinding.provision(
                        bindingId,
                        locationRef,
                        WorkspaceBindingMode.DIRECT,
                        new PrincipalRef("owner", "user"),
                        WorkspaceCapabilitySet.executionFiles(),
                        WorkspacePermissionSet.readWriteExecute(),
                        HostWorkspaceLocationStore.fingerprintFor(root),
                        NOW)
                .activate(NOW);
        bindings.create(binding);
        Workspace workspace = Workspace.provision(
                        workspaceId,
                        new ProjectId("project-1"),
                        WorkspacePurpose.PRIMARY,
                        new WorkspaceRoot(ProjectPath.root(), bindingId, "test"),
                        WorkspaceRevision.initial(binding.rootFingerprint()),
                        NOW)
                .activate(NOW);
        workspaces.create(workspace);
        var fileService = new HostWorkspaceFileService(workspaces, bindings, locations, SensitivePathPolicy.defaults());
        var manifests = new WorkspaceManifestService(
                workspaces, fileService, new ManifestBudget(100, 1024 * 1024, 1024 * 1024), "test-v1");
        return new Fixture(workspaceId, root, workspaces, bindings, manifests, new InMemoryExecutionOutputStore());
    }

    private static SandboxProvider fakeProvider(Runnable effect, byte[] stdout) {
        return fakeProvider(effect, stdout, SandboxProcessStatus.EXITED);
    }

    private static SandboxProvider fakeProvider(Runnable effect, byte[] stdout, SandboxProcessStatus processStatus) {
        return new SandboxProvider() {
            @Override
            public String providerId() {
                return "fake";
            }

            @Override
            public SandboxCapabilities capabilities() {
                return new SandboxCapabilities(true, true, true, true, true);
            }

            @Override
            public SandboxSession open(SandboxProfile profile, WorkspaceMount mount) {
                return new SandboxSession() {
                    @Override
                    public SandboxSessionId id() {
                        return new SandboxSessionId("session-1");
                    }

                    @Override
                    public SandboxProcessResult execute(SandboxExecution execution) {
                        effect.run();
                        return new SandboxProcessResult(
                                processStatus,
                                processStatus == SandboxProcessStatus.EXITED ? 0 : null,
                                stdout,
                                new byte[0],
                                NOW,
                                NOW.plusSeconds(1),
                                false,
                                false,
                                true,
                                1);
                    }

                    @Override
                    public boolean cancel() {
                        return true;
                    }

                    @Override
                    public void close() {}
                };
            }
        };
    }

    private static SandboxProvider managedProvider() {
        return managedProvider((exit, ignored) -> {
            int[] emitCount = new int[] {0};
            return new SandboxManagedProcess() {
                @Override
                public Instant startedAt() {
                    return NOW;
                }

                @Override
                public void write(ProcessInputChunk input) {
                    assertThat(new String(input.bytes(), StandardCharsets.UTF_8))
                            .isEqualTo("request\n");
                }

                @Override
                public java.util.Optional<io.haifa.agent.execution.api.ProcessOutputChunk> read(Duration timeout) {
                    if (emitCount[0] == 0) {
                        emitCount[0]++;
                        return java.util.Optional.of(new io.haifa.agent.execution.api.ProcessOutputChunk(
                                io.haifa.agent.execution.api.ExecutionOutputChannel.STDOUT,
                                "https://user:remote-".getBytes(StandardCharsets.UTF_8),
                                false,
                                false));
                    } else if (emitCount[0] == 1) {
                        emitCount[0]++;
                        java.util.concurrent.CompletableFuture.delayedExecutor(
                                        20, java.util.concurrent.TimeUnit.MILLISECONDS)
                                .execute(() -> exit.complete(new io.haifa.agent.execution.api.ProcessExit(
                                        ExecutionStatus.SUCCEEDED, 0, true, NOW.plusSeconds(1))));
                        return java.util.Optional.of(new io.haifa.agent.execution.api.ProcessOutputChunk(
                                io.haifa.agent.execution.api.ExecutionOutputChannel.STDOUT,
                                "secret@github.example/repo.git\n".getBytes(StandardCharsets.UTF_8),
                                true,
                                false));
                    }
                    return java.util.Optional.empty();
                }

                @Override
                public java.util.concurrent.CompletableFuture<io.haifa.agent.execution.api.ProcessExit> exit() {
                    return exit;
                }

                @Override
                public int observedProcessCount() {
                    return 1;
                }

                @Override
                public boolean cancel() {
                    return true;
                }

                @Override
                public void close() {}
            };
        });
    }

    private static SandboxProvider managedProvider(
            java.util.function.BiFunction<
                            java.util.concurrent.CompletableFuture<io.haifa.agent.execution.api.ProcessExit>,
                            Runnable,
                            SandboxManagedProcess>
                    factory) {
        return new SandboxProvider() {
            @Override
            public String providerId() {
                return "managed-fake";
            }

            @Override
            public SandboxCapabilities capabilities() {
                return new SandboxCapabilities(true, true, true, true, true);
            }

            @Override
            public boolean supportsManagedProcess() {
                return true;
            }

            @Override
            public SandboxSession open(SandboxProfile profile, WorkspaceMount mount) {
                return new SandboxSession() {
                    private final java.util.concurrent.CompletableFuture<io.haifa.agent.execution.api.ProcessExit>
                            exit = new java.util.concurrent.CompletableFuture<>();

                    @Override
                    public SandboxSessionId id() {
                        return new SandboxSessionId("managed-session");
                    }

                    @Override
                    public SandboxProcessResult execute(SandboxExecution execution) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public SandboxManagedProcess openManagedProcess(SandboxExecution execution) {
                        return factory.apply(exit, () -> {});
                    }

                    @Override
                    public boolean cancel() {
                        return true;
                    }

                    @Override
                    public void close() {}
                };
            }
        };
    }

    private record Fixture(
            WorkspaceId workspaceId,
            Path root,
            InMemoryWorkspaceStore workspaces,
            InMemoryWorkspaceBindingStore bindings,
            WorkspaceManifestService manifests,
            InMemoryExecutionOutputStore outputs) {
        DefaultExecutionBroker broker(SandboxProvider provider, ExecutionPolicy policy) {
            return broker(provider, policy, profile(provider));
        }

        SandboxProfile profile(SandboxProvider provider) {
            return new SandboxProfile(
                    new SandboxProfileRef("test", "1"),
                    provider.providerId(),
                    provider.configurationDigest(),
                    Set.of("fake"),
                    Set.of("SECRET"),
                    false,
                    NetworkPolicy.ALLOW,
                    io.haifa.agent.sandbox.api.SandboxFilesystemPolicy.hostCompatible(),
                    new SandboxCapabilities(true, false, false, false, false));
        }

        DefaultExecutionBroker broker(SandboxProvider provider, ExecutionPolicy policy, SandboxProfile profile) {
            return broker(
                    provider,
                    policy,
                    profile,
                    ResolvedExecutionEnvironment.of(Map.of("SECRET", "secret-token"), Set.of("SECRET")));
        }

        DefaultExecutionBroker broker(
                SandboxProvider provider, ExecutionPolicy policy, Map<String, String> environment) {
            return broker(provider, policy, profile(provider), ResolvedExecutionEnvironment.of(environment));
        }

        DefaultExecutionBroker broker(
                SandboxProvider provider, ExecutionPolicy policy, ResolvedExecutionEnvironment environment) {
            return broker(provider, policy, profile(provider), environment);
        }

        DefaultExecutionBroker broker(
                SandboxProvider provider,
                ExecutionPolicy policy,
                SandboxProfile profile,
                ResolvedExecutionEnvironment environment) {
            return new DefaultExecutionBroker(
                    new InMemoryExecutionStore(),
                    outputs,
                    ignored -> environment,
                    policy,
                    ignored -> profile,
                    ignored -> provider,
                    workspaces,
                    bindings,
                    new LocalIncrementalWorkspaceChangeObserver(workspaceId, root, WorkspaceChangeIgnorePolicy.none()));
        }

        DefaultExecutionBroker broker(
                SandboxProvider provider,
                ExecutionPolicy policy,
                SandboxProfile profile,
                WorkspaceChangeObserver workspaceChanges) {
            return new DefaultExecutionBroker(
                    new InMemoryExecutionStore(),
                    outputs,
                    ignored -> ResolvedExecutionEnvironment.of(Map.of("SECRET", "secret-token"), Set.of("SECRET")),
                    policy,
                    ignored -> profile,
                    ignored -> provider,
                    workspaces,
                    bindings,
                    workspaceChanges);
        }

        ExecutionRequest request(String id, String key, Set<String> capabilities, List<String> argv) {
            return new ExecutionRequest(
                    new ExecutionId(id),
                    key,
                    new TrustedExecutionContext("run-1", new PrincipalRef("actor", "user"), capabilities, "allow-1"),
                    workspaceId,
                    WorkspacePath.root(workspaceId),
                    new ExecutionCommand(ExecutionCommandMode.DIRECT, argv),
                    new ExecutionEnvironmentRef(List.of("lease-1")),
                    new ExecutionLimits(Duration.ofSeconds(5), 8192, 8192, 2),
                    new SandboxProfileRef("test", "1"));
        }
    }
}
