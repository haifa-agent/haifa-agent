package io.haifa.agent.execution.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.execution.api.ExecutionCommand;
import io.haifa.agent.execution.api.ExecutionCommandMode;
import io.haifa.agent.execution.api.ExecutionEnvironmentRef;
import io.haifa.agent.execution.api.ExecutionId;
import io.haifa.agent.execution.api.ExecutionLimits;
import io.haifa.agent.execution.api.ExecutionRequest;
import io.haifa.agent.execution.api.ExecutionStatus;
import io.haifa.agent.execution.api.ManagedProcessRequest;
import io.haifa.agent.execution.api.ProcessInputChunk;
import io.haifa.agent.execution.api.SandboxProfileRef;
import io.haifa.agent.execution.api.TrustedExecutionContext;
import io.haifa.agent.execution.core.manifest.ManifestBudget;
import io.haifa.agent.execution.core.manifest.ManifestDiffService;
import io.haifa.agent.execution.core.manifest.WorkspaceManifestService;
import io.haifa.agent.execution.core.store.InMemoryExecutionOutputStore;
import io.haifa.agent.execution.core.store.InMemoryExecutionStore;
import io.haifa.agent.project.binding.WorkspaceBinding;
import io.haifa.agent.project.binding.WorkspaceBindingId;
import io.haifa.agent.project.binding.WorkspaceBindingMode;
import io.haifa.agent.project.binding.WorkspaceLocationRef;
import io.haifa.agent.project.changeset.FileChangeSetService;
import io.haifa.agent.project.changeset.InMemoryFileChangeSetStore;
import io.haifa.agent.project.changeset.ObservedFileChangeService;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.path.WorkspacePath;
import io.haifa.agent.project.provider.local.LocalWorkspaceFileService;
import io.haifa.agent.project.provider.local.LocalWorkspaceLocationStore;
import io.haifa.agent.project.provider.local.SensitivePathPolicy;
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
import io.haifa.agent.sandbox.api.SandboxExecution;
import io.haifa.agent.sandbox.api.SandboxManagedProcess;
import io.haifa.agent.sandbox.api.SandboxProcessResult;
import io.haifa.agent.sandbox.api.SandboxProcessStatus;
import io.haifa.agent.sandbox.api.SandboxProfile;
import io.haifa.agent.sandbox.api.SandboxProvider;
import io.haifa.agent.sandbox.api.SandboxSession;
import io.haifa.agent.sandbox.api.SandboxSessionId;
import io.haifa.agent.sandbox.api.WorkspaceMount;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
                ("secret-token\n" + "x".repeat(5000)).getBytes(StandardCharsets.UTF_8));
        DefaultExecutionBroker broker = fixture.broker(provider, request -> policyCalls.incrementAndGet());
        ExecutionRequest request = fixture.request("execution-1", "key-1", Set.of("execution.run"), List.of("fake"));

        var result = broker.execute(request);

        assertThat(result.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(result.optionalFileChangeSetId()).isPresent();
        assertThat(result.stdout().summary()).doesNotContain("secret-token");
        assertThat(result.stdout().optionalAssetRef()).isPresent();
        byte[] stored = fixture.outputs.load(result.stdout().assetRef()).orElseThrow();
        assertThat(new String(stored, StandardCharsets.UTF_8))
                .doesNotContain("secret-token")
                .contains("***");
        assertThat(fixture.changeSets
                        .find(result.fileChangeSetId())
                        .orElseThrow()
                        .changes())
                .extracting(change -> change.path().value())
                .contains("created.txt");
        assertThat(broker.execute(request).replayed()).isTrue();
        assertThat(policyCalls).hasValue(1);

        assertThatThrownBy(() -> broker.execute(
                        fixture.request("execution-2", "key-1", Set.of("execution.run"), List.of("different"))))
                .isInstanceOf(ExecutionRejectedException.class);
        assertThatThrownBy(() -> broker.execute(fixture.request("execution-3", "key-3", Set.of(), List.of("fake"))))
                .isInstanceOfSatisfying(ExecutionRejectedException.class, exception -> assertThat(exception.code())
                        .isEqualTo("CAPABILITY_DENIED"));
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
            var output = session.read(Duration.ofSeconds(1)).orElseThrow();
            assertThat(new String(output.bytes(), StandardCharsets.UTF_8)).isEqualTo("***\n");
            assertThat(session.exit()
                            .get(2, java.util.concurrent.TimeUnit.SECONDS)
                            .status())
                    .isEqualTo(ExecutionStatus.SUCCEEDED);
        }

        var result = broker.find(request.id()).orElseThrow();
        assertThat(result.status()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(result.stdout().summary()).doesNotContain("secret-token");
    }

    private Fixture fixture() {
        WorkspaceId workspaceId = new WorkspaceId("workspace-1");
        WorkspaceBindingId bindingId = new WorkspaceBindingId("binding-1");
        WorkspaceLocationRef locationRef = new WorkspaceLocationRef("location-1");
        var workspaces = new InMemoryWorkspaceStore();
        var bindings = new InMemoryWorkspaceBindingStore();
        var locations = new LocalWorkspaceLocationStore();
        locations.register(locationRef, root);
        WorkspaceBinding binding = WorkspaceBinding.provision(
                        bindingId,
                        locationRef,
                        WorkspaceBindingMode.DIRECT,
                        new PrincipalRef("owner", "user"),
                        WorkspaceCapabilitySet.executionFiles(),
                        WorkspacePermissionSet.readWriteExecute(),
                        LocalWorkspaceLocationStore.fingerprintFor(root),
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
        var fileService =
                new LocalWorkspaceFileService(workspaces, bindings, locations, SensitivePathPolicy.defaults());
        var manifests = new WorkspaceManifestService(
                workspaces, fileService, new ManifestBudget(100, 1024 * 1024, 1024 * 1024), "test-v1");
        var changeSets = new InMemoryFileChangeSetStore();
        var ids = new AtomicInteger();
        var changeSetService = new FileChangeSetService(changeSets, () -> "change-" + ids.incrementAndGet(), () -> NOW);
        var observed = new ObservedFileChangeService(workspaces, changeSets, changeSetService, () -> NOW);
        return new Fixture(
                workspaceId, workspaces, bindings, manifests, changeSets, observed, new InMemoryExecutionOutputStore());
    }

    private static SandboxProvider fakeProvider(Runnable effect, byte[] stdout) {
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
                                SandboxProcessStatus.EXITED,
                                0,
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
            public SandboxSession open(SandboxProfile profile, WorkspaceMount mount) {
                return new SandboxSession() {
                    private final java.util.concurrent.CompletableFuture<io.haifa.agent.execution.api.ProcessExit>
                            exit = new java.util.concurrent.CompletableFuture<>();
                    private boolean emitted;

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
                            public java.util.Optional<io.haifa.agent.execution.api.ProcessOutputChunk> read(
                                    Duration timeout) {
                                if (emitted) return java.util.Optional.empty();
                                emitted = true;
                                var output = new io.haifa.agent.execution.api.ProcessOutputChunk(
                                        io.haifa.agent.execution.api.ExecutionOutputChannel.STDOUT,
                                        "secret-token\n".getBytes(StandardCharsets.UTF_8),
                                        false,
                                        false);
                                java.util.concurrent.CompletableFuture.delayedExecutor(
                                                20, java.util.concurrent.TimeUnit.MILLISECONDS)
                                        .execute(() -> exit.complete(new io.haifa.agent.execution.api.ProcessExit(
                                                ExecutionStatus.SUCCEEDED, 0, true, NOW.plusSeconds(1))));
                                return java.util.Optional.of(output);
                            }

                            @Override
                            public java.util.concurrent.CompletableFuture<io.haifa.agent.execution.api.ProcessExit>
                                    exit() {
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
            InMemoryWorkspaceStore workspaces,
            InMemoryWorkspaceBindingStore bindings,
            WorkspaceManifestService manifests,
            InMemoryFileChangeSetStore changeSets,
            ObservedFileChangeService observed,
            InMemoryExecutionOutputStore outputs) {
        DefaultExecutionBroker broker(SandboxProvider provider, ExecutionPolicy policy) {
            SandboxProfile profile = new SandboxProfile(
                    new SandboxProfileRef("test", "1"), Set.of("fake"), Set.of("SECRET"), false, NetworkPolicy.ALLOW);
            return new DefaultExecutionBroker(
                    new InMemoryExecutionStore(),
                    outputs,
                    ignored -> Map.of("SECRET", "secret-token"),
                    policy,
                    ignored -> profile,
                    ignored -> provider,
                    workspaces,
                    bindings,
                    manifests,
                    new ManifestDiffService(),
                    observed);
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
