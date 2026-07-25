package io.haifa.agent.application.project.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.application.project.product.ProjectProductService;
import io.haifa.agent.application.project.product.TrustedProductCaller;
import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.project.binding.WorkspaceBindingId;
import io.haifa.agent.project.configuration.InMemoryProjectConfigurationStore;
import io.haifa.agent.project.configuration.ProjectConfiguration;
import io.haifa.agent.project.configuration.ProjectConfigurationId;
import io.haifa.agent.project.configuration.ProjectConfigurationService;
import io.haifa.agent.project.configuration.ProjectConfigurationVersion;
import io.haifa.agent.project.domain.Project;
import io.haifa.agent.project.domain.ProjectConfigurationRef;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.path.ProjectPath;
import io.haifa.agent.project.store.InMemoryProjectStore;
import io.haifa.agent.project.store.InMemoryWorkspaceStore;
import io.haifa.agent.project.workspace.Workspace;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.project.workspace.WorkspacePurpose;
import io.haifa.agent.project.workspace.WorkspaceRevision;
import io.haifa.agent.project.workspace.WorkspaceRoot;
import io.haifa.agent.runtime.api.AgentRunHandle;
import io.haifa.agent.runtime.api.AgentRunListener;
import io.haifa.agent.runtime.api.AgentRunOutputEvent;
import io.haifa.agent.runtime.api.AgentRunOutputListener;
import io.haifa.agent.runtime.api.AgentRunRequest;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.runtime.api.AgentRuntime;
import io.haifa.agent.runtime.api.InteractionResponse;
import io.haifa.agent.runtime.api.ResumeAgentRunRequest;
import io.haifa.agent.runtime.api.RunOutputCursor;
import io.haifa.agent.runtime.api.RuntimeCommand;
import io.haifa.agent.runtime.api.RuntimeCommandResult;
import io.haifa.agent.runtime.api.RuntimeOverrides;
import io.haifa.agent.runtime.core.RuntimeCoreBuilder;
import io.haifa.agent.runtime.core.model.continuation.AesGcmModelContinuationProtector;
import io.haifa.agent.store.jsonl.JsonlTranscriptReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectPersistenceAssemblyTest {
    private static final Instant NOW = Instant.parse("2026-07-25T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final TimeProvider TIME = () -> NOW;
    private static final TenantRef TENANT = new TenantRef("tenant-1");
    private static final PrincipalRef PRINCIPAL = new PrincipalRef("principal-1", "user");

    @TempDir
    Path directory;

    @Test
    void assemblesOnlyTheThreeSupportedModesWithFreshWorkerIds() throws Exception {
        TestIds ids = new TestIds("worker");
        try (ProjectPersistenceAssembly memory =
                ProjectPersistenceAssembly.open(ProjectPersistenceConfiguration.memory(), CLOCK, ids, null)) {
            assertThat(memory.mode()).isEqualTo(ProjectPersistenceMode.MEMORY);
        }

        Path database = directory.resolve("runtime.db");
        String firstWorker;
        try (ProjectPersistenceAssembly sqlite = ProjectPersistenceAssembly.open(
                ProjectPersistenceConfiguration.sqlite(database, "env://TEST_KEY"), CLOCK, ids, protector())) {
            firstWorker = sqlite.workerId();
            assertThat(sqlite.mode()).isEqualTo(ProjectPersistenceMode.SQLITE);
            try (var entries = Files.list(directory)) {
                assertThat(entries.noneMatch(path -> path.toString().endsWith(".jsonl")))
                        .isTrue();
            }
        }
        try (ProjectPersistenceAssembly reopened = ProjectPersistenceAssembly.open(
                ProjectPersistenceConfiguration.sqlite(database, "env://TEST_KEY"), CLOCK, ids, protector())) {
            assertThat(reopened.workerId()).isNotEqualTo(firstWorker);
        }
        assertThatThrownBy(() -> ProjectPersistenceMode.parse("JSONL"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MEMORY, SQLITE, or SQLITE_WITH_JSONL");
        assertThatThrownBy(() -> ProjectPersistenceConfiguration.sqlite(Path.of("relative.db"), "env://TEST_KEY"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("database path must be absolute");
        assertThatThrownBy(() -> ProjectPersistenceAssembly.open(
                        ProjectPersistenceConfiguration.sqlite(
                                directory.resolve("missing-protector.db"), "env://TEST_KEY"),
                        CLOCK,
                        ids,
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("durable continuation protector");
        assertThat(Files.deleteIfExists(database)).isTrue();
    }

    @Test
    void sqliteWithJsonlProjectsOnlyAfterRuntimeCommitAndClosesWithoutDatabaseLock() throws Exception {
        Path database = directory.resolve("runtime-with-jsonl.db");
        Path transcripts = Files.createDirectory(directory.resolve("transcripts"));
        AgentRunId runId;
        try (ProjectPersistenceAssembly assembly = ProjectPersistenceAssembly.open(
                ProjectPersistenceConfiguration.sqliteWithJsonl(database, transcripts, "env://TEST_KEY"),
                CLOCK,
                new TestIds("jsonl"),
                protector())) {
            var runtime = assembly.configure(new RuntimeCoreBuilder())
                    .registerChatModel("openai-compatible", "1.0.0", ignored -> finalResponse())
                    .identifierGenerator(new TestIds("runtime"))
                    .timeProvider(TIME)
                    .scheduler((ignored, task) -> task.run())
                    .build();
            assembly.attachProjection(runtime);
            AgentSessionId sessionId = new AgentSessionId("jsonl-session");
            assembly.provisionUserSession(sessionId, TENANT, PRINCIPAL, CLOCK);
            runId = runtime.start(request(sessionId)).runId();
            assertThat(runtime.handle(runId).awaitCompletion(Duration.ofSeconds(3)))
                    .isPresent();

            assertThat(assembly.ports().runs().find(runId)).isPresent();
            assertThat(new JsonlTranscriptReader(transcripts)
                            .read(runId.value())
                            .events())
                    .extracting(event -> event.eventType())
                    .containsSubsequence("run.created", "run.queued");
            assertThat(assembly.ports().outbox().pending()).isEmpty();
        }
        assertThat(Files.deleteIfExists(database)).isTrue();
    }

    @Test
    void productContinueSessionRecoversVersionedMappingAfterApplicationRebuild() {
        Path database = directory.resolve("product-session.db");
        ProductFixture fixture = productFixture();
        AgentSessionId sessionId;
        String digest;
        try (ProjectPersistenceAssembly first = ProjectPersistenceAssembly.open(
                ProjectPersistenceConfiguration.sqlite(database, "env://TEST_KEY"),
                CLOCK,
                new TestIds("first"),
                protector())) {
            ProjectProductService service = fixture.service(first, new TestIds("service-a"));
            var started = service.start(fixture.projectId, "first", List.of());
            sessionId = started.sessionId();
            digest = started.configurationDigest();
        }
        try (ProjectPersistenceAssembly second = ProjectPersistenceAssembly.open(
                ProjectPersistenceConfiguration.sqlite(database, "env://TEST_KEY"),
                CLOCK,
                new TestIds("second"),
                protector())) {
            ProjectProductService service = fixture.service(second, new TestIds("service-b"));
            var continued = service.continueSession(sessionId, "continue", List.of());

            assertThat(continued.sessionId()).isEqualTo(sessionId);
            assertThat(continued.configurationDigest()).isEqualTo(digest);
            assertThat(second.productSessions().find(sessionId))
                    .isPresent()
                    .get()
                    .extracting(session -> session.configurationVersion().value())
                    .isEqualTo("1");
        }
    }

    @Test
    void rejectsNonDirectoryTranscriptRootWithoutEchoingPath() throws Exception {
        Path database = directory.resolve("bad-transcript.db");
        Path file = Files.writeString(directory.resolve("sensitive-transcript-name"), "not a directory");
        assertThatThrownBy(() -> ProjectPersistenceAssembly.open(
                        ProjectPersistenceConfiguration.sqliteWithJsonl(database, file, "env://TEST_KEY"),
                        CLOCK,
                        new TestIds("invalid"),
                        protector()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("transcript root must be an existing writable controlled directory")
                .hasMessageNotContaining(file.toString());
    }

    @Test
    void boundedBusyRetrySucceedsAfterLockReleaseAndFailsClearlyWhenExhausted() throws Exception {
        Path recoverableDatabase = directory.resolve("busy-recoverable.db");
        var recoverableConfiguration = new ProjectPersistenceConfiguration(
                ProjectPersistenceMode.SQLITE,
                Optional.of(recoverableDatabase),
                Optional.empty(),
                Optional.of("env://TEST_KEY"),
                20,
                ProjectPersistenceConfiguration.DEFAULT_MAXIMUM_PAYLOAD_BYTES);
        try (ProjectPersistenceAssembly assembly = ProjectPersistenceAssembly.open(
                        recoverableConfiguration, CLOCK, new TestIds("busy-worker"), protector());
                var blocker = DriverManager.getConnection("jdbc:sqlite:" + recoverableDatabase);
                var statement = blocker.createStatement();
                var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            AgentSessionId sessionId = new AgentSessionId("busy-session");
            assembly.provisionUserSession(sessionId, TENANT, PRINCIPAL, CLOCK);
            var runtime = assembly.configure(new RuntimeCoreBuilder())
                    .registerChatModel("openai-compatible", "1.0.0", ignored -> finalResponse())
                    .identifierGenerator(new TestIds("busy-runtime"))
                    .timeProvider(TIME)
                    .build();
            statement.execute("BEGIN IMMEDIATE");
            var started = executor.submit(() -> runtime.start(request(sessionId)));
            Thread.sleep(90);
            statement.execute("COMMIT");

            assertThat(started.get(3, TimeUnit.SECONDS).runId()).isNotNull();
        }

        Path exhaustedDatabase = directory.resolve("busy-exhausted.db");
        var exhaustedConfiguration = new ProjectPersistenceConfiguration(
                ProjectPersistenceMode.SQLITE,
                Optional.of(exhaustedDatabase),
                Optional.empty(),
                Optional.of("env://TEST_KEY"),
                20,
                ProjectPersistenceConfiguration.DEFAULT_MAXIMUM_PAYLOAD_BYTES);
        try (ProjectPersistenceAssembly assembly = ProjectPersistenceAssembly.open(
                        exhaustedConfiguration, CLOCK, new TestIds("exhausted-worker"), protector());
                var blocker = DriverManager.getConnection("jdbc:sqlite:" + exhaustedDatabase);
                var statement = blocker.createStatement()) {
            AgentSessionId sessionId = new AgentSessionId("exhausted-session");
            assembly.provisionUserSession(sessionId, TENANT, PRINCIPAL, CLOCK);
            var runtime = assembly.configure(new RuntimeCoreBuilder())
                    .registerChatModel("openai-compatible", "1.0.0", ignored -> finalResponse())
                    .identifierGenerator(new TestIds("exhausted-runtime"))
                    .timeProvider(TIME)
                    .build();
            statement.execute("BEGIN IMMEDIATE");

            assertThatThrownBy(() -> runtime.start(request(sessionId)))
                    .isInstanceOf(io.haifa.agent.store.sqlite.SqliteStoreException.class)
                    .extracting(exception -> ((io.haifa.agent.store.sqlite.SqliteStoreException) exception).failure())
                    .isEqualTo(io.haifa.agent.store.sqlite.SqliteStoreFailure.DATABASE_BUSY);
            statement.execute("ROLLBACK");
            assertThat(assembly.ports().outbox().pending()).isEmpty();
        }
    }

    private static AgentRunRequest request(AgentSessionId sessionId) {
        return new AgentRunRequest(
                "start-key",
                new AgentDefinitionId("assembly-agent"),
                Optional.empty(),
                "assembly-profile",
                sessionId,
                Optional.empty(),
                "test",
                List.of(),
                RuntimeOverrides.NONE);
    }

    private static AgentChatResponse finalResponse() {
        return new AgentChatResponse(
                "response",
                "model",
                "done",
                List.of(),
                ModelFinishReason.STOP,
                ModelUsage.unpriced(1, 1),
                "",
                Map.of());
    }

    private static AesGcmModelContinuationProtector protector() {
        return new AesGcmModelContinuationProtector(new SecretKeySpec(new byte[32], "AES"), new SecureRandom());
    }

    private static ProductFixture productFixture() {
        ProjectId projectId = new ProjectId("project-1");
        WorkspaceId workspaceId = new WorkspaceId("workspace-1");
        InMemoryProjectStore projects = new InMemoryProjectStore();
        projects.create(Project.create(
                        projectId,
                        TENANT,
                        PRINCIPAL,
                        "Demo",
                        "",
                        new ProjectConfigurationRef("config-1", "1"),
                        NOW,
                        Map.of())
                .assignDefaultWorkspace(workspaceId, NOW));
        InMemoryWorkspaceStore workspaces = new InMemoryWorkspaceStore();
        workspaces.create(Workspace.provision(
                        workspaceId,
                        projectId,
                        WorkspacePurpose.PRIMARY,
                        new WorkspaceRoot(ProjectPath.root(), new WorkspaceBindingId("binding-1"), "test"),
                        WorkspaceRevision.initial("root"),
                        NOW)
                .activate(NOW));
        InMemoryProjectConfigurationStore configurations = new InMemoryProjectConfigurationStore();
        configurations.publish(ProjectConfiguration.create(
                new ProjectConfigurationId("config-1"),
                new ProjectConfigurationVersion("1"),
                workspaceId,
                "coding",
                "1",
                Set.of("file.read"),
                Set.of("project.workspace.files"),
                Set.of("file.read"),
                "policy-1"));
        return new ProductFixture(projectId, projects, workspaces, configurations);
    }

    private record ProductFixture(
            ProjectId projectId,
            InMemoryProjectStore projects,
            InMemoryWorkspaceStore workspaces,
            InMemoryProjectConfigurationStore configurations) {
        private ProjectProductService service(ProjectPersistenceAssembly persistence, IdentifierGenerator ids) {
            return new ProjectProductService(
                    projects,
                    workspaces,
                    new ProjectConfigurationService(configurations),
                    persistence.productSessions(),
                    persistence.projectSessionProvisioner(CLOCK),
                    () -> new TrustedProductCaller(TENANT, PRINCIPAL),
                    new CapturingRuntime(),
                    ids,
                    new AgentDefinitionId("coding-agent"));
        }
    }

    private static final class CapturingRuntime implements AgentRuntime {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public AgentRunSnapshot start(AgentRunRequest request) {
            return new AgentRunSnapshot(
                    new AgentRunId("captured-" + sequence.incrementAndGet()),
                    AgentRunStatus.PENDING,
                    0,
                    NOW,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty());
        }

        @Override
        public AgentRunSnapshot resume(ResumeAgentRunRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AgentRunSnapshot respond(InteractionResponse response) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RuntimeCommandResult command(RuntimeCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<AgentRunSnapshot> find(AgentRunId runId) {
            return Optional.empty();
        }

        @Override
        public AgentRunHandle handle(AgentRunId runId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addListener(AgentRunListener listener) {}

        @Override
        public List<AgentRunOutputEvent> outputEvents(AgentRunId runId, RunOutputCursor after, int limit) {
            return List.of();
        }

        @Override
        public void addOutputListener(AgentRunOutputListener listener) {}
    }

    private static final class TestIds implements IdentifierGenerator {
        private final String prefix;
        private final AtomicInteger sequence = new AtomicInteger();

        private TestIds(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public String nextValue() {
            return prefix + "-" + sequence.incrementAndGet();
        }
    }
}
