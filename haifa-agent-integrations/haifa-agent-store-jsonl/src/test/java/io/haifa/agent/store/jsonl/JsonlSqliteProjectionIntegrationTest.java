package io.haifa.agent.store.jsonl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSession;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.session.SessionScope;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.runtime.api.AgentRunRequest;
import io.haifa.agent.runtime.api.RuntimeOverrides;
import io.haifa.agent.runtime.core.RuntimeCoreBuilder;
import io.haifa.agent.runtime.core.execution.ManualExecutionScheduler;
import io.haifa.agent.runtime.core.model.continuation.AesGcmModelContinuationProtector;
import io.haifa.agent.runtime.core.storage.RuntimePersistencePorts;
import io.haifa.agent.store.sqlite.SqliteStoreConfiguration;
import io.haifa.agent.store.sqlite.SqliteStoreFoundation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonlSqliteProjectionIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-07-25T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final TimeProvider TIME = () -> NOW;
    private static final AgentSessionId SESSION_ID = new AgentSessionId("jsonl-sqlite-session");
    private static final byte[] PROTECTOR_KEY = new byte[32];

    @TempDir
    Path directory;

    @Test
    void projectsRealSqliteOutboxAndJsonlLossCannotAffectRuntimeRecovery() throws Exception {
        Path database = directory.resolve("runtime.db");
        Path transcripts = directory.resolve("transcripts");
        AgentRunId runId;
        RuntimePersistencePorts closedPorts;
        try (SqliteStoreFoundation foundation = SqliteStoreFoundation.initialize(
                new SqliteStoreConfiguration(database.toAbsolutePath(), 1_250, 8_192), CLOCK)) {
            RuntimePersistencePorts ports = foundation.persistencePorts(protector());
            closedPorts = ports;
            ensureSession(ports);
            ManualExecutionScheduler scheduler = new ManualExecutionScheduler();
            var runtime = new RuntimeCoreBuilder()
                    .registerChatModel("openai-compatible", "1.0.0", finalModel())
                    .scheduler(scheduler)
                    .persistence(ports)
                    .identifierGenerator(new TestIds())
                    .timeProvider(TIME)
                    .workerId("jsonl-test-worker")
                    .build();
            runId = runtime.start(request()).runId();

            JsonlTranscriptProjector projector = projector(ports, transcripts);
            assertThat(projector.projectPending()).isEqualTo(2);
            assertThat(ports.outbox().pending()).isEmpty();
            assertThat(new JsonlTranscriptReader(transcripts)
                            .read(runId.value())
                            .events())
                    .extracting(SafeTranscriptEvent::eventType)
                    .containsExactly("run.created", "run.queued");

            Files.delete(new JsonlTranscriptWriter(transcripts).transcriptPath(runId.value()));
            assertThat(ports.runs().find(runId)).isPresent();
        }

        try (SqliteStoreFoundation reopened = SqliteStoreFoundation.initialize(
                new SqliteStoreConfiguration(database.toAbsolutePath(), 1_250, 8_192), CLOCK)) {
            assertThat(reopened.persistencePorts(protector()).runs().find(runId))
                    .isPresent();
        }

        assertThatThrownBy(() -> projector(closedPorts, transcripts).projectPending())
                .isInstanceOf(TranscriptProjectionException.class)
                .extracting(exception -> ((TranscriptProjectionException) exception).code())
                .isEqualTo(TranscriptDiagnosticCode.SOURCE_UNAVAILABLE);
    }

    @Test
    void sqliteCommitSurvivesCrashBeforeJsonlAndReopenedProjectorDrainsOutbox() throws Exception {
        Path database = directory.resolve("crash-before-jsonl.db");
        Path transcripts = directory.resolve("crash-before-jsonl-transcripts");
        AgentRunId runId;
        try (SqliteStoreFoundation foundation = SqliteStoreFoundation.initialize(
                new SqliteStoreConfiguration(database.toAbsolutePath(), 1_250, 8_192), CLOCK)) {
            RuntimePersistencePorts ports = foundation.persistencePorts(protector());
            ensureSession(ports);
            var runtime = new RuntimeCoreBuilder()
                    .registerChatModel("openai-compatible", "1.0.0", finalModel())
                    .scheduler(new ManualExecutionScheduler())
                    .persistence(ports)
                    .identifierGenerator(new TestIds())
                    .timeProvider(TIME)
                    .workerId("jsonl-crash-worker")
                    .build();
            runId = runtime.start(request()).runId();
            var failingWriter = new JsonlTranscriptWriter(transcripts, new TranscriptWriteHook() {
                @Override
                public void beforeWrite(SafeTranscriptEvent event) {
                    throw new IllegalStateException("injected crash before JSONL");
                }
            });
            var failing = new JsonlTranscriptProjector(
                    ports.outbox(),
                    ports.unitOfWork(),
                    SafeTranscriptMapperRegistry.defaults(),
                    new TranscriptRedactor(),
                    failingWriter);

            assertThatThrownBy(failing::projectPending).hasMessage("injected crash before JSONL");
            assertThat(ports.runs().find(runId)).isPresent();
            assertThat(ports.outbox().pending()).hasSize(2);
        }

        try (SqliteStoreFoundation reopened = SqliteStoreFoundation.initialize(
                new SqliteStoreConfiguration(database.toAbsolutePath(), 1_250, 8_192), CLOCK)) {
            RuntimePersistencePorts ports = reopened.persistencePorts(protector());
            assertThat(ports.runs().find(runId)).isPresent();
            assertThat(projector(ports, transcripts).projectPending()).isEqualTo(2);
            assertThat(new JsonlTranscriptReader(transcripts)
                            .read(runId.value())
                            .events())
                    .extracting(SafeTranscriptEvent::eventType)
                    .containsExactly("run.created", "run.queued");
            Path transcript = new JsonlTranscriptWriter(transcripts).transcriptPath(runId.value());
            Files.writeString(
                    transcript,
                    "{\"schemaVersion\":",
                    java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.APPEND);
            assertThat(new JsonlTranscriptReader(transcripts)
                            .read(runId.value())
                            .truncatedTail())
                    .isTrue();
            assertThat(ports.runs().find(runId)).isPresent();
        }
    }

    @Test
    void deletingSqliteNeverAllowsJsonlToBecomeARecoverySource() throws Exception {
        Path database = directory.resolve("deleted-source.db");
        Path transcripts = directory.resolve("orphaned-transcripts");
        AgentRunId runId;
        try (SqliteStoreFoundation foundation = SqliteStoreFoundation.initialize(
                new SqliteStoreConfiguration(database.toAbsolutePath(), 1_250, 8_192), CLOCK)) {
            RuntimePersistencePorts ports = foundation.persistencePorts(protector());
            ensureSession(ports);
            var runtime = new RuntimeCoreBuilder()
                    .registerChatModel("openai-compatible", "1.0.0", finalModel())
                    .scheduler(new ManualExecutionScheduler())
                    .persistence(ports)
                    .identifierGenerator(new TestIds())
                    .timeProvider(TIME)
                    .workerId("jsonl-orphan-worker")
                    .build();
            runId = runtime.start(request()).runId();
            assertThat(projector(ports, transcripts).projectPending()).isEqualTo(2);
        }
        try (var files = Files.list(directory)) {
            for (Path path : files.filter(path -> path.getFileName().toString().startsWith("deleted-source.db"))
                    .toList()) {
                Files.deleteIfExists(path);
            }
        }
        assertThat(new JsonlTranscriptReader(transcripts).read(runId.value()).events())
                .isNotEmpty();

        try (SqliteStoreFoundation empty = SqliteStoreFoundation.initialize(
                new SqliteStoreConfiguration(database.toAbsolutePath(), 1_250, 8_192), CLOCK)) {
            RuntimePersistencePorts ports = empty.persistencePorts(protector());
            var runtime = new RuntimeCoreBuilder()
                    .registerChatModel("openai-compatible", "1.0.0", finalModel())
                    .scheduler(new ManualExecutionScheduler())
                    .persistence(ports)
                    .identifierGenerator(new TestIds())
                    .timeProvider(TIME)
                    .workerId("jsonl-must-not-recover")
                    .build();

            assertThat(ports.runs().find(runId)).isEmpty();
            assertThatThrownBy(() -> runtime.recover(runId))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unknown run");
        }
    }

    private static JsonlTranscriptProjector projector(RuntimePersistencePorts ports, Path root) {
        return new JsonlTranscriptProjector(
                ports.outbox(),
                ports.unitOfWork(),
                SafeTranscriptMapperRegistry.defaults(),
                new TranscriptRedactor(),
                new JsonlTranscriptWriter(root));
    }

    private static void ensureSession(RuntimePersistencePorts ports) {
        ports.unitOfWork().execute(() -> {
            ports.sessions()
                    .insert(AgentSession.open(
                            SESSION_ID,
                            new TenantRef("local"),
                            new PrincipalRef("local-user", "user"),
                            null,
                            SessionScope.USER,
                            NOW,
                            Map.of()));
            return null;
        });
    }

    private static AgentRunRequest request() {
        return new AgentRunRequest(
                "jsonl-sqlite-start",
                new AgentDefinitionId("jsonl-sqlite-agent"),
                Optional.empty(),
                "jsonl-sqlite-profile",
                SESSION_ID,
                Optional.empty(),
                "project a safe transcript",
                List.of(),
                RuntimeOverrides.NONE);
    }

    private static AgentChatModel finalModel() {
        return ignored -> new AgentChatResponse(
                "response-final",
                "test-model",
                "not executed by this test",
                List.of(),
                ModelFinishReason.STOP,
                ModelUsage.unpriced(1, 1),
                "",
                Map.of());
    }

    private static AesGcmModelContinuationProtector protector() {
        return new AesGcmModelContinuationProtector(new SecretKeySpec(PROTECTOR_KEY, "AES"), new SecureRandom());
    }

    private static final class TestIds implements IdentifierGenerator {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public String nextValue() {
            return "jsonl-sqlite-" + sequence.incrementAndGet();
        }
    }
}
