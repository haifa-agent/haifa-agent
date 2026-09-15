package io.haifa.agent.application.project.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.application.project.product.ProjectProductException;
import io.haifa.agent.application.project.product.ProjectProductService;
import io.haifa.agent.application.project.product.TrustedProductCaller;
import io.haifa.agent.application.project.product.coding.CodingCommandBinding;
import io.haifa.agent.application.project.product.coding.CodingFollowUp;
import io.haifa.agent.application.project.product.coding.CodingFollowUpStatus;
import io.haifa.agent.application.project.product.coding.CodingModelCatalog;
import io.haifa.agent.application.project.product.coding.CodingModelControls;
import io.haifa.agent.application.project.product.coding.CodingModelOption;
import io.haifa.agent.application.project.product.coding.CodingModelPreference;
import io.haifa.agent.application.project.product.coding.CodingModelPreferences;
import io.haifa.agent.application.project.product.coding.CodingModelSelection;
import io.haifa.agent.application.project.product.coding.CodingModelState;
import io.haifa.agent.application.project.product.coding.CodingSessionActivity;
import io.haifa.agent.application.project.product.coding.CodingSessionCreateOptions;
import io.haifa.agent.application.project.product.coding.CodingSessionQuery;
import io.haifa.agent.application.project.product.coding.CodingSessionService;
import io.haifa.agent.application.project.product.coding.CodingSessionView;
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
import io.haifa.agent.project.configuration.ProjectConfiguration;
import io.haifa.agent.project.configuration.ProjectConfigurationId;
import io.haifa.agent.project.configuration.ProjectConfigurationVersion;
import io.haifa.agent.project.core.configuration.InMemoryProjectConfigurationStore;
import io.haifa.agent.project.core.configuration.ProjectConfigurationService;
import io.haifa.agent.project.core.store.InMemoryProjectStore;
import io.haifa.agent.project.core.store.InMemoryWorkspaceStore;
import io.haifa.agent.project.domain.Project;
import io.haifa.agent.project.domain.ProjectConfigurationRef;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.project.workspace.Workspace;
import io.haifa.agent.project.workspace.WorkspaceId;
import io.haifa.agent.project.workspace.WorkspaceRevision;
import io.haifa.agent.runtime.api.AgentRunHandle;
import io.haifa.agent.runtime.api.AgentRunListener;
import io.haifa.agent.runtime.api.AgentRunOutputEvent;
import io.haifa.agent.runtime.api.AgentRunOutputListener;
import io.haifa.agent.runtime.api.AgentRunRequest;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.runtime.api.AgentRuntime;
import io.haifa.agent.runtime.api.InteractionResponse;
import io.haifa.agent.runtime.api.ResumeAgentRunRequest;
import io.haifa.agent.runtime.api.RunInputReceipt;
import io.haifa.agent.runtime.api.RunInputReceiptStatus;
import io.haifa.agent.runtime.api.RunInputSubmission;
import io.haifa.agent.runtime.api.RunOutputCursor;
import io.haifa.agent.runtime.api.RuntimeCommand;
import io.haifa.agent.runtime.api.RuntimeCommandResult;
import io.haifa.agent.runtime.api.RuntimeCommandStatus;
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
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("slow")
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
        Path plaintextDatabase = directory.resolve("plaintext.db");
        try (ProjectPersistenceAssembly plaintext = ProjectPersistenceAssembly.open(
                ProjectPersistenceConfiguration.sqliteUnprotected(plaintextDatabase), CLOCK, ids, null)) {
            assertThat(plaintext.mode()).isEqualTo(ProjectPersistenceMode.SQLITE);
        }
        assertThatThrownBy(() -> ProjectPersistenceMode.parse("JSONL"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MEMORY, SQLITE, or SQLITE_WITH_JSONL");
        assertThatThrownBy(() -> ProjectPersistenceConfiguration.sqlite(Path.of("relative.db"), "env://TEST_KEY"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("database path must be absolute");
        assertThatThrownBy(() -> new ProjectPersistenceConfiguration(
                        ProjectPersistenceMode.SQLITE,
                        ProjectPersistenceProtection.NONE,
                        Optional.of(directory.resolve("none-with-key.db")),
                        Optional.empty(),
                        Optional.of("env://TEST_KEY"),
                        5_000,
                        1_048_576))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not accept a protector reference");
        assertThatThrownBy(() -> new ProjectPersistenceConfiguration(
                        ProjectPersistenceMode.SQLITE,
                        ProjectPersistenceProtection.AES_GCM,
                        Optional.of(directory.resolve("aes-without-key.db")),
                        Optional.empty(),
                        Optional.empty(),
                        5_000,
                        1_048_576))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires a protector reference");
        assertThatThrownBy(() -> ProjectPersistenceAssembly.open(
                        ProjectPersistenceConfiguration.sqlite(
                                directory.resolve("missing-protector.db"), "env://TEST_KEY"),
                        CLOCK,
                        ids,
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("durable continuation protector");
        assertThat(Files.deleteIfExists(database)).isTrue();
        assertThat(Files.deleteIfExists(plaintextDatabase)).isTrue();
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
    void codingSessionQueueIsEncryptedOrderedAndRecoverableAfterApplicationRebuild() throws Exception {
        Path database = directory.resolve("coding-session.db");
        ProductFixture fixture = productFixture();
        AgentSessionId sessionId;
        AgentRunId activeRunId = new AgentRunId("active-run");
        try (ProjectPersistenceAssembly first = ProjectPersistenceAssembly.open(
                ProjectPersistenceConfiguration.sqlite(database, "env://TEST_KEY"),
                CLOCK,
                new TestIds("first"),
                protector())) {
            ProjectProductService service = fixture.service(first, new TestIds("service"));
            sessionId =
                    service.start(fixture.projectId, "first turn", List.of()).sessionId();
            first.codingSessions()
                    .createActivity(new CodingSessionActivity(
                            sessionId,
                            fixture.projectId,
                            TENANT,
                            PRINCIPAL,
                            "first turn",
                            io.haifa.agent.core.session.AgentSessionStatus.ACTIVE,
                            Optional.of(activeRunId),
                            OptionalLong.of(3),
                            Optional.empty(),
                            NOW,
                            NOW,
                            0));
            first.codingSessions().enqueue(followUp("follow-1", sessionId, activeRunId, "secret queued turn", "key-1"));
            first.codingSessions().enqueue(followUp("follow-2", sessionId, activeRunId, "second queued turn", "key-2"));
            first.codingSessions().createModelPreference(CodingModelPreference.initial(sessionId, "deepseek", NOW));
            first.codingSessions()
                    .changeModel(
                            sessionId, 0, "bailian", "model-key-digest", "model-request-digest", NOW.plusSeconds(1));

            byte[] databaseBytes = Files.readAllBytes(database);
            assertThat(new String(databaseBytes, java.nio.charset.StandardCharsets.UTF_8))
                    .doesNotContain("secret queued turn", "second queued turn");
        }

        try (ProjectPersistenceAssembly reopened = ProjectPersistenceAssembly.open(
                ProjectPersistenceConfiguration.sqlite(database, "env://TEST_KEY"),
                CLOCK,
                new TestIds("second"),
                protector())) {
            assertThat(reopened.codingSessions()
                            .listActivities(TENANT, PRINCIPAL, fixture.projectId, CodingSessionQuery.firstPage(10)))
                    .extracting(CodingSessionActivity::sessionId)
                    .containsExactly(sessionId);
            assertThat(reopened.codingSessions().queuedCount(sessionId)).isEqualTo(2);
            assertThat(reopened.codingSessions().findModelPreference(sessionId))
                    .get()
                    .satisfies(preference -> {
                        assertThat(preference.modelId()).isEqualTo("bailian");
                        assertThat(preference.revision()).isEqualTo(1);
                    });
            CodingSessionActivity idle =
                    reopened.codingSessions().clearActive(sessionId, activeRunId, 0, NOW.plusSeconds(1));
            var claim = reopened.codingSessions()
                    .claimNextForDispatch(sessionId, idle.revision(), NOW.plusSeconds(2))
                    .orElseThrow();
            assertThat(claim.followUp().followUpId()).isEqualTo("follow-1");
            assertThat(claim.followUp().message()).isEqualTo("secret queued turn");
            assertThat(claim.followUp().status()).isEqualTo(CodingFollowUpStatus.CLAIMED);
            assertThatThrownBy(() -> reopened.codingSessions()
                            .restore("follow-1", claim.followUp().revision(), NOW.plusSeconds(3)))
                    .isInstanceOf(RuntimeException.class)
                    .hasRootCauseMessage("follow-up is already reserved for dispatch");
        }
    }

    @Test
    void codingSessionQueueCanUseExplicitPlaintextProtectionAndRecoverWithoutAKey() throws Exception {
        Path database = directory.resolve("coding-session-plaintext.db");
        ProductFixture fixture = productFixture();
        AgentSessionId sessionId;
        AgentRunId activeRunId = new AgentRunId("plaintext-active-run");
        try (ProjectPersistenceAssembly first = ProjectPersistenceAssembly.open(
                ProjectPersistenceConfiguration.sqliteUnprotected(database),
                CLOCK,
                new TestIds("plaintext-first"),
                null)) {
            ProjectProductService service = fixture.service(first, new TestIds("plaintext-service"));
            sessionId =
                    service.start(fixture.projectId, "first turn", List.of()).sessionId();
            first.codingSessions()
                    .createActivity(new CodingSessionActivity(
                            sessionId,
                            fixture.projectId,
                            TENANT,
                            PRINCIPAL,
                            "first turn",
                            io.haifa.agent.core.session.AgentSessionStatus.ACTIVE,
                            Optional.of(activeRunId),
                            OptionalLong.of(1),
                            Optional.empty(),
                            NOW,
                            NOW,
                            0));
            first.codingSessions()
                    .enqueue(followUp(
                            "plaintext-follow-up",
                            sessionId,
                            activeRunId,
                            "readable local queued turn",
                            "plaintext-key"));
        }

        assertThat(new String(Files.readAllBytes(database), java.nio.charset.StandardCharsets.UTF_8))
                .contains("readable local queued turn");
        try (ProjectPersistenceAssembly reopened = ProjectPersistenceAssembly.open(
                ProjectPersistenceConfiguration.sqliteUnprotected(database),
                CLOCK,
                new TestIds("plaintext-second"),
                null)) {
            assertThat(reopened.codingSessions().queuedCount(sessionId)).isEqualTo(1);
        }
    }

    @Test
    void codingSessionFacadeKeepsOneActiveRunAndDispatchesFollowUpAfterSettlement() {
        ProductFixture fixture = productFixture();
        CapturingRuntime runtime = new CapturingRuntime();
        TestIds ids = new TestIds("coding");
        try (ProjectPersistenceAssembly assembly =
                ProjectPersistenceAssembly.open(ProjectPersistenceConfiguration.memory(), CLOCK, ids, null)) {
            CodingSessionService coding = fixture.codingService(assembly, runtime, ids);
            var created = coding.createSession(fixture.projectId, "inspect the project", List.of(), "create-key");
            var retried = coding.createSession(fixture.projectId, "inspect the project", List.of(), "create-key");
            AgentSessionId sessionId = created.summary().sessionId();
            AgentRunId firstRunId = created.activeRun().orElseThrow().runId();

            assertThat(retried.summary().sessionId()).isEqualTo(sessionId);
            assertThat(retried.activeRun().orElseThrow().runId()).isEqualTo(firstRunId);
            assertThat(retried.activeRunTaskSummary()).contains("inspect the project");
            assertThat(coding.listSessions(fixture.projectId, CodingSessionQuery.firstPage(10))
                            .items())
                    .extracting(value -> value.sessionId())
                    .containsExactly(sessionId);
            assertThatThrownBy(() -> coding.submitTurn(sessionId, "another turn", List.of(), "submit-key"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("active");

            RunInputReceipt steer = coding.steer(sessionId, firstRunId, "check tests too", "steer-key");
            assertThat(steer.status()).isEqualTo(RunInputReceiptStatus.ACCEPTED);
            var queued = coding.enqueueFollowUp(sessionId, firstRunId, "then update the docs", List.of(), "follow-key");
            var queuedRetry =
                    coding.enqueueFollowUp(sessionId, firstRunId, "then update the docs", List.of(), "follow-key");
            assertThat(queuedRetry.followUpId()).isEqualTo(queued.followUpId());
            assertThatThrownBy(() ->
                            coding.enqueueFollowUp(sessionId, firstRunId, "different request", List.of(), "follow-key"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("another request");

            runtime.settle(firstRunId);
            var reconciled = coding.reconcileSession(sessionId);
            assertThat(reconciled.activeRun()).isPresent();
            assertThat(reconciled.activeRun().orElseThrow().runId()).isNotEqualTo(firstRunId);
            assertThat(reconciled.activeRunTaskSummary()).contains("then update the docs");
            assertThat(reconciled.summary().queuedCount()).isZero();

            RuntimeCommandResult aborted = coding.abortActiveRun(sessionId, "abort-key");
            assertThat(aborted.status()).isEqualTo(RuntimeCommandStatus.ACCEPTED);
        }
    }

    @Test
    void codingSessionCommandIsFrozenIdempotentAndSurvivesSqliteReopen() {
        Path database = directory.resolve("coding-delivery-intent.db");
        ProductFixture fixture = productFixture();
        AgentRunId runId;
        try (ProjectPersistenceAssembly assembly = ProjectPersistenceAssembly.open(
                ProjectPersistenceConfiguration.sqliteUnprotected(database),
                CLOCK,
                new TestIds("delivery-first"),
                null)) {
            CodingSessionService coding =
                    fixture.codingService(assembly, new CapturingRuntime(), new TestIds("delivery-service"));
            var created = coding.createSession(
                    fixture.projectId, "implement and open a pull request", List.of(), "delivery-key");
            runId = created.activeRun().orElseThrow().runId();

            assertThat(assembly.codingSessions().findCommandByRunId(runId))
                    .get()
                    .extracting(CodingCommandBinding::message)
                    .isEqualTo("implement and open a pull request");
            assertThat(coding.createSession(
                                    fixture.projectId, "implement and open a pull request", List.of(), "delivery-key")
                            .activeRun()
                            .orElseThrow()
                            .runId())
                    .isEqualTo(runId);
            assertThatThrownBy(() -> coding.createSession(
                            fixture.projectId,
                            "a different prompt with same idempotency key",
                            List.of(),
                            "delivery-key"))
                    .isInstanceOf(RuntimeException.class)
                    .hasRootCauseMessage("idempotency key is bound to another request");
        }

        try (ProjectPersistenceAssembly reopened = ProjectPersistenceAssembly.open(
                ProjectPersistenceConfiguration.sqliteUnprotected(database),
                CLOCK,
                new TestIds("delivery-second"),
                null)) {
            assertThat(reopened.codingSessions().findCommandByRunId(runId))
                    .get()
                    .extracting(CodingCommandBinding::message)
                    .isEqualTo("implement and open a pull request");
        }
    }

    @Test
    void createSessionRejectsUnreadyModelBeforeStartingRun() {
        CodingModelOption unready =
                unreadyModel("unready-model", "Unready Model", CodingModelState.Connection.LOGIN_REQUIRED);
        CodingModelOption ready = readyModel("ready-model", "Ready Model");
        ProductFixture fixture = productFixture();
        CapturingRuntime runtime = new CapturingRuntime();
        TestIds ids = new TestIds("readiness");

        try (ProjectPersistenceAssembly assembly =
                ProjectPersistenceAssembly.open(ProjectPersistenceConfiguration.memory(), CLOCK, ids, null)) {
            CodingSessionService service = fixture.codingService(assembly, runtime, ids, testCatalog(unready, ready));

            assertThatThrownBy(
                            () -> service.createSession(fixture.projectId, "draft prompt", List.of(), "idempotency-1"))
                    .isInstanceOf(ProjectProductException.class)
                    .satisfies(ex -> {
                        ProjectProductException ppe = (ProjectProductException) ex;
                        assertThat(ppe.code()).isEqualTo("MODEL_AUTHENTICATION_REQUIRED");
                    });

            assertThat(runtime.startCount()).isZero();
            assertThat(service.listSessions(fixture.projectId, CodingSessionQuery.firstPage(10))
                            .items())
                    .isEmpty();
        }
    }

    @Test
    void createSessionWithExplicitReadyModelSucceeds() {
        CodingModelOption unready =
                unreadyModel("unready-model", "Unready Model", CodingModelState.Connection.LOGIN_REQUIRED);
        CodingModelOption ready = readyModel("ready-model", "Ready Model");
        ProductFixture fixture = productFixture();
        CapturingRuntime runtime = new CapturingRuntime();
        TestIds ids = new TestIds("readiness");

        try (ProjectPersistenceAssembly assembly =
                ProjectPersistenceAssembly.open(ProjectPersistenceConfiguration.memory(), CLOCK, ids, null)) {
            CodingSessionService service = fixture.codingService(assembly, runtime, ids, testCatalog(unready, ready));

            CodingSessionCreateOptions options = CodingSessionCreateOptions.withInitialModel("ready-model");
            CodingSessionView created =
                    service.createSession(fixture.projectId, "draft prompt", List.of(), "idempotency-1", options);

            assertThat(created.activeRun()).isPresent();
            assertThat(runtime.startCount()).isEqualTo(1);
        }
    }

    @Test
    void selectModelAllowsUnreadyModelButSubmitTurnRejectsIt() {
        CodingModelOption ready = readyModel("ready-model", "Ready Model");
        CodingModelOption unready =
                unreadyModel("unready-model", "Unready Model", CodingModelState.Connection.LOGIN_REQUIRED);
        ProductFixture fixture = productFixture();
        CapturingRuntime runtime = new CapturingRuntime();
        TestIds ids = new TestIds("readiness");

        try (ProjectPersistenceAssembly assembly =
                ProjectPersistenceAssembly.open(ProjectPersistenceConfiguration.memory(), CLOCK, ids, null)) {
            CodingSessionService service = fixture.codingService(assembly, runtime, ids, testCatalog(ready, unready));

            CodingSessionView created =
                    service.createSession(fixture.projectId, "first prompt", List.of(), "create-key");
            AgentSessionId sessionId = created.summary().sessionId();
            AgentRunId firstRunId = created.activeRun().orElseThrow().runId();
            assertThat(runtime.startCount()).isEqualTo(1);

            runtime.settle(firstRunId);
            service.reconcileSession(sessionId);

            CodingModelSelection selection = service.selectModel(sessionId, "unready-model", 0, "select-key");
            assertThat(selection.model().id()).isEqualTo("unready-model");
            assertThat(selection.model().state().connection()).isEqualTo(CodingModelState.Connection.LOGIN_REQUIRED);

            assertThatThrownBy(() -> service.submitTurn(sessionId, "second turn", List.of(), "turn-key"))
                    .isInstanceOf(ProjectProductException.class)
                    .satisfies(ex -> {
                        ProjectProductException ppe = (ProjectProductException) ex;
                        assertThat(ppe.code()).isEqualTo("MODEL_AUTHENTICATION_REQUIRED");
                    });

            assertThat(runtime.startCount()).isEqualTo(1);

            service.selectModel(sessionId, "ready-model", 1, "select-back-key");
            var receipt = service.submitTurn(sessionId, "second turn", List.of(), "turn-key-2");
            assertThat(receipt.operation()).isEqualTo("submit-turn");
            assertThat(runtime.startCount()).isEqualTo(2);
        }
    }

    @Test
    void submitTurnIdempotentRetrySucceedsEvenIfModelBecomesUnready() {
        CodingModelOption ready = readyModel("dynamic-model", "Dynamic Model");
        CodingModelOption unready =
                unreadyModel("dynamic-model", "Dynamic Model", CodingModelState.Connection.LOGIN_REQUIRED);
        java.util.concurrent.atomic.AtomicReference<CodingModelOption> currentModel =
                new java.util.concurrent.atomic.AtomicReference<>(ready);
        ProductFixture fixture = productFixture();
        CapturingRuntime runtime = new CapturingRuntime();
        TestIds ids = new TestIds("readiness-idempotency");

        CodingModelCatalog dynamicCatalog = new CodingModelCatalog() {
            @Override
            public String defaultModelId() {
                return "dynamic-model";
            }

            @Override
            public List<CodingModelOption> available(TenantRef tenant, PrincipalRef principal) {
                return List.of(currentModel.get());
            }

            @Override
            public Optional<CodingModelOption> find(TenantRef tenant, PrincipalRef principal, String modelId) {
                return Optional.of(currentModel.get()).filter(m -> m.id().equals(modelId));
            }
        };

        try (ProjectPersistenceAssembly assembly =
                ProjectPersistenceAssembly.open(ProjectPersistenceConfiguration.memory(), CLOCK, ids, null)) {
            CodingSessionService service = fixture.codingService(assembly, runtime, ids, dynamicCatalog);

            CodingSessionView created =
                    service.createSession(fixture.projectId, "first prompt", List.of(), "create-key");
            AgentSessionId sessionId = created.summary().sessionId();
            AgentRunId firstRunId = created.activeRun().orElseThrow().runId();
            runtime.settle(firstRunId);
            service.reconcileSession(sessionId);

            var receipt = service.submitTurn(sessionId, "second turn", List.of(), "turn-idempotent-key");
            assertThat(receipt.replayed()).isFalse();
            assertThat(runtime.startCount()).isEqualTo(2);

            currentModel.set(unready);

            var retried = service.submitTurn(sessionId, "second turn", List.of(), "turn-idempotent-key");
            assertThat(retried.replayed()).isTrue();
            assertThat(retried.runId()).isEqualTo(receipt.runId());
            assertThat(runtime.startCount()).isEqualTo(2);
        }
    }

    @Test
    void createSessionIdempotentRetrySucceedsEvenIfModelBecomesUnready() {
        CodingModelOption ready = readyModel("dynamic-model", "Dynamic Model");
        CodingModelOption unready =
                unreadyModel("dynamic-model", "Dynamic Model", CodingModelState.Connection.LOGIN_REQUIRED);
        java.util.concurrent.atomic.AtomicReference<CodingModelOption> currentModel =
                new java.util.concurrent.atomic.AtomicReference<>(ready);
        ProductFixture fixture = productFixture();
        CapturingRuntime runtime = new CapturingRuntime();
        TestIds ids = new TestIds("create-idempotency");

        CodingModelCatalog dynamicCatalog = new CodingModelCatalog() {
            @Override
            public String defaultModelId() {
                return "dynamic-model";
            }

            @Override
            public List<CodingModelOption> available(TenantRef tenant, PrincipalRef principal) {
                return List.of(currentModel.get());
            }

            @Override
            public Optional<CodingModelOption> find(TenantRef tenant, PrincipalRef principal, String modelId) {
                return Optional.of(currentModel.get()).filter(m -> m.id().equals(modelId));
            }
        };

        try (ProjectPersistenceAssembly assembly =
                ProjectPersistenceAssembly.open(ProjectPersistenceConfiguration.memory(), CLOCK, ids, null)) {
            CodingSessionService service = fixture.codingService(assembly, runtime, ids, dynamicCatalog);

            var created = service.createSession(fixture.projectId, "first prompt", List.of(), "create-idempotent-key");
            AgentSessionId sessionId = created.summary().sessionId();
            AgentRunId runId = created.activeRun().orElseThrow().runId();
            assertThat(runtime.startCount()).isEqualTo(1);

            currentModel.set(unready);

            var retried = service.createSession(fixture.projectId, "first prompt", List.of(), "create-idempotent-key");
            assertThat(retried.summary().sessionId()).isEqualTo(sessionId);
            assertThat(retried.activeRun().orElseThrow().runId()).isEqualTo(runId);
            assertThat(runtime.startCount()).isEqualTo(1);
        }
    }

    private static CodingModelCatalog testCatalog(CodingModelOption defaultModel, CodingModelOption... others) {
        List<CodingModelOption> all = new java.util.ArrayList<>();
        all.add(defaultModel);
        all.addAll(List.of(others));
        return new CodingModelCatalog() {
            @Override
            public String defaultModelId() {
                return defaultModel.id();
            }

            @Override
            public List<CodingModelOption> available(TenantRef tenant, PrincipalRef principal) {
                return all;
            }

            @Override
            public Optional<CodingModelOption> find(TenantRef tenant, PrincipalRef principal, String modelId) {
                return all.stream().filter(m -> m.id().equals(modelId)).findFirst();
            }
        };
    }

    private static CodingModelOption readyModel(String id, String name) {
        return new CodingModelOption(
                id,
                name,
                "provider-1",
                "Provider 1",
                java.util.Set.of("TEXT_CHAT", "TOOL_CALLING"),
                128_000,
                16_000,
                new CodingModelState(
                        CodingModelState.Connection.CONNECTED,
                        CodingModelState.BindingAvailability.AVAILABLE,
                        CodingModelState.RuntimeStatus.NORMAL,
                        CodingModelState.RunScope.IDLE),
                "",
                CodingModelControls.unavailable(),
                CodingModelPreferences.recommended(),
                Optional.empty());
    }

    private static CodingModelOption unreadyModel(String id, String name, CodingModelState.Connection connection) {
        return new CodingModelOption(
                id,
                name,
                "provider-1",
                "Provider 1",
                java.util.Set.of("TEXT_CHAT", "TOOL_CALLING"),
                128_000,
                16_000,
                new CodingModelState(
                        connection,
                        CodingModelState.BindingAvailability.AVAILABLE,
                        CodingModelState.RuntimeStatus.NORMAL,
                        CodingModelState.RunScope.IDLE),
                "",
                CodingModelControls.unavailable(),
                CodingModelPreferences.recommended(),
                Optional.empty());
    }

    @Test
    void codingSessionLifecycleUsesCoreAuthorityAndLogicalDelete() {
        ProductFixture fixture = productFixture();
        CapturingRuntime runtime = new CapturingRuntime();
        TestIds ids = new TestIds("lifecycle");
        try (ProjectPersistenceAssembly assembly =
                ProjectPersistenceAssembly.open(ProjectPersistenceConfiguration.memory(), CLOCK, ids, null)) {
            CodingSessionService coding = fixture.codingService(assembly, runtime, ids);
            var created = coding.createSession(fixture.projectId, "initial title", List.of(), "create-lifecycle");
            AgentSessionId sessionId = created.summary().sessionId();
            AgentRunId runId = created.activeRun().orElseThrow().runId();
            runtime.settle(runId);
            var idle = coding.reconcileSession(sessionId);

            var renamed = coding.renameSession(
                    sessionId, "renamed session", idle.summary().revision());
            assertThat(renamed.displayName()).isEqualTo("renamed session");
            assertThat(coding.listSessions(
                                    fixture.projectId,
                                    new CodingSessionQuery(Optional.of(sessionId.value()), Optional.empty(), 10))
                            .items())
                    .extracting(value -> value.sessionId())
                    .containsExactly(sessionId);
            var archived = coding.archiveSession(sessionId, renamed.revision());
            assertThat(archived.status()).isEqualTo(io.haifa.agent.core.session.AgentSessionStatus.ARCHIVED);
            assertThat(assembly.ports().sessions().find(sessionId).orElseThrow().status())
                    .isEqualTo(io.haifa.agent.core.session.AgentSessionStatus.ARCHIVED);

            coding.deleteSession(sessionId, archived.revision());
            assertThat(assembly.ports().sessions().find(sessionId).orElseThrow().status())
                    .isEqualTo(io.haifa.agent.core.session.AgentSessionStatus.DELETED);
            assertThat(coding.listSessions(fixture.projectId, CodingSessionQuery.firstPage(10))
                            .items())
                    .isEmpty();
            assertThat(runtime.find(runId)).isPresent();
        }
    }

    @Test
    void applicationPersistenceDoesNotExposePolicyStoresAfterRebuild() {
        Path database = directory.resolve("policy-recovery.db");
        try (ProjectPersistenceAssembly first = ProjectPersistenceAssembly.open(
                ProjectPersistenceConfiguration.sqlite(database, "env://TEST_KEY"),
                CLOCK,
                new TestIds("policy-first"),
                protector())) {
            assertThat(List.of(first.getClass().getMethods()))
                    .extracting(method -> method.getName())
                    .doesNotContain("policy");
        }

        try (ProjectPersistenceAssembly reopened = ProjectPersistenceAssembly.open(
                ProjectPersistenceConfiguration.sqlite(database, "env://TEST_KEY"),
                CLOCK,
                new TestIds("policy-second"),
                protector())) {
            assertThat(List.of(reopened.getClass().getMethods()))
                    .extracting(method -> method.getName())
                    .doesNotContain("policy");
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
    void sqliteBeginRetrySucceedsAfterLockReleaseAndFailsClearlyWhenExhausted() throws Exception {
        Path recoverableDatabase = directory.resolve("busy-recoverable.db");
        var recoverableConfiguration = new ProjectPersistenceConfiguration(
                ProjectPersistenceMode.SQLITE,
                Optional.of(recoverableDatabase),
                Optional.empty(),
                Optional.of("env://TEST_KEY"),
                100,
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

    private static CodingFollowUp followUp(
            String id, AgentSessionId sessionId, AgentRunId activeRunId, String message, String key) {
        return new CodingFollowUp(
                id,
                sessionId,
                activeRunId,
                message,
                List.of(),
                "sha256:" + key,
                "sha256:request-" + key,
                "dispatch-" + id,
                CodingFollowUpStatus.PENDING,
                1,
                Optional.empty(),
                NOW,
                NOW,
                0);
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
        workspaces.create(Workspace.provision(workspaceId, projectId, WorkspaceRevision.initial("root"), NOW)
                .activate(NOW));
        InMemoryProjectConfigurationStore configurations = new InMemoryProjectConfigurationStore();
        configurations.publish(ProjectConfiguration.create(
                new ProjectConfigurationId("config-1"),
                new ProjectConfigurationVersion("1"),
                workspaceId,
                "coding",
                "1",
                Set.of("file_read"),
                Set.of("file_read"),
                "policy-1"));
        return new ProductFixture(projectId, projects, workspaces, configurations);
    }

    private record ProductFixture(
            ProjectId projectId,
            InMemoryProjectStore projects,
            InMemoryWorkspaceStore workspaces,
            InMemoryProjectConfigurationStore configurations) {
        private ProjectProductService service(ProjectPersistenceAssembly persistence, IdentifierGenerator ids) {
            return service(persistence, ids, new CapturingRuntime());
        }

        private ProjectProductService service(
                ProjectPersistenceAssembly persistence, IdentifierGenerator ids, AgentRuntime runtime) {
            return new ProjectProductService(
                    projects,
                    workspaces,
                    new ProjectConfigurationService(configurations),
                    persistence.productSessions(),
                    persistence.projectSessionProvisioner(CLOCK),
                    () -> new TrustedProductCaller(TENANT, PRINCIPAL),
                    runtime,
                    ids,
                    new AgentDefinitionId("coding-agent"));
        }

        private CodingSessionService codingService(
                ProjectPersistenceAssembly persistence, AgentRuntime runtime, IdentifierGenerator ids) {
            return codingService(
                    persistence, runtime, ids, CodingModelCatalog.fixed("coding-default", "Configured model"));
        }

        private CodingSessionService codingService(
                ProjectPersistenceAssembly persistence,
                AgentRuntime runtime,
                IdentifierGenerator ids,
                CodingModelCatalog models) {
            var callers = (io.haifa.agent.application.project.product.TrustedProductCallerProvider)
                    () -> new TrustedProductCaller(TENANT, PRINCIPAL);
            return new CodingSessionService(
                    service(persistence, ids, runtime),
                    persistence.productSessions(),
                    persistence.codingSessions(),
                    persistence.codingSessionLifecycle(),
                    persistence.codingSessionCompactor(ids, new io.haifa.agent.common.time.TimeProvider() {
                        @Override
                        public java.time.Instant now() {
                            return NOW;
                        }
                    }),
                    callers,
                    runtime,
                    ids,
                    CLOCK,
                    models);
        }
    }

    private static final class CapturingRuntime implements AgentRuntime {
        private final AtomicInteger sequence = new AtomicInteger();
        private final Map<String, AgentRunSnapshot> byIdempotency = new java.util.concurrent.ConcurrentHashMap<>();
        private final Map<AgentRunId, AgentRunSnapshot> snapshots = new java.util.concurrent.ConcurrentHashMap<>();

        int startCount() {
            return sequence.get();
        }

        @Override
        public AgentRunSnapshot start(AgentRunRequest request) {
            AgentRunSnapshot snapshot = byIdempotency.computeIfAbsent(request.idempotencyKey(), ignored -> {
                var created = new AgentRunSnapshot(
                        new AgentRunId("captured-" + sequence.incrementAndGet()),
                        AgentRunStatus.RUNNING,
                        1,
                        NOW,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty());
                snapshots.put(created.runId(), created);
                return created;
            });
            return snapshot;
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
            AgentRunSnapshot current = snapshots.get(command.runId());
            AgentRunSnapshot cancelled = new AgentRunSnapshot(
                    current.runId(),
                    AgentRunStatus.CANCELLED,
                    current.version() + 1,
                    NOW,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty());
            snapshots.put(cancelled.runId(), cancelled);
            return new RuntimeCommandResult(command, RuntimeCommandStatus.ACCEPTED, cancelled);
        }

        @Override
        public Optional<AgentRunSnapshot> find(AgentRunId runId) {
            return Optional.ofNullable(snapshots.get(runId));
        }

        @Override
        public RunInputReceipt submitInput(RunInputSubmission input) {
            return new RunInputReceipt(
                    input.inputId(),
                    input.runId(),
                    RunInputReceiptStatus.ACCEPTED,
                    NOW,
                    Optional.empty(),
                    Optional.empty(),
                    java.util.OptionalInt.empty(),
                    Optional.empty());
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

        private void settle(AgentRunId runId) {
            AgentRunSnapshot current = snapshots.get(runId);
            snapshots.put(
                    runId,
                    new AgentRunSnapshot(
                            runId,
                            AgentRunStatus.COMPLETED,
                            current.version() + 1,
                            NOW,
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty()));
        }
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
