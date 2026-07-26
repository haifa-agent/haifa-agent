package io.haifa.agent.transport.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunStatus;
import io.haifa.agent.core.session.AgentSessionId;
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
import io.haifa.agent.runtime.api.InteractionView;
import io.haifa.agent.runtime.api.ResumeAgentRunRequest;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RunEventPage;
import io.haifa.agent.runtime.api.RunEventPayloads;
import io.haifa.agent.runtime.api.RunEventSubscription;
import io.haifa.agent.runtime.api.RunInputReceipt;
import io.haifa.agent.runtime.api.RunInputSubmission;
import io.haifa.agent.runtime.api.RunOutputCursor;
import io.haifa.agent.runtime.api.RuntimeCommand;
import io.haifa.agent.runtime.api.RuntimeCommandResult;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HaifaHttpTransportAdapterTest {
    private static final Instant NOW = Instant.parse("2026-07-26T00:00:00Z");
    private static final AgentRunId RUN_ID = new AgentRunId("run-http-1");
    private static final AgentSessionId SESSION_ID = new AgentSessionId("session-http-1");
    private static final TrustedCallerContext CALLER = new TrustedCallerContext("tenant-a", "user", "alice", "coding");

    @Test
    void routesStartQueryAndEventPageThroughTrustedBoundaries() {
        StubRuntime runtime = new StubRuntime();
        AtomicInteger scopedCalls = new AtomicInteger();
        List<RunOperation> authorized = new ArrayList<>();
        HaifaHttpTransportAdapter adapter = adapter(
                runtime,
                request -> CALLER,
                (caller, operation, run, interaction) -> authorized.add(operation),
                new HttpTransportConfiguration("1.0", 8_192, 10, 100, 4, Duration.ofMillis(10)),
                scopedCalls);

        HttpTransportResponse started = adapter.handle(
                jsonRequest(
                        "POST",
                        "/v1/runs",
                        """
                {
                  "idempotencyKey":"start-1",
                  "agentDefinitionId":"agent-1",
                  "productProfileId":"coding",
                  "sessionId":"session-http-1",
                  "objective":"safe objective",
                  "inputs":[]
                }
                """));
        HttpTransportResponse queried = adapter.handle(get("/v1/runs/run-http-1", Map.of()));
        HttpTransportResponse events =
                adapter.handle(get("/v1/runs/run-http-1/events", Map.of("limit", List.of("10"))));

        assertThat(started.status()).isEqualTo(202);
        assertThat(started.bodyUtf8()).contains("\"runId\":\"run-http-1\"").contains("\"baselineCursor\":\"opaque-");
        assertThat(queried.status()).isEqualTo(200);
        assertThat(events.status()).isEqualTo(200);
        assertThat(events.bodyUtf8()).contains("\"eventType\":\"run.accepted\"").contains("\"cursor\":\"opaque-");
        assertThat(authorized).containsExactly(RunOperation.START, RunOperation.QUERY, RunOperation.READ_EVENTS);
        assertThat(scopedCalls.get()).isGreaterThanOrEqualTo(5);
    }

    @Test
    void rejectsProtocolConflictsBeforeInvokingRuntimeAndHidesUnauthorizedResources() {
        StubRuntime runtime = new StubRuntime();
        HaifaHttpTransportAdapter adapter = adapter(
                runtime,
                request -> CALLER,
                (caller, operation, run, interaction) -> {
                    if (operation == RunOperation.QUERY) throw new HttpAuthorizationException();
                },
                HttpTransportConfiguration.DEFAULT,
                new AtomicInteger());

        HttpTransportResponse conflict = adapter.handle(new HttpTransportRequest(
                "POST",
                "/v1/runs/run-http-1/inputs",
                Map.of(
                        "Content-Type", List.of("application/json"),
                        "Idempotency-Key", List.of("header-key"),
                        "If-Match", List.of("\"1\"")),
                Map.of(),
                """
                {"inputId":"input-1","runId":"run-http-1","expectedRunVersion":2,
                 "contents":[{"type":"text","text":"hello","format":"plain"}],
                 "idempotencyKey":"body-key","submittedAt":"2026-07-26T00:00:00Z"}
                """
                        .getBytes(StandardCharsets.UTF_8)));
        HttpTransportResponse hidden = adapter.handle(get("/v1/runs/run-http-1", Map.of()));

        assertThat(conflict.status()).isEqualTo(412);
        assertThat(conflict.bodyUtf8()).contains("RUN_VERSION_CONFLICT").doesNotContain("header-key", "body-key");
        assertThat(hidden.status()).isEqualTo(404);
        assertThat(hidden.bodyUtf8()).contains("RUN_NOT_FOUND").doesNotContain("alice", "tenant-a");
        assertThat(runtime.inputs).isZero();
    }

    @Test
    void sseUsesOpaqueCursorHeartbeatsReauthorizationAndIdempotentClose() throws Exception {
        StubRuntime runtime = new StubRuntime();
        AtomicBoolean revoked = new AtomicBoolean();
        HaifaHttpTransportAdapter adapter = adapter(
                runtime,
                request -> CALLER,
                (caller, operation, run, interaction) -> {
                    if (revoked.get()) throw new HttpAuthorizationException();
                },
                new HttpTransportConfiguration("1.0", 8_192, 10, 100, 4, Duration.ofMillis(2)),
                new AtomicInteger());

        SseOpenResult opened = adapter.openEventStream(new HttpTransportRequest(
                "GET",
                "/v1/runs/run-http-1/events/stream",
                Map.of("Accept", List.of("text/event-stream")),
                Map.of(),
                new byte[0]));
        HttpSseSession session = opened.session().orElseThrow();
        SseFrame replay = session.poll(Duration.ofMillis(5));
        session.acknowledgeWritten(replay);
        SseFrame heartbeat = session.poll(Duration.ofMillis(1));

        assertThat(replay.id()).hasValueSatisfying(id -> assertThat(id).startsWith("opaque-"));
        assertThat(replay.event()).contains("run.accepted");
        assertThat(replay.data().orElseThrow()).contains("\"eventId\":\"event-1\"");
        assertThat(heartbeat.comment()).contains("heartbeat");
        assertThat(session.lastWrittenCursor()).contains(replay.id().orElseThrow());

        revoked.set(true);
        assertThatThrownBy(() -> session.poll(Duration.ofMillis(1))).isInstanceOf(HttpAuthorizationException.class);
        assertThat(session.closed()).isTrue();
        assertThat(session.closeReason()).isEqualTo(SseCloseReason.AUTHORIZATION_REVOKED);
        session.close();
        assertThat(runtime.subscriptionClosed).isTrue();
    }

    @Test
    void slowConsumerClosesBoundedSessionWithoutCallingNetworkFromRuntimeCallback() {
        StubRuntime runtime = new StubRuntime();
        HaifaHttpTransportAdapter adapter = adapter(
                runtime,
                request -> CALLER,
                (caller, operation, run, interaction) -> {},
                new HttpTransportConfiguration("1.0", 8_192, 10, 100, 1, Duration.ofMillis(2)),
                new AtomicInteger());
        HttpSseSession session = adapter.openEventStream(new HttpTransportRequest(
                        "GET",
                        "/v1/runs/run-http-1/events/stream",
                        Map.of("Accept", List.of("text/event-stream")),
                        Map.of(
                                "cursor",
                                List.of(runtime.cursorCodec.encode(
                                        runtime.events.getFirst().cursor()))),
                        new byte[0]))
                .session()
                .orElseThrow();

        runtime.emit(2);
        runtime.emit(3);

        assertThat(session.closed()).isTrue();
        assertThat(session.closeReason()).isEqualTo(SseCloseReason.SLOW_CONSUMER);
        assertThat(runtime.subscriptionClosed).isTrue();
    }

    @Test
    void rejectsOversizedBodiesContentTypeAndTamperedCursorWithoutEchoingSensitiveInput() {
        StubRuntime runtime = new StubRuntime();
        HaifaHttpTransportAdapter adapter = adapter(
                runtime,
                request -> CALLER,
                (caller, operation, run, interaction) -> {},
                new HttpTransportConfiguration("1.0", 16, 10, 100, 2, Duration.ofMillis(2)),
                new AtomicInteger());

        HttpTransportResponse oversized =
                adapter.handle(jsonRequest("POST", "/v1/runs", "{\"token\":\"Bearer fake-secret-value\"}"));
        HttpTransportResponse tampered =
                adapter.handle(get("/v1/runs/run-http-1/events", Map.of("cursor", List.of("opaque-tampered"))));

        assertThat(oversized.status()).isEqualTo(413);
        assertThat(oversized.bodyUtf8()).doesNotContain("fake-secret-value", "Bearer");
        assertThat(tampered.status()).isEqualTo(409);
        assertThat(tampered.bodyUtf8()).contains("CURSOR_INVALID").doesNotContain("opaque-tampered");
    }

    @Test
    void returnsStableAuthenticationVersionAndMediaTypeProblems() {
        StubRuntime runtime = new StubRuntime();
        HaifaHttpTransportAdapter unauthenticated = adapter(
                runtime,
                request -> {
                    throw new HttpAuthenticationException();
                },
                (caller, operation, run, interaction) -> {},
                HttpTransportConfiguration.DEFAULT,
                new AtomicInteger());
        HaifaHttpTransportAdapter adapter = adapter(
                runtime,
                request -> CALLER,
                (caller, operation, run, interaction) -> {},
                HttpTransportConfiguration.DEFAULT,
                new AtomicInteger());

        HttpTransportResponse authentication = unauthenticated.handle(get("/v1/runs/run-http-1", Map.of()));
        HttpTransportResponse version = adapter.handle(new HttpTransportRequest(
                "GET", "/v1/runs/run-http-1", Map.of("X-Haifa-Api-Version", List.of("2.0")), Map.of(), new byte[0]));
        HttpTransportResponse mediaType = adapter.handle(new HttpTransportRequest(
                "POST",
                "/v1/runs",
                Map.of("Content-Type", List.of("text/plain")),
                Map.of(),
                "{}".getBytes(StandardCharsets.UTF_8)));

        assertThat(authentication.status()).isEqualTo(401);
        assertThat(authentication.bodyUtf8())
                .contains("AUTHENTICATION_REQUIRED")
                .doesNotContain("alice");
        assertThat(version.status()).isEqualTo(400);
        assertThat(version.bodyUtf8()).contains("CONTRACT_VERSION_UNSUPPORTED");
        assertThat(mediaType.status()).isEqualTo(415);
        assertThat(mediaType.bodyUtf8()).contains("\"title\":\"Unsupported Media Type\"");
    }

    private static HaifaHttpTransportAdapter adapter(
            StubRuntime runtime,
            HttpCallerResolver resolver,
            RunOperationAuthorizer authorizer,
            HttpTransportConfiguration configuration,
            AtomicInteger scopedCalls) {
        return new HaifaHttpTransportAdapter(
                runtime,
                resolver,
                authorizer,
                new RuntimeCallerScope() {
                    @Override
                    public <T> T call(TrustedCallerContext caller, java.util.function.Supplier<T> operation) {
                        scopedCalls.incrementAndGet();
                        return operation.get();
                    }
                },
                runtime.cursorCodec,
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                configuration,
                () -> "correlation-test");
    }

    private static HttpTransportRequest jsonRequest(String method, String path, String json) {
        return new HttpTransportRequest(
                method,
                path,
                Map.of("Content-Type", List.of("application/json")),
                Map.of(),
                json.getBytes(StandardCharsets.UTF_8));
    }

    private static HttpTransportRequest get(String path, Map<String, List<String>> query) {
        return new HttpTransportRequest("GET", path, Map.of(), query, new byte[0]);
    }

    private static final class StubRuntime implements AgentRuntime {
        private final List<AgentRunEvent> events = new ArrayList<>();
        private final TestCursorCodec cursorCodec = new TestCursorCodec();
        private io.haifa.agent.runtime.api.AgentRunEventListener listener;
        private boolean subscriptionClosed;
        private int inputs;

        private StubRuntime() {
            events.add(event(1));
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
        public RunInputReceipt submitInput(RunInputSubmission input) {
            inputs++;
            throw new UnsupportedOperationException();
        }

        @Override
        public RuntimeCommandResult command(RuntimeCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<AgentRunSnapshot> find(AgentRunId runId) {
            return runId.equals(RUN_ID) ? Optional.of(snapshot()) : Optional.empty();
        }

        @Override
        public Optional<AgentRunViewSnapshot> view(AgentRunId runId) {
            return find(runId).map(value -> new AgentRunViewSnapshot(SESSION_ID, value));
        }

        @Override
        public Optional<InteractionView> pendingInteraction(AgentRunId runId) {
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

        @Override
        public RunEventPage events(AgentRunId runId, RunEventCursor after, int limit) {
            List<AgentRunEvent> remaining = events.stream()
                    .filter(event ->
                            event.sequence() > after.exclusiveSequence().orElse(0))
                    .limit(limit)
                    .toList();
            RunEventCursor head = events.isEmpty()
                    ? RunEventCursor.beforeFirst(runId)
                    : events.getLast().cursor();
            RunEventCursor next =
                    remaining.isEmpty() ? after : remaining.getLast().cursor();
            return new RunEventPage(
                    remaining,
                    next,
                    head,
                    next.exclusiveSequence().orElse(0)
                            < head.exclusiveSequence().orElse(0));
        }

        @Override
        public RunEventSubscription subscribe(
                AgentRunId runId, RunEventCursor after, io.haifa.agent.runtime.api.AgentRunEventListener listener) {
            this.listener = listener;
            events(runId, after, 100).items().forEach(listener::onEvent);
            return new RunEventSubscription() {
                private boolean closed;

                @Override
                public boolean closed() {
                    return closed;
                }

                @Override
                public void close() {
                    closed = true;
                    subscriptionClosed = true;
                }
            };
        }

        private void emit(long sequence) {
            AgentRunEvent event = event(sequence);
            events.add(event);
            if (listener != null) listener.onEvent(event);
        }

        private static AgentRunSnapshot snapshot() {
            return new AgentRunSnapshot(
                    RUN_ID, AgentRunStatus.QUEUED, 1, NOW, Optional.empty(), Optional.empty(), Optional.empty());
        }

        private static AgentRunEvent event(long sequence) {
            return new AgentRunEvent(
                    "event-" + sequence,
                    "run.accepted",
                    "1",
                    RUN_ID,
                    SESSION_ID,
                    sequence,
                    new RunEventCursor(RUN_ID, "1", OptionalLong.of(sequence)),
                    NOW.plusSeconds(sequence),
                    Optional.empty(),
                    Optional.empty(),
                    new RunEventPayloads.RunLifecycle("QUEUED", sequence, "NONE"));
        }
    }

    private static final class TestCursorCodec implements RunEventCursorTokenCodec {
        private final Map<String, RunEventCursor> cursors = new HashMap<>();

        @Override
        public String encode(RunEventCursor cursor) {
            String token = "opaque-" + Integer.toHexString(cursor.hashCode());
            cursors.put(token, cursor);
            return token;
        }

        @Override
        public RunEventCursor decode(AgentRunId expectedRunId, String token) {
            RunEventCursor cursor = cursors.get(token);
            if (cursor == null || !cursor.runId().equals(expectedRunId)) {
                throw new io.haifa.agent.runtime.api.RuntimeContractException(
                        io.haifa.agent.runtime.api.RuntimeErrorCode.CURSOR_INVALID, "The cursor is invalid");
            }
            return cursor;
        }
    }
}
