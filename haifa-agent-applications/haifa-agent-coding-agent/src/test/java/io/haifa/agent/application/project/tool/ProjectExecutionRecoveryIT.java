package io.haifa.agent.application.project.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.application.project.persistence.ProjectPersistenceAssembly;
import io.haifa.agent.application.project.persistence.ProjectPersistenceConfiguration;
import io.haifa.agent.application.project.policy.CodingAgentExecutionPolicy;
import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.session.AgentSession;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.session.SessionScope;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.ToolCallStatus;
import io.haifa.agent.execution.api.ExecutionBroker;
import io.haifa.agent.execution.api.ExecutionEnvironmentRef;
import io.haifa.agent.execution.api.ExecutionId;
import io.haifa.agent.execution.api.ExecutionOutput;
import io.haifa.agent.execution.api.ExecutionOutputObserver;
import io.haifa.agent.execution.api.ExecutionPreflightException;
import io.haifa.agent.execution.api.ExecutionRequest;
import io.haifa.agent.execution.api.ExecutionResult;
import io.haifa.agent.execution.api.ExecutionScratchSpaceSpec;
import io.haifa.agent.execution.api.ExecutionStatus;
import io.haifa.agent.execution.api.ResourceUsageSummary;
import io.haifa.agent.execution.api.SandboxProfileRef;
import io.haifa.agent.execution.core.ExecutionPolicyEntryPoint;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.policy.api.PolicyDecision;
import io.haifa.agent.policy.api.PolicyEffect;
import io.haifa.agent.project.core.store.InMemoryProjectStore;
import io.haifa.agent.project.core.store.InMemoryWorkspaceStore;
import io.haifa.agent.project.core.workspace.WorkspaceService;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.hostworkspace.HostWorkspaceLocationStore;
import io.haifa.agent.project.hostworkspace.directory.InMemoryAuthorizedDirectoryStore;
import io.haifa.agent.project.hostworkspace.scope.AuthorizedHostDirectory;
import io.haifa.agent.project.hostworkspace.scope.AuthorizedWorkspaceProvisioning;
import io.haifa.agent.project.hostworkspace.scope.HostWorkspaceScope;
import io.haifa.agent.project.workspace.Workspace;
import io.haifa.agent.project.workspace.WorkspaceAccessMode;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.project.workspace.WorkspaceRevision;
import io.haifa.agent.runtime.api.AgentRunRequest;
import io.haifa.agent.runtime.api.RuntimeOverrides;
import io.haifa.agent.runtime.core.DefaultAgentRuntime;
import io.haifa.agent.runtime.core.RuntimeCoreBuilder;
import io.haifa.agent.runtime.core.execution.ManualExecutionScheduler;
import io.haifa.agent.runtime.core.interaction.InteractionPort;
import io.haifa.agent.runtime.core.model.continuation.AesGcmModelContinuationProtector;
import io.haifa.agent.runtime.core.storage.RuntimePersistencePorts;
import io.haifa.agent.runtime.core.tool.PublicToolPolicy;
import io.haifa.agent.runtime.core.tool.RuntimeToolExecutionVerifier;
import io.haifa.agent.sandbox.api.SandboxConfigurationDigest;
import io.haifa.agent.sandbox.api.SandboxProfile;
import io.haifa.agent.tool.core.DefaultToolInvoker;
import io.haifa.agent.tool.core.JsonSchema202012Validator;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("slow")
class ProjectExecutionRecoveryIT {
    private static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");
    private static final TimeProvider TIME = () -> NOW;
    private static final TenantRef TENANT = new TenantRef("local");
    private static final PrincipalRef PRINCIPAL = new PrincipalRef("local-user", "user");
    private static final AgentSessionId SESSION = new AgentSessionId("execution-recovery-session");
    private static final WorkspaceId WORKSPACE = new WorkspaceId("workspace-1");
    private static final byte[] PROTECTOR_KEY = new byte[32];

    @Test
    void notDispatchedPreflightFailureIsAnOrdinaryFailedResultThatContinuesWithoutRecoveryInteraction(
            @TempDir Path directory) throws Exception {
        AtomicInteger brokerCalls = new AtomicInteger();
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger ids = new AtomicInteger();
        List<SandboxProfileRef> observedProfiles = new ArrayList<>();
        IdentifierGenerator identifiers = () -> "m4-id-" + ids.incrementAndGet();
        ExecutionBroker broker = broker(brokerCalls, observedProfiles);
        Queue<AgentChatResponse> responses = new ArrayDeque<>(List.of(
                new AgentChatResponse(
                        "m4-model-tool",
                        "test-model",
                        "",
                        List.of(new ModelToolCall(
                                new ProviderToolCallCorrelationId("m4-provider-call-1"),
                                "execution_run",
                                Map.of(
                                        "command", "git fetch origin",
                                        "workspaceRef", WORKSPACE.value(),
                                        "relativeWorkdir", "."))),
                        ModelFinishReason.TOOL_CALLS,
                        ModelUsage.unpriced(1, 1),
                        "",
                        Map.of()),
                new AgentChatResponse(
                        "m4-model-final",
                        "test-model",
                        "network unavailable, reported blocker",
                        List.of(),
                        ModelFinishReason.STOP,
                        ModelUsage.unpriced(1, 1),
                        "",
                        Map.of())));
        AgentChatModel model = request -> {
            modelCalls.incrementAndGet();
            return responses.remove();
        };
        Path database = directory.resolve("runtime.db").toAbsolutePath();

        try (ProjectPersistenceAssembly persistence = persistence(database)) {
            RuntimeInstance instance = runtime(persistence, model, broker, identifiers, "m4-worker");
            AgentRunId runId = instance.runtime().start(request()).runId();
            instance.scheduler().runAll();

            assertThat(instance.runtime().find(runId).orElseThrow().status()).isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(instance.ports().interactions().pending(runId)).isEmpty();

            var toolCalls = instance.ports().state().toolCalls(runId);
            assertThat(toolCalls).hasSize(1);
            var failedCall = toolCalls.getFirst();
            assertThat(failedCall.status()).isEqualTo(ToolCallStatus.FAILED);
            assertThat(failedCall.error()).isPresent();
            assertThat(failedCall.error().orElseThrow().error().details())
                    .containsEntry("stableFailureCode", "NETWORK_UNAVAILABLE");

            assertThat(modelCalls).hasValue(2);
            assertThat(brokerCalls).hasValue(1);
            assertLegacyTablesAbsent(database);
        }
    }

    @Test
    void notDispatchedFailureAllowsModelToIssueOrdinaryNewToolCallUnderStandardPolicy(@TempDir Path directory)
            throws Exception {
        AtomicInteger brokerCalls = new AtomicInteger();
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger ids = new AtomicInteger();
        List<SandboxProfileRef> observedProfiles = new ArrayList<>();
        IdentifierGenerator identifiers = () -> String.format("m4-retry-id-%04d", ids.incrementAndGet());
        ExecutionBroker broker = broker(brokerCalls, observedProfiles);
        Queue<AgentChatResponse> responses = new ArrayDeque<>(List.of(
                new AgentChatResponse(
                        "m4-model-tool-1",
                        "test-model",
                        "",
                        List.of(new ModelToolCall(
                                new ProviderToolCallCorrelationId("m4-provider-call-1"),
                                "execution_run",
                                Map.of(
                                        "command", "git fetch origin",
                                        "workspaceRef", WORKSPACE.value(),
                                        "relativeWorkdir", "."))),
                        ModelFinishReason.TOOL_CALLS,
                        ModelUsage.unpriced(1, 1),
                        "",
                        Map.of()),
                new AgentChatResponse(
                        "m4-model-tool-2",
                        "test-model",
                        "",
                        List.of(new ModelToolCall(
                                new ProviderToolCallCorrelationId("m4-provider-call-2"),
                                "execution_run",
                                Map.of(
                                        "command", "git status",
                                        "workspaceRef", WORKSPACE.value(),
                                        "relativeWorkdir", "."))),
                        ModelFinishReason.TOOL_CALLS,
                        ModelUsage.unpriced(1, 1),
                        "",
                        Map.of()),
                new AgentChatResponse(
                        "m4-model-final",
                        "test-model",
                        "finished after ordinary status check",
                        List.of(),
                        ModelFinishReason.STOP,
                        ModelUsage.unpriced(1, 1),
                        "",
                        Map.of())));
        AgentChatModel model = request -> {
            modelCalls.incrementAndGet();
            return responses.remove();
        };
        Path database = directory.resolve("runtime.db").toAbsolutePath();

        try (ProjectPersistenceAssembly persistence = persistence(database)) {
            RuntimeInstance instance = runtime(persistence, model, broker, identifiers, "m4-worker");
            AgentRunId runId = instance.runtime().start(request()).runId();
            instance.scheduler().runAll();

            assertThat(instance.runtime().find(runId).orElseThrow().status()).isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(instance.ports().interactions().pending(runId)).isEmpty();

            var toolCalls = instance.ports().state().toolCalls(runId);
            assertThat(toolCalls).hasSize(2);
            var failedCall = toolCalls.stream()
                    .filter(tc -> tc.status() == ToolCallStatus.FAILED)
                    .findFirst()
                    .orElseThrow();
            var completedCall = toolCalls.stream()
                    .filter(tc -> tc.status() == ToolCallStatus.COMPLETED)
                    .findFirst()
                    .orElseThrow();
            assertThat(failedCall.error().orElseThrow().error().details())
                    .containsEntry("stableFailureCode", "NETWORK_UNAVAILABLE");
            assertThat(completedCall.status()).isEqualTo(ToolCallStatus.COMPLETED);
            assertThat(completedCall.result().orElseThrow().structuredData())
                    .containsEntry("processState", "EXITED")
                    .containsEntry("exitCode", 128);

            assertThat(modelCalls).hasValue(3);
            assertThat(brokerCalls).hasValue(2);
            assertLegacyTablesAbsent(database);
        }
    }

    private static RuntimeInstance runtime(
            ProjectPersistenceAssembly persistence,
            AgentChatModel model,
            ExecutionBroker broker,
            IdentifierGenerator identifiers,
            String workerId) {
        RuntimePersistencePorts ports = persistence.ports();
        AuthorizedWorkspaceProvisioning provisioning = provisioning();
        ensureSession(ports);
        ManualExecutionScheduler scheduler = new ManualExecutionScheduler();
        InteractionPort interactions = ports.interactions();
        PublicToolPolicy publicPolicy = (run, binding, request) -> {
            provisioning.requireAuthorized(TENANT, PRINCIPAL, WORKSPACE, WorkspaceAccessMode.DEVELOP);
            return new PolicyDecision(
                    PolicyEffect.ALLOW,
                    Optional.empty(),
                    "M4_TEST_ALLOW",
                    "M4 integration policy allowed the tool",
                    "sha256:m4-integration-allow");
        };
        var canonicalizer = new CodingExecutionToolRequestCanonicalizer();
        var runtimeVerifier = new RuntimeToolExecutionVerifier(
                ports.runs(), ports.state(), interactions, canonicalizer, publicPolicy);
        var executionPolicy = new CodingAgentExecutionPolicy(
                runtimeVerifier,
                provisioning,
                TENANT,
                PRINCIPAL,
                new ExecutionEnvironmentRef(List.of("test-environment")),
                normalProfile().ref(),
                ExecutionScratchSpaceSpec.genericRequired(),
                Duration.ofMinutes(2),
                8_192,
                4);
        ExecutionBroker guardedBroker = authorizeBeforeDispatch(broker, executionPolicy);
        ProjectExecutionToolOperations normal =
                operations(guardedBroker, normalProfile().ref());
        ProjectToolOperations unreachable = (toolName, workspaceId, actor, runRef, arguments) -> {
            throw new AssertionError("unexpected non-execution tool");
        };
        ProjectToolExecutor provider = new ProjectToolExecutor(
                (runId, principal) -> {
                    var current =
                            provisioning.requireAuthorized(TENANT, principal, WORKSPACE, WorkspaceAccessMode.READ);
                    return new RunWorkspaceAccess(
                            WORKSPACE,
                            current.mode() == WorkspaceAccessMode.DEVELOP
                                    ? Set.of("execution_run")
                                    : Set.of("file_read"));
                },
                unreachable,
                normal);
        var catalog = new ProjectToolCatalog()
                .freeze(Set.of("execution_run"), Set.of("execution_run"), true, provider, normalProfile());
        DefaultAgentRuntime runtime = new RuntimeCoreBuilder()
                .registerChatModel("openai-compatible", "1.0.0", model)
                .scheduler(scheduler)
                .persistence(ports)
                .identifierGenerator(identifiers)
                .timeProvider(TIME)
                .workerId(workerId)
                .toolRequestCanonicalizer(canonicalizer)
                .publicToolPolicy(publicPolicy)
                .toolPlatform(catalog, new DefaultToolInvoker(catalog), new JsonSchema202012Validator())
                .build();
        return new RuntimeInstance(runtime, scheduler, ports);
    }

    private static ProjectExecutionToolOperations operations(ExecutionBroker broker, SandboxProfileRef profile) {
        return new ProjectExecutionToolOperations(
                broker,
                new AtomicIdentifierGenerator("execution"),
                TIME,
                new ExecutionEnvironmentRef(List.of("test-environment")),
                profile,
                Duration.ofMinutes(2),
                8_192,
                2_000,
                4,
                ExecutionOutputObserver.noop(),
                java.util.function.UnaryOperator.identity(),
                ExecutionScratchSpaceSpec.genericRequired(),
                ExecutionWorkspaceTargetResolver.currentWorkspaceOnly());
    }

    private static ExecutionBroker broker(AtomicInteger calls, List<SandboxProfileRef> observedProfiles) {
        return new ExecutionBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request) {
                return execute(request, ExecutionOutputObserver.noop());
            }

            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                observedProfiles.add(request.sandboxProfileRef());
                if (calls.incrementAndGet() == 1) {
                    throw new ExecutionPreflightException(
                            "NETWORK_PERMISSION_REQUIRED", "network access requires operator approval", null);
                }
                observer.onStarted();
                return completed(request.id(), 128);
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

    private static ExecutionBroker authorizeBeforeDispatch(
            ExecutionBroker delegate, CodingAgentExecutionPolicy policy) {
        return new ExecutionBroker() {
            @Override
            public ExecutionResult execute(ExecutionRequest request) {
                return execute(request, ExecutionOutputObserver.noop());
            }

            @Override
            public ExecutionResult execute(ExecutionRequest request, ExecutionOutputObserver observer) {
                policy.authorize(request, ExecutionPolicyEntryPoint.FIRST_EXECUTION);
                return delegate.execute(request, observer);
            }

            @Override
            public boolean cancel(ExecutionId id) {
                return delegate.cancel(id);
            }

            @Override
            public Optional<ExecutionResult> find(ExecutionId id) {
                return delegate.find(id);
            }
        };
    }

    private static AuthorizedWorkspaceProvisioning provisioning() {
        try {
            Path root = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
            var projects = new InMemoryProjectStore();
            var workspaces = new InMemoryWorkspaceStore();
            var locations = new HostWorkspaceLocationStore();
            ProjectId projectId = new ProjectId("m6-project");
            locations.register(WORKSPACE, root);
            workspaces.create(Workspace.provision(WORKSPACE, projectId, WorkspaceRevision.initial("m6-revision"), NOW)
                    .activate(NOW));
            return new AuthorizedWorkspaceProvisioning(
                    projectId,
                    workspaces,
                    locations,
                    new WorkspaceService(projects, workspaces, () -> NOW),
                    TENANT,
                    PRINCIPAL,
                    () -> NOW,
                    HostWorkspaceScope.initial(AuthorizedHostDirectory.of(WORKSPACE, root)),
                    new InMemoryAuthorizedDirectoryStore(),
                    "m6-workspace");
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("test workspace root is unavailable", exception);
        }
    }

    private static ExecutionResult completed(ExecutionId id, int exitCode) {
        ExecutionOutput empty = new ExecutionOutput("", null, 0, "0".repeat(64), false, false);
        return new ExecutionResult(
                id,
                ExecutionStatus.EXITED,
                exitCode,
                NOW,
                NOW,
                empty,
                empty,
                "test-sandbox",
                new ResourceUsageSummary(Duration.ZERO, 1),
                null,
                false);
    }

    private static SandboxProfile normalProfile() {
        return profile("normal");
    }

    private static SandboxProfile profile(String id) {
        return new SandboxProfile(
                new SandboxProfileRef(id, "1"),
                "host-guarded",
                SandboxConfigurationDigest.sha256Fields(List.of(id)),
                Set.of(),
                Set.of(),
                true);
    }

    private static void ensureSession(RuntimePersistencePorts ports) {
        ports.unitOfWork().execute(() -> {
            if (ports.sessions().find(SESSION).isEmpty()) {
                ports.sessions()
                        .insert(AgentSession.open(SESSION, TENANT, PRINCIPAL, null, SessionScope.USER, NOW, Map.of()));
            }
            return null;
        });
    }

    private static AgentRunRequest request() {
        return new AgentRunRequest(
                "m4-execution-recovery",
                new AgentDefinitionId("m4-agent"),
                Optional.empty(),
                "m4-profile",
                SESSION,
                Optional.empty(),
                "fetch the remote repository state",
                List.of(),
                RuntimeOverrides.NONE);
    }

    private static AesGcmModelContinuationProtector protector() {
        return new AesGcmModelContinuationProtector(new SecretKeySpec(PROTECTOR_KEY, "AES"), new SecureRandom());
    }

    private static ProjectPersistenceAssembly persistence(Path database) {
        return ProjectPersistenceAssembly.open(
                ProjectPersistenceConfiguration.sqlite(database, "env://M4_TEST_PROTECTOR"),
                Clock.fixed(NOW, ZoneOffset.UTC),
                new AtomicIdentifierGenerator("persistence"),
                protector());
    }

    private static void assertLegacyTablesAbsent(Path database) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            for (String table : List.of(
                    "policy_snapshot",
                    "policy_decision",
                    "policy_authorization_evidence",
                    "approval_grant",
                    "project_trust",
                    "approval_request_metadata",
                    "approval_response_metadata")) {
                try (var statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?")) {
                    statement.setString(1, table);
                    try (ResultSet result = statement.executeQuery()) {
                        assertThat(result.next()).isTrue();
                        assertThat(result.getLong(1)).as(table).isZero();
                    }
                }
            }
        }
    }

    private static final class AtomicIdentifierGenerator implements IdentifierGenerator {
        private final String prefix;
        private final AtomicInteger values = new AtomicInteger();

        private AtomicIdentifierGenerator(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public String nextValue() {
            return prefix + "-" + values.incrementAndGet();
        }
    }

    private record RuntimeInstance(
            DefaultAgentRuntime runtime, ManualExecutionScheduler scheduler, RuntimePersistencePorts ports) {}
}
