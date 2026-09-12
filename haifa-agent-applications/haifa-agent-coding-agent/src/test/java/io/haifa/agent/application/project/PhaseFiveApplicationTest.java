package io.haifa.agent.application.project;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.application.project.admin.ArtifactView;
import io.haifa.agent.application.project.admin.WorkspaceAdminView;
import io.haifa.agent.application.project.admin.WorkspaceBindingView;
import io.haifa.agent.application.project.admin.WorkspaceSnapshotView;
import io.haifa.agent.application.project.artifact.ArtifactExportRequest;
import io.haifa.agent.application.project.artifact.ArtifactExportService;
import io.haifa.agent.application.project.artifact.ArtifactExportSourceKind;
import io.haifa.agent.application.project.artifact.PublishedArtifactRequiredChecker;
import io.haifa.agent.artifact.ArtifactId;
import io.haifa.agent.artifact.ArtifactService;
import io.haifa.agent.artifact.ArtifactType;
import io.haifa.agent.artifact.ArtifactVersion;
import io.haifa.agent.artifact.InMemoryArtifactPayloadStore;
import io.haifa.agent.artifact.InMemoryArtifactStore;
import io.haifa.agent.core.reference.ArtifactRef;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.ProjectRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunOutcome;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.project.binding.WorkspaceBinding;
import io.haifa.agent.project.binding.WorkspaceBindingId;
import io.haifa.agent.project.binding.WorkspaceBindingMode;
import io.haifa.agent.project.binding.WorkspaceLocationRef;
import io.haifa.agent.project.core.store.InMemoryWorkspaceBindingStore;
import io.haifa.agent.project.core.store.InMemoryWorkspaceStore;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.filesystem.FileContent;
import io.haifa.agent.project.filesystem.FileListPage;
import io.haifa.agent.project.filesystem.FileListRequest;
import io.haifa.agent.project.filesystem.FileMetadata;
import io.haifa.agent.project.filesystem.SearchRequest;
import io.haifa.agent.project.filesystem.SearchResult;
import io.haifa.agent.project.filesystem.WorkspaceFileService;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.workspace.Workspace;
import io.haifa.agent.project.workspace.WorkspaceCapabilitySet;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.project.workspace.WorkspacePermissionSet;
import io.haifa.agent.project.workspace.WorkspacePurpose;
import io.haifa.agent.project.workspace.WorkspaceRevision;
import io.haifa.agent.project.workspace.WorkspaceRoot;
import io.haifa.agent.runtime.core.decision.FinalAnswerDecision;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PhaseFiveApplicationTest {
    private static final Instant NOW = Instant.parse("2026-07-21T00:00:00Z");

    @Test
    void explicitPatchExportIsImmutableAndRequiredCheckerUsesPublishedState() throws Exception {
        var workspaceId = new WorkspaceId("workspace-1");
        var revision = WorkspaceRevision.initial("sha256:workspace");
        var workspaces = new InMemoryWorkspaceStore();
        workspaces.create(Workspace.provision(
                        workspaceId,
                        new ProjectId("project-1"),
                        WorkspacePurpose.PRIMARY,
                        new WorkspaceRoot(ProjectPath.root(), new WorkspaceBindingId("binding-1"), "test"),
                        revision,
                        NOW)
                .activate(NOW));
        var artifactStore = new InMemoryArtifactStore();
        var payloadStore = new InMemoryArtifactPayloadStore();
        var ids = new AtomicInteger();
        var service = new ArtifactExportService(
                workspaces,
                new UnusedFileService(),
                new ArtifactService(artifactStore, payloadStore, () -> "artifact-" + ids.incrementAndGet(), () -> NOW),
                request -> request.actor().principalId().equals("author-1"));
        byte[] patch = "diff --git a/a b/a".getBytes(StandardCharsets.UTF_8);
        String hash = "sha256:"
                + java.util.HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(patch));
        var request = new ArtifactExportRequest(
                ArtifactExportSourceKind.PATCH,
                new ProjectRef("project-1"),
                workspaceId,
                ProjectPath.of("review/change.patch"),
                revision,
                hash,
                "change-1",
                "execution-1",
                patch,
                new ArtifactType("patch"),
                "Review patch",
                "text/x-diff",
                "review-v1",
                new AgentRunId("run-1"),
                new AgentSessionId("session-1"),
                new PrincipalRef("author-1", "user"),
                1024);
        patch[0] = 'X';

        var result = service.export(request);
        var published = artifactStore
                .find(new ArtifactId(result.artifact().artifactId()), new ArtifactVersion(1))
                .orElseThrow();
        assertThat(payloadStore.load(published.payload()).orElseThrow())
                .startsWith("diff --git".getBytes(StandardCharsets.UTF_8));

        var checker = new PublishedArtifactRequiredChecker(artifactStore);
        assertThat(checker.evaluate(null, decision(List.of(result.artifact()))).blockers())
                .isEmpty();
        for (var invalid : List.of(
                new ArtifactRef("missing", "patch", "1", "missing"),
                new ArtifactRef(
                        result.artifact().artifactId(),
                        "patch",
                        "invalid",
                        result.artifact().title()),
                new ArtifactRef(
                        result.artifact().artifactId(),
                        "wrong-type",
                        "1",
                        result.artifact().title()),
                new ArtifactRef(
                        result.artifact().artifactId(), result.artifact().artifactType(), "1", "wrong-title"))) {
            assertThat(checker.evaluate(null, decision(List.of(invalid))).blockers())
                    .containsExactly(io.haifa.agent.runtime.core.completion.CompletionBlocker.recoverable(
                            "REQUIRED_ARTIFACT_MISSING", "A required artifact is missing.", "REQUIRED_ARTIFACT"));
        }
    }

    @Test
    void adminViewsCannotExposeHostPathTypes() {
        for (Class<?> view : List.of(
                WorkspaceAdminView.class,
                WorkspaceBindingView.class,
                WorkspaceSnapshotView.class,
                ArtifactView.class)) {
            assertThat(view.getRecordComponents())
                    .extracting(RecordComponent::getType)
                    .noneMatch(type -> type.equals(java.nio.file.Path.class) || type.equals(byte[].class));
        }
    }

    @Test
    void executionToolBrokerConfigurationMismatchFailsClosedSpanningPipelineAndDecisionExecutor() {
        var workspaceId = new WorkspaceId("workspace-e2e");
        var bindingId = new WorkspaceBindingId("binding-e2e");
        var workspaces = new InMemoryWorkspaceStore();
        var bindings = new InMemoryWorkspaceBindingStore();
        var owner = new PrincipalRef("test-user", "user");
        var binding = WorkspaceBinding.provision(
                        bindingId,
                        new WorkspaceLocationRef("location-1"),
                        WorkspaceBindingMode.DIRECT,
                        owner,
                        WorkspaceCapabilitySet.executionFiles(),
                        WorkspacePermissionSet.readWriteExecute(),
                        "root-1",
                        NOW)
                .activate(NOW);
        bindings.create(binding);
        var workspace = Workspace.provision(
                        workspaceId,
                        new ProjectId("project-1"),
                        WorkspacePurpose.PRIMARY,
                        new WorkspaceRoot(ProjectPath.root(), bindingId, "test"),
                        WorkspaceRevision.initial("rev-1"),
                        NOW)
                .activate(NOW);
        workspaces.create(workspace);

        var requestedProfileRef = new io.haifa.agent.execution.api.SandboxProfileRef("profile-req", "1");
        var actualProfileRef = new io.haifa.agent.execution.api.SandboxProfileRef("profile-actual", "1");
        var actualProfile = new io.haifa.agent.sandbox.api.SandboxProfile(
                actualProfileRef,
                "provider-1",
                io.haifa.agent.sandbox.api.SandboxConfigurationDigest.sha256Fields(List.of("provider-1")),
                java.util.Set.of(),
                java.util.Set.of(),
                false);
        io.haifa.agent.sandbox.api.SandboxProvider sandboxProvider = new io.haifa.agent.sandbox.api.SandboxProvider() {
            @Override
            public String providerId() {
                return "provider-1";
            }

            @Override
            public io.haifa.agent.sandbox.api.SandboxCapabilities capabilities() {
                return new io.haifa.agent.sandbox.api.SandboxCapabilities(true);
            }

            @Override
            public io.haifa.agent.sandbox.api.SandboxSession open(
                    io.haifa.agent.sandbox.api.SandboxProfile profile,
                    io.haifa.agent.sandbox.api.WorkspaceMount mount) {
                throw new UnsupportedOperationException();
            }
        };

        var broker = new io.haifa.agent.execution.core.DefaultExecutionBroker(
                new io.haifa.agent.execution.core.store.InMemoryExecutionStore(),
                new io.haifa.agent.execution.core.store.InMemoryExecutionOutputStore(),
                ignored -> io.haifa.agent.execution.api.ResolvedExecutionEnvironment.of(Map.of()),
                (req, entry) -> {},
                ref -> actualProfile,
                prof -> sandboxProvider,
                workspaces,
                bindings);

        var config = new io.haifa.agent.execution.core.tool.ExecutionToolConfiguration(
                io.haifa.agent.execution.api.ExecutionEnvironmentRef.empty(),
                requestedProfileRef,
                java.time.Duration.ofSeconds(5),
                java.time.Duration.ofSeconds(10),
                4096,
                100,
                1,
                false,
                new io.haifa.agent.execution.core.tool.ScriptRuntimeResolver(
                        io.haifa.agent.execution.core.tool.ExecutionOperatingSystem.WINDOWS, List.of()),
                ignored -> {},
                value -> value);

        var executionToolProvider = new io.haifa.agent.execution.core.tool.ExecutionToolProvider(
                broker,
                () -> "exec-1",
                () -> NOW,
                ignored ->
                        new io.haifa.agent.execution.core.tool.ExecutionInvocationScopeResolver
                                .ExecutionInvocationScope(workspaceId, java.util.Set.of("execution_run")),
                config,
                io.haifa.agent.execution.core.tool.TrustedWorkspacePathValidator.rejectWorkspaceInputs());

        var definition = io.haifa.agent.execution.core.tool.ExecutionToolDefinitionFactory.create(
                executionToolProvider.sandboxProfileIdentity(),
                executionToolProvider.configurationIdentity(),
                executionToolProvider.scratchSpecDigest(),
                false,
                false,
                java.util.Set.of());

        var catalog = new io.haifa.agent.tool.core.ToolCatalogBuilder()
                .register(
                        new io.haifa.agent.tool.api.ToolAlias("execution_run"),
                        definition,
                        "execution",
                        executionToolProvider)
                .freeze();

        io.haifa.agent.model.api.AgentChatModel model = req -> new io.haifa.agent.model.api.AgentChatResponse(
                "resp-1",
                "test-model",
                "",
                List.of(new io.haifa.agent.model.api.ModelToolCall(
                        new io.haifa.agent.core.tool.ProviderToolCallCorrelationId("provider-call-1"),
                        "execution_run",
                        Map.of("mode", "COMMAND", "content", "echo test", "purpose", "test fail-closed path"))),
                io.haifa.agent.model.api.ModelFinishReason.TOOL_CALLS,
                io.haifa.agent.model.api.ModelUsage.unpriced(1, 1),
                "",
                Map.of());

        var scheduler = new io.haifa.agent.runtime.core.execution.ManualExecutionScheduler();
        var store = new io.haifa.agent.runtime.core.storage.InMemoryRuntimeStore();
        var interactions = new io.haifa.agent.runtime.core.interaction.InMemoryInteractionPort();
        var runInputs = new io.haifa.agent.runtime.core.input.InMemoryRunInputPort();
        var journal = new io.haifa.agent.runtime.core.tool.InMemoryToolExecutionJournal();
        var identifiers = new AtomicInteger();

        var runtime = new io.haifa.agent.runtime.core.RuntimeCoreBuilder()
                .registerChatModel("openai-compatible", "1.0.0", model)
                .scheduler(scheduler)
                .persistence(io.haifa.agent.runtime.core.storage.RuntimePersistencePorts.inMemory(
                        store, journal, interactions))
                .runInputs(runInputs)
                .identifierGenerator(() -> "phase-five-e2e-" + identifiers.incrementAndGet())
                .timeProvider(() -> NOW)
                .publicToolPolicy((run, bind, req) -> new io.haifa.agent.policy.api.PolicyDecision(
                        io.haifa.agent.policy.api.PolicyEffect.ALLOW,
                        java.util.Optional.empty(),
                        "ALLOW",
                        "Allowed",
                        "sha256:test-allow"))
                .toolPlatform(
                        catalog,
                        new io.haifa.agent.tool.core.DefaultToolInvoker(catalog),
                        new io.haifa.agent.tool.core.JsonSchema202012Validator())
                .build();

        var runRequest = new io.haifa.agent.runtime.api.AgentRunRequest(
                "run-e2e-key",
                new io.haifa.agent.core.agent.AgentDefinitionId("coding-agent"),
                java.util.Optional.empty(),
                "coding-profile",
                new io.haifa.agent.core.session.AgentSessionId("session-e2e"),
                java.util.Optional.empty(),
                "execute command",
                List.of(),
                io.haifa.agent.runtime.api.RuntimeOverrides.NONE);

        var handle = runtime.start(runRequest);
        scheduler.runAll();

        var finalRun = runtime.find(handle.runId()).orElseThrow();
        assertThat(finalRun.status()).isEqualTo(io.haifa.agent.core.run.AgentRunStatus.FAILED);
        var toolCalls = store.toolCalls(handle.runId());
        assertThat(toolCalls).hasSize(1);
        var failedCall = toolCalls.getFirst();
        assertThat(failedCall.status()).isEqualTo(io.haifa.agent.core.tool.ToolCallStatus.FAILED);
        assertThat(failedCall.error().orElseThrow().error().code())
                .isEqualTo(io.haifa.agent.core.error.AgentErrorCode.TOOL_INVOCATION_FAILED);
        assertThat(failedCall.error().orElseThrow().error().details())
                .doesNotContainKeys("preflight", "failureKind", "failureCode", "dispatchState");
    }

    private static FinalAnswerDecision decision(List<ArtifactRef> artifacts) {
        return new FinalAnswerDecision(AgentRunOutcome.SUCCESS, "done", "result", "1", Map.of(), artifacts, List.of());
    }

    private static final class UnusedFileService implements WorkspaceFileService {
        @Override
        public FileListPage list(FileListRequest request) {
            throw new AssertionError("not used");
        }

        @Override
        public FileMetadata stat(io.haifa.agent.project.path.WorkspacePath path, boolean includeHash) {
            throw new AssertionError("not used");
        }

        @Override
        public FileContent read(
                io.haifa.agent.project.path.WorkspacePath path, io.haifa.agent.project.filesystem.ReadOptions options) {
            throw new AssertionError("not used");
        }

        @Override
        public List<SearchResult> search(SearchRequest request) {
            throw new AssertionError("not used");
        }
    }
}
