package io.haifa.agent.testing.transport;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.RunConfigurationSnapshotRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunBudget;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunLimits;
import io.haifa.agent.core.run.AgentRunSpec;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.run.AgentRunType;
import io.haifa.agent.core.session.AgentSession;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.session.SessionScope;
import io.haifa.agent.runtime.api.AgentRunEvent;
import io.haifa.agent.runtime.api.AgentRunHandle;
import io.haifa.agent.runtime.api.AgentRunListener;
import io.haifa.agent.runtime.api.AgentRunOutputEvent;
import io.haifa.agent.runtime.api.AgentRunOutputListener;
import io.haifa.agent.runtime.api.AgentRunRequest;
import io.haifa.agent.runtime.api.AgentRunSnapshot;
import io.haifa.agent.runtime.api.AgentRunViewSnapshot;
import io.haifa.agent.runtime.api.AgentRuntime;
import io.haifa.agent.runtime.api.InteractionResponse;
import io.haifa.agent.runtime.api.ResumeAgentRunRequest;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RunEventPage;
import io.haifa.agent.runtime.api.RunEventPayloads;
import io.haifa.agent.runtime.api.RunEventSubscription;
import io.haifa.agent.runtime.api.RunOutputCursor;
import io.haifa.agent.runtime.api.RuntimeCommand;
import io.haifa.agent.runtime.api.RuntimeCommandResult;
import io.haifa.agent.runtime.core.event.OpaqueRunEventCursorCodec;
import io.haifa.agent.runtime.core.storage.RuntimeEventAppender;
import io.haifa.agent.store.sqlite.SqliteStoreConfiguration;
import io.haifa.agent.store.sqlite.SqliteStoreFoundation;
import io.haifa.agent.transport.http.HaifaHttpTransportAdapter;
import io.haifa.agent.transport.http.HttpTransportConfiguration;
import io.haifa.agent.transport.http.HttpTransportRequest;
import io.haifa.agent.transport.http.RunEventCursorTokenCodec;
import io.haifa.agent.transport.http.RuntimeCallerScope;
import io.haifa.agent.transport.http.TrustedCallerContext;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteReconnectTransportTest {
    private static final AgentRunId RUN_ID = new AgentRunId("run-sqlite-feed");
    private static final AgentSessionId SESSION_ID = new AgentSessionId("session-sqlite-feed");
    private static final Instant NOW = Instant.parse("2026-07-26T00:00:00Z");

    @Test
    void opaqueCursorReconnectsAfterAdapterAndSqliteRuntimeAreRebuilt(@TempDir Path directory) throws Exception {
        Path database = directory.resolve("runtime.db").toAbsolutePath();
        var configuration = SqliteStoreConfiguration.defaults(database);
        try (var first = SqliteStoreFoundation.initialize(configuration, Clock.fixed(NOW, ZoneOffset.UTC))) {
            prepareRun(first);
            first.events().append(RUN_ID, "run.created", Map.of("version", 1L), NOW);
        }

        byte[] key = "task03-test-cursor-signing-key-32bytes!".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        RunEventCursorTokenCodec cursors = cursorCodec(new OpaqueRunEventCursorCodec(key));
        String cursor;
        try (var second = SqliteStoreFoundation.initialize(configuration, Clock.fixed(NOW, ZoneOffset.UTC))) {
            var adapter = adapter(new JournalRuntime(second.events()), cursors);
            var response = adapter.handle(get(Map.of()));
            assertThat(response.status()).isEqualTo(200);
            var body = new ObjectMapper().readTree(response.body());
            cursor = body.path("nextCursor").asText();
            assertThat(body.path("items")).hasSize(1);
        }

        try (var third = SqliteStoreFoundation.initialize(configuration, Clock.fixed(NOW, ZoneOffset.UTC))) {
            third.events().append(RUN_ID, "run.created", Map.of("version", 2L), NOW.plusSeconds(1));
            var adapter = adapter(new JournalRuntime(third.events()), cursors);
            var response = adapter.handle(get(Map.of("cursor", List.of(cursor))));
            var body = new ObjectMapper().readTree(response.body());
            assertThat(response.status()).isEqualTo(200);
            assertThat(body.path("items")).hasSize(1);
            assertThat(body.path("items").get(0).path("sequence").asLong()).isEqualTo(2);
            assertThat(body.path("items").get(0).path("payload").path("version").asLong())
                    .isEqualTo(2);
        }
    }

    private static HaifaHttpTransportAdapter adapter(AgentRuntime runtime, RunEventCursorTokenCodec cursors) {
        TrustedCallerContext caller = new TrustedCallerContext("tenant", "user", "alice", "coding");
        return new HaifaHttpTransportAdapter(
                runtime,
                request -> caller,
                (trusted, operation, runId, requestId) -> {},
                new RuntimeCallerScope() {
                    @Override
                    public <T> T call(TrustedCallerContext trusted, java.util.function.Supplier<T> operation) {
                        return operation.get();
                    }
                },
                cursors,
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                HttpTransportConfiguration.DEFAULT,
                () -> "sqlite-reconnect");
    }

    private static HttpTransportRequest get(Map<String, List<String>> query) {
        return new HttpTransportRequest("GET", "/v1/runs/" + RUN_ID.value() + "/events", Map.of(), query, new byte[0]);
    }

    private static RunEventCursorTokenCodec cursorCodec(OpaqueRunEventCursorCodec codec) {
        return new RunEventCursorTokenCodec() {
            @Override
            public String encode(RunEventCursor cursor) {
                return codec.encode(cursor);
            }

            @Override
            public RunEventCursor decode(AgentRunId expectedRunId, String token) {
                return codec.decode(token, expectedRunId, "1");
            }
        };
    }

    private static void prepareRun(SqliteStoreFoundation foundation) {
        foundation
                .agentSessions()
                .insert(AgentSession.open(
                        SESSION_ID,
                        new TenantRef("tenant"),
                        new PrincipalRef("alice", "user"),
                        null,
                        SessionScope.USER,
                        NOW,
                        Map.of()));
        try (var connection = foundation.connections().openConnection();
                var statement = connection.prepareStatement(
                        """
                            INSERT INTO configuration_snapshot (
                                configuration_ref, schema_version, definition_id, definition_version,
                                profile_id, profile_version, run_type, content_schema_version,
                                content_payload, content_hash, created_at
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """)) {
            statement.setString(1, "config-http");
            statement.setString(2, "1");
            statement.setString(3, "agent-http");
            statement.setString(4, "1.0.0");
            statement.setString(5, "coding");
            statement.setString(6, "1");
            statement.setString(7, "chat");
            statement.setString(8, "1");
            statement.setBytes(9, "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            statement.setString(10, "sha256:config-http");
            statement.setLong(11, NOW.toEpochMilli());
            statement.executeUpdate();
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException(exception);
        }
        AgentRunSpec specification = new AgentRunSpec(
                SESSION_ID,
                null,
                new TenantRef("tenant"),
                new PrincipalRef("alice", "user"),
                new AgentDefinitionId("agent-http"),
                new AgentDefinitionVersion(1, 0, 0),
                "coding",
                "1",
                AgentRunType.CHAT,
                "transport reconnect fixture",
                new AgentRunBudget(100, 100, 100, 10, 10, 2, "USD", 100),
                new AgentRunLimits(10, 2, 1, 60_000, 10_000),
                new RunConfigurationSnapshotRef("config-http", "sha256:config-http"));
        foundation.runs().insert(AgentRun.createRoot(RUN_ID, specification, NOW));
    }

    private static final class JournalRuntime implements AgentRuntime {
        private final RuntimeEventAppender journal;

        private JournalRuntime(RuntimeEventAppender journal) {
            this.journal = journal;
        }

        @Override
        public Optional<AgentRunViewSnapshot> view(AgentRunId runId) {
            return Optional.of(new AgentRunViewSnapshot(SESSION_ID, snapshot()));
        }

        @Override
        public RunEventPage events(AgentRunId runId, RunEventCursor after, int limit) {
            long requested = after.exclusiveSequence().orElse(0);
            var slice = journal.eventsAfter(runId, requested, OptionalLong.empty(), limit);
            var events = slice.events().stream()
                    .map(event -> new AgentRunEvent(
                            event.eventId(),
                            "run.accepted",
                            event.eventSchemaVersion(),
                            runId,
                            SESSION_ID,
                            event.sequence(),
                            new RunEventCursor(runId, "1", OptionalLong.of(event.sequence())),
                            event.occurredAt(),
                            event.correlationId(),
                            event.causationId(),
                            new RunEventPayloads.RunLifecycle(
                                    "QUEUED", ((Number) event.data().get("version")).longValue(), "NONE")))
                    .toList();
            RunEventCursor head = slice.headSequence().isPresent()
                    ? new RunEventCursor(
                            runId, "1", OptionalLong.of(slice.headSequence().getAsLong()))
                    : RunEventCursor.beforeFirst(runId);
            RunEventCursor next = events.isEmpty() ? after : events.getLast().cursor();
            return new RunEventPage(events, next, head, false);
        }

        @Override
        public AgentRunSnapshot start(AgentRunRequest request) {
            return snapshot();
        }

        @Override
        public AgentRunSnapshot resume(ResumeAgentRunRequest request) {
            return snapshot();
        }

        @Override
        public AgentRunSnapshot respond(InteractionResponse response) {
            return snapshot();
        }

        @Override
        public RuntimeCommandResult command(RuntimeCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<AgentRunSnapshot> find(AgentRunId runId) {
            return Optional.of(snapshot());
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

        @Override
        public RunEventSubscription subscribe(
                AgentRunId runId, RunEventCursor after, io.haifa.agent.runtime.api.AgentRunEventListener listener) {
            events(runId, after, 1_000).items().forEach(listener::onEvent);
            return new RunEventSubscription() {
                private boolean closed;

                @Override
                public boolean closed() {
                    return closed;
                }

                @Override
                public void close() {
                    closed = true;
                }
            };
        }

        private static AgentRunSnapshot snapshot() {
            return new AgentRunSnapshot(
                    RUN_ID, AgentRunStatus.QUEUED, 1, NOW, Optional.empty(), Optional.empty(), Optional.empty());
        }
    }
}
