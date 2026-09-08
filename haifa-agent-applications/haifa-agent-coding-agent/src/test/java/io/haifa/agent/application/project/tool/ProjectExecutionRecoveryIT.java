package io.haifa.agent.application.project.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.application.project.persistence.ProjectPersistenceAssembly;
import io.haifa.agent.application.project.persistence.ProjectPersistenceConfiguration;
import io.haifa.agent.application.project.policy.CodingAgentExecutionPolicy;
import io.haifa.agent.application.project.policy.CodingExecutionRecoveryPolicy;
import io.haifa.agent.application.project.workspace.WorkspaceAccess;
import io.haifa.agent.application.project.workspace.WorkspaceAccessMode;
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
import io.haifa.agent.project.binding.WorkspaceBinding;
import io.haifa.agent.project.binding.WorkspaceBindingId;
import io.haifa.agent.project.binding.WorkspaceBindingMode;
import io.haifa.agent.project.binding.WorkspaceLocationRef;
import io.haifa.agent.project.core.store.InMemoryProjectStore;
import io.haifa.agent.project.core.store.InMemoryWorkspaceBindingStore;
import io.haifa.agent.project.core.store.InMemoryWorkspaceStore;
import io.haifa.agent.project.core.workspace.WorkspaceService;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.hostworkspace.HostWorkspaceLocationStore;
import io.haifa.agent.project.hostworkspace.scope.AuthorizedHostDirectory;
import io.haifa.agent.project.hostworkspace.scope.AuthorizedWorkspaceProvisioning;
import io.haifa.agent.project.hostworkspace.scope.HostWorkspaceScope;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.workspace.Workspace;
import io.haifa.agent.project.workspace.WorkspaceCapabilitySet;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.project.workspace.WorkspacePermissionSet;
import io.haifa.agent.project.workspace.WorkspacePurpose;
import io.haifa.agent.project.workspace.WorkspaceRevision;
import io.haifa.agent.project.workspace.WorkspaceRoot;
import io.haifa.agent.runtime.api.AgentRunRequest;
import io.haifa.agent.runtime.api.InteractionResponse;
import io.haifa.agent.runtime.api.InteractionResponseId;
import io.haifa.agent.runtime.api.InteractionResponseType;
import io.haifa.agent.runtime.api.RuntimeOverrides;
import io.haifa.agent.runtime.core.DefaultAgentRuntime;
import io.haifa.agent.runtime.core.RuntimeCoreBuilder;
import io.haifa.agent.runtime.core.execution.ManualExecutionScheduler;
import io.haifa.agent.runtime.core.interaction.InteractionPort;
import io.haifa.agent.runtime.core.model.continuation.AesGcmModelContinuationProtector;
import io.haifa.agent.runtime.core.storage.RuntimePersistencePorts;
import io.haifa.agent.runtime.core.tool.PublicToolPolicy;
import io.haifa.agent.runtime.core.tool.RuntimeToolExecutionVerifier;
import io.haifa.agent.sandbox.api.NetworkPolicy;
import io.haifa.agent.sandbox.api.SandboxCapabilities;
import io.haifa.agent.sandbox.api.SandboxConfigurationDigest;
import io.haifa.agent.sandbox.api.SandboxFilesystemPolicy;
import io.haifa.agent.sandbox.api.SandboxProfile;
import io.haifa.agent.tool.core.DefaultToolInvoker;
import io.haifa.agent.tool.core.JsonSchema202012Validator;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
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
    void resumesOneExactSuccessorAfterFreshSqliteReopen(@TempDir Path directory) throws Exception {
        AtomicInteger brokerCalls = new AtomicInteger();
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger ids = new AtomicInteger();
        List<SandboxProfileRef> observedProfiles = new ArrayList<>();
        IdentifierGenerator identifiers = () -> "m4-id-" + ids.incrementAndGet();
        ExecutionBroker broker = broker(brokerCalls, observedProfiles);
        AgentChatModel model = model(modelCalls);
        AgentRunId runId;
        Path database = directory.resolve("runtime.db").toAbsolutePath();

        try (ProjectPersistenceAssembly first = persistence(database)) {
            first.workspaceAccess()
                    .createIfAbsent(new WorkspaceAccess(TENANT, PRINCIPAL, WORKSPACE, WorkspaceAccessMode.DEVELOP));
            RuntimeInstance instance = runtime(first, model, broker, identifiers, "m4-worker-a");
            runId = instance.runtime().start(request()).runId();
            instance.scheduler().runAll();

            assertThat(instance.runtime().find(runId).orElseThrow().status())
                    .isEqualTo(AgentRunStatus.WAITING_INTERACTION);
            assertThat(instance.ports()
                            .interactions()
                            .pending(runId)
                            .orElseThrow()
                            .type())
                    .isEqualTo("execution-recovery");
            assertThat(instance.ports().state().toolCalls(runId)).hasSize(1);
            assertThat(modelCalls).hasValue(1);
            assertThat(brokerCalls).hasValue(1);
            assertLegacyRowsRemainZero(database);
        }

        try (ProjectPersistenceAssembly reopened = persistence(database)) {
            RuntimeInstance instance = runtime(reopened, model, broker, identifiers, "m4-worker-b");
            var recovery = instance.ports().interactions().pending(runId).orElseThrow();
            instance.runtime()
                    .respond(new InteractionResponse(
                            new InteractionResponseId("execution-recovery-response"),
                            recovery.id(),
                            runId,
                            InteractionResponseType.APPROVE,
                            List.of(),
                            "execution-recovery-response-key",
                            NOW));

            assertThat(instance.ports()
                            .interactions()
                            .record(recovery.id())
                            .orElseThrow()
                            .state()
                            .name())
                    .isEqualTo("RESPONDED");
            assertThat(instance.ports().state().toolCalls(runId)).hasSize(1);
            assertThat(modelCalls).hasValue(1);
            assertThat(brokerCalls).hasValue(1);
            assertLegacyRowsRemainZero(database);
        }

        try (ProjectPersistenceAssembly recovered = persistence(database)) {
            RuntimeInstance instance = runtime(recovered, model, broker, identifiers, "m4-worker-c");
            var recovery = instance.ports()
                    .interactions()
                    .record(io.haifa.agent.runtime.core.recovery.ExecutionRecoveryKeys.requestId(
                            runId,
                            instance.ports().state().toolCalls(runId).getFirst().id()))
                    .orElseThrow();
            instance.runtime().recover(runId);
            instance.scheduler().runAll();

            assertThat(instance.runtime().find(runId).orElseThrow().status()).isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(instance.ports().state().toolCalls(runId)).hasSize(2);
            assertThat(instance.ports()
                            .interactions()
                            .record(recovery.request().id())
                            .orElseThrow()
                            .state()
                            .name())
                    .isEqualTo("APPLIED");
            assertThat(modelCalls).hasValue(2);
            assertThat(brokerCalls).hasValue(2);
            assertThat(observedProfiles)
                    .containsExactly(new SandboxProfileRef("normal", "1"), new SandboxProfileRef("recovery", "1"));
            assertLegacyRowsRemainZero(database);
        }
    }

    @Test
    void workspaceAccessDowngradeBeforeApplicationFailsClosedWithoutSuccessor(@TempDir Path directory)
            throws Exception {
        AtomicInteger brokerCalls = new AtomicInteger();
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger ids = new AtomicInteger();
        IdentifierGenerator identifiers = () -> "m4-downgrade-id-" + ids.incrementAndGet();
        Path database = directory.resolve("runtime.db").toAbsolutePath();

        try (ProjectPersistenceAssembly persistence = persistence(database)) {
            persistence
                    .workspaceAccess()
                    .createIfAbsent(new WorkspaceAccess(TENANT, PRINCIPAL, WORKSPACE, WorkspaceAccessMode.DEVELOP));
            RuntimeInstance instance = runtime(
                    persistence, model(modelCalls), broker(brokerCalls, new ArrayList<>()), identifiers, "m4-worker");
            AgentRunId runId = instance.runtime().start(request()).runId();
            instance.scheduler().runAll();
            var recovery = instance.ports().interactions().pending(runId).orElseThrow();

            persistence
                    .workspaceAccess()
                    .replace(new WorkspaceAccess(TENANT, PRINCIPAL, WORKSPACE, WorkspaceAccessMode.READ));
            instance.runtime()
                    .respond(new InteractionResponse(
                            new InteractionResponseId("execution-recovery-downgrade-response"),
                            recovery.id(),
                            runId,
                            InteractionResponseType.APPROVE,
                            List.of(),
                            "execution-recovery-downgrade-key",
                            NOW));
            instance.scheduler().runAll();

            assertThat(instance.runtime().find(runId).orElseThrow().status()).isEqualTo(AgentRunStatus.FAILED);
            assertThat(instance.ports().state().toolCalls(runId)).hasSize(1);
            assertThat(instance.ports()
                            .interactions()
                            .record(recovery.id())
                            .orElseThrow()
                            .state()
                            .name())
                    .isEqualTo("INVALIDATED");
            assertThat(modelCalls).hasValue(1);
            assertThat(brokerCalls).hasValue(1);
            assertLegacyRowsRemainZero(database);
        }
    }

    private static RuntimeInstance runtime(
            ProjectPersistenceAssembly persistence,
            AgentChatModel model,
            ExecutionBroker broker,
            IdentifierGenerator identifiers,
            String workerId) {
        RuntimePersistencePorts ports = persistence.ports();
        ensureSession(ports);
        ManualExecutionScheduler scheduler = new ManualExecutionScheduler();
        InteractionPort interactions = ports.interactions();
        var recoveryAuthorization = new ProjectExecutionRecoveryAuthorization(ports.state(), interactions);
        PublicToolPolicy publicPolicy = new CodingExecutionRecoveryPolicy(
                (run, binding, request) -> {
                    persistence.workspaceAccess().require(TENANT, PRINCIPAL, WORKSPACE, WorkspaceAccessMode.DEVELOP);
                    boolean successor = request.toolCallId().value().startsWith("execution-recovery-tool:v1:");
                    return new PolicyDecision(
                            successor ? PolicyEffect.ASK : PolicyEffect.ALLOW,
                            successor
                                    ? Optional.of(io.haifa.agent.policy.api.PolicyChallenge.APPROVAL)
                                    : Optional.empty(),
                            successor ? "M6_RECOVERY_TEST_ASK" : "M4_TEST_ALLOW",
                            successor
                                    ? "M6 recovery integration requires its existing approval"
                                    : "M4 integration policy allowed the tool",
                            "sha256:m4-integration-allow");
                },
                recoveryAuthorization);
        var canonicalizer = new CodingExecutionToolRequestCanonicalizer();
        var runtimeVerifier = new RuntimeToolExecutionVerifier(
                ports.runs(), ports.state(), interactions, canonicalizer, publicPolicy);
        var executionPolicy = new CodingAgentExecutionPolicy(
                runtimeVerifier,
                recoveryAuthorization,
                persistence.workspaceAccess(),
                provisioning(),
                TENANT,
                PRINCIPAL,
                new ExecutionEnvironmentRef(List.of("test-environment")),
                new ExecutionEnvironmentRef(List.of("test-environment")),
                normalProfile().ref(),
                recoveryProfile().ref(),
                ExecutionScratchSpaceSpec.genericRequired(),
                Duration.ofMinutes(1),
                Duration.ofMinutes(2),
                8_192,
                4);
        ExecutionBroker guardedBroker = authorizeBeforeDispatch(broker, executionPolicy);
        ProjectExecutionToolOperations normal = operations(guardedBroker, new SandboxProfileRef("normal", "1"));
        ProjectExecutionToolOperations recovery = operations(guardedBroker, new SandboxProfileRef("recovery", "1"));
        ProjectToolOperations unreachable = (toolName, workspaceId, actor, runRef, arguments) -> {
            throw new AssertionError("unexpected non-execution tool");
        };
        ProjectToolExecutor provider = ProjectToolExecutor.withExecutionRecovery(
                (runId, principal) -> {
                    WorkspaceAccess current = persistence
                            .workspaceAccess()
                            .require(TENANT, principal, WORKSPACE, WorkspaceAccessMode.READ);
                    return new RunWorkspaceAccess(
                            WORKSPACE,
                            current.mode() == WorkspaceAccessMode.DEVELOP
                                    ? Set.of("execution.run")
                                    : Set.of("file.read"));
                },
                unreachable,
                normal,
                recovery,
                recoveryAuthorization,
                normalProfile(),
                recoveryProfile(),
                null);
        var catalog = new ProjectToolCatalog()
                .freeze(Set.of("execution.run"), Set.of("execution.run"), true, provider, normalProfile());
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
                Duration.ofMinutes(1),
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
                return completed(request.id());
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
            var bindings = new InMemoryWorkspaceBindingStore();
            var locations = new HostWorkspaceLocationStore();
            ProjectId projectId = new ProjectId("m6-project");
            WorkspaceBindingId bindingId = new WorkspaceBindingId("m6-binding");
            WorkspaceLocationRef locationRef = new WorkspaceLocationRef("m6-location");
            locations.register(locationRef, root);
            bindings.create(WorkspaceBinding.provision(
                            bindingId,
                            locationRef,
                            WorkspaceBindingMode.DIRECT,
                            PRINCIPAL,
                            WorkspaceCapabilitySet.executionFiles(),
                            WorkspacePermissionSet.readWriteExecute(),
                            HostWorkspaceLocationStore.fingerprintFor(root),
                            NOW)
                    .activate(NOW));
            workspaces.create(Workspace.provision(
                            WORKSPACE,
                            projectId,
                            WorkspacePurpose.PRIMARY,
                            new WorkspaceRoot(ProjectPath.root(), bindingId, "test"),
                            WorkspaceRevision.initial("m6-revision"),
                            NOW)
                    .activate(NOW));
            return new AuthorizedWorkspaceProvisioning(
                    projectId,
                    workspaces,
                    bindings,
                    locations,
                    new WorkspaceService(projects, workspaces, bindings, () -> "m6-id", () -> NOW),
                    PRINCIPAL,
                    () -> NOW,
                    HostWorkspaceScope.initial(AuthorizedHostDirectory.of(WORKSPACE, root)));
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("test workspace root is unavailable", exception);
        }
    }

    private static AgentChatModel model(AtomicInteger calls) {
        Queue<AgentChatResponse> responses = new ArrayDeque<>(List.of(
                new AgentChatResponse(
                        "m4-model-tool",
                        "test-model",
                        "",
                        List.of(new ModelToolCall(
                                new ProviderToolCallCorrelationId("m4-provider-call"),
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
                        "recovered",
                        List.of(),
                        ModelFinishReason.STOP,
                        ModelUsage.unpriced(1, 1),
                        "",
                        Map.of())));
        return request -> {
            calls.incrementAndGet();
            return responses.remove();
        };
    }

    private static ExecutionResult completed(ExecutionId id) {
        ExecutionOutput empty = new ExecutionOutput("", null, 0, "0".repeat(64), false, false);
        return new ExecutionResult(
                id,
                ExecutionStatus.SUCCEEDED,
                0,
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
        return profile("normal", NetworkPolicy.DENY);
    }

    private static SandboxProfile recoveryProfile() {
        return profile("recovery", NetworkPolicy.ALLOW);
    }

    private static SandboxProfile profile(String id, NetworkPolicy network) {
        return new SandboxProfile(
                new SandboxProfileRef(id, "1"),
                "host-guarded",
                SandboxConfigurationDigest.sha256Fields(List.of(id, network.name())),
                Set.of(),
                Set.of(),
                true,
                network,
                SandboxFilesystemPolicy.hostCompatible(),
                new SandboxCapabilities(true, false, network == NetworkPolicy.DENY, false, false));
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

    private static void assertLegacyRowsRemainZero(Path database) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            for (String table : List.of(
                    "policy_snapshot",
                    "policy_decision",
                    "policy_authorization_evidence",
                    "approval_grant",
                    "project_trust",
                    "approval_request_metadata",
                    "approval_response_metadata")) {
                try (Statement statement = connection.createStatement();
                        ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getLong(1)).as(table).isZero();
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
