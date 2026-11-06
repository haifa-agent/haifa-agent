package io.haifa.agent.application.project.product.coding.presentation;

import static org.assertj.core.api.Assertions.*;

import io.haifa.agent.application.project.product.coding.*;
import io.haifa.agent.application.project.product.coding.client.CodingSessionClient;
import io.haifa.agent.core.run.*;
import io.haifa.agent.core.session.*;
import io.haifa.agent.credential.core.DefaultSecretRedactor;
import io.haifa.agent.project.domain.ProjectId;
import io.haifa.agent.runtime.api.*;
import io.haifa.coding.api.*;
import io.haifa.coding.api.CodingProtocol.*;
import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class CodingPresentationObservationTest {
    @Test
    void registersBeforeReadingAndBuffersUpdatesBeforeTransportAttach() throws Exception {
        Fixture f = new Fixture();
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        f.beforeRead.set(() -> {
            reading.countDown();
            await(release);
        });
        try (var observation = f.observe("first-client")) {
            assertThat(reading.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(f.source.get()).isNotNull();
            f.title.set("changed during snapshot");
            f.source.get().run();
            release.countDown();
            Snapshot first = observation.initial();
            assertThat(first.session().title()).isEqualTo("changed during snapshot");
            f.title.set("accepted but receipt lost");
            f.source.get().run();
            // No polling/attach occurred before this mutation. The update must already be buffered.
            Update next = (Update) observation.poll(Duration.ofSeconds(5));
            assertThat(next.baseRevision()).isEqualTo(first.revision());
            assertThat(CodingPresentationReducer.apply(first, next).session().title())
                    .isEqualTo("accepted but receipt lost");
        }
        assertThat(f.closes).hasValue(3);
    }

    @Test
    void outputUsesSeparateCursorDoesNotReadSessionAndDoesNotPublishSplitCredentials() throws Exception {
        Fixture f = new Fixture();
        try (var observation = f.observe("client")) {
            observation.initial();
            int reads = f.reads.get();
            f.emit(1, "Bearer synthetic-");
            Update first = (Update) observation.poll(Duration.ofSeconds(5));
            assertThat(first.replacement().live().text()).doesNotContain("synthetic");
            f.emit(2, "credential ");
            Update second = (Update) observation.poll(Duration.ofSeconds(5));
            assertThat(second.replacement().live().text())
                    .contains("[REDACTED]")
                    .doesNotContain("synthetic-credential");
            assertThat(f.reads).hasValue(reads);
            f.emit(2, "duplicate must not appear ");
            f.title.set("barrier");
            f.source.get().run();
            Update barrier = (Update) observation.poll(Duration.ofSeconds(5));
            assertThat(barrier.replacement().session().title()).isEqualTo("barrier");
            assertThat(barrier.revision()).isEqualTo(second.revision() + 1);
        }
    }

    @Test
    void byteBudgetOverflowRequiresResyncAndClosesOnlyObservation() throws Exception {
        Fixture f = new Fixture();
        try (var observation = f.observe("client")) {
            observation.initial();
            List<CodingSessionHistoryItem> items = new ArrayList<>();
            for (int i = 0; i < 3; i++)
                items.add(new CodingSessionHistoryItem(
                        "message-" + i,
                        CodingSessionHistoryItem.Kind.USER,
                        "You",
                        "汉".repeat(8000),
                        "COMPLETED",
                        i + 1,
                        Instant.EPOCH,
                        Optional.of("run"),
                        false));
            f.history.set(new CodingSessionHistoryPage(f.session, items, false, OptionalLong.empty()));
            f.source.get().run();
            assertThat(observation.poll(Duration.ofSeconds(5))).isInstanceOf(Resync.class);
            assertThat(observation.closed()).isTrue();
        }
        assertThat(f.closes).hasValue(3);
    }

    @Test
    void runHandoffWaitsForExactHistoryAssociationAndIgnoresOldGeneration() throws Exception {
        Fixture f = new Fixture();
        try (var observation = f.observe("client")) {
            observation.initial();
            var oldOutput = f.output.get();
            f.emit(1, "unfinished answer ");
            Update live = (Update) observation.poll(Duration.ofSeconds(5));
            assertThat(live.replacement().live().runId()).isEqualTo(f.run.value());
            f.selected.set(new AgentRunId("successor"));
            f.source.get().run();
            Update waiting = (Update) observation.poll(Duration.ofSeconds(5));
            assertThat(waiting.replacement().hydration()).isEqualTo("refreshing");
            assertThat(waiting.replacement().live().runId()).isEqualTo(f.run.value());
            assertThat(waiting.replacement().actions()).allMatch(a -> !a.available());
            f.history.set(new CodingSessionHistoryPage(
                    f.session,
                    List.of(new CodingSessionHistoryItem(
                            "saved-message",
                            CodingSessionHistoryItem.Kind.ASSISTANT,
                            "Assistant",
                            "authoritative final answer",
                            "COMPLETED",
                            1,
                            Instant.EPOCH,
                            Optional.of(f.run.value()),
                            false)),
                    false,
                    OptionalLong.empty()));
            f.source.get().run();
            Update committed = (Update) observation.poll(Duration.ofSeconds(5));
            assertThat(committed.replacement().session().run().id()).isEqualTo("successor");
            assertThat(committed.replacement().live()).isNull();
            assertThat(committed.replacement().entries())
                    .anyMatch(e -> e.id().equals("saved-message") && e.runId().equals(f.run.value()));
            oldOutput.onOutput(new AgentRunOutputEvent(
                    f.run,
                    "old-call",
                    "old-generation",
                    1,
                    99,
                    AgentRunOutputEventType.ASSISTANT_TEXT_DELTA,
                    "late output ",
                    Instant.EPOCH));
            f.title.set("after handoff");
            f.source.get().run();
            Update barrier = (Update) observation.poll(Duration.ofSeconds(5));
            assertThat(barrier.revision()).isEqualTo(committed.revision() + 1);
            assertThat(barrier.replacement().live()).isNull();
        }
    }

    @Test
    void rejectsCrossRunOutputInsteadOfPublishingForeignContent() throws Exception {
        Fixture f = new Fixture();
        try (var observation = f.observe("client")) {
            observation.initial();
            f.output
                    .get()
                    .onOutput(new AgentRunOutputEvent(
                            new AgentRunId("foreign"),
                            "call",
                            "generation",
                            1,
                            1,
                            AgentRunOutputEventType.ASSISTANT_TEXT_DELTA,
                            "foreign private body ",
                            Instant.EPOCH));
            Resync notification = (Resync) observation.poll(Duration.ofSeconds(5));
            assertThat(notification.reason()).isEqualTo("SOURCE_SCOPE_MISMATCH");
            assertThat(observation.closed()).isTrue();
        }
    }

    @Test
    void largeInitialHistoryStaysUsableWithExplicitUtf8BudgetDegradation() throws Exception {
        Fixture f = new Fixture();
        List<CodingSessionHistoryItem> items = new ArrayList<>();
        for (int i = 1; i <= 31; i++)
            items.add(new CodingSessionHistoryItem(
                    "huge-" + i,
                    CodingSessionHistoryItem.Kind.USER,
                    "You",
                    "汉".repeat(8000),
                    "COMPLETED",
                    i,
                    Instant.EPOCH,
                    Optional.of(f.run.value()),
                    false));
        f.history.set(new CodingSessionHistoryPage(f.session, items, false, OptionalLong.empty()));
        try (var observation = f.observe("client")) {
            Snapshot snapshot = observation.initial();
            assertThat(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(snapshot).length)
                    .isLessThanOrEqualTo(262144);
            assertThat(snapshot.entries()).isNotEmpty();
            assertThat(snapshot.history().hasEarlier()).isTrue();
            assertThat(snapshot.history().complete()).isFalse();
            assertThat(snapshot.actions()).allMatch(action -> !action.available());
        }
    }

    @Test
    void recentToolFactsDoNotReloadSessionOrDisappearBehindFullHistory() throws Exception {
        Fixture f = new Fixture();
        List<CodingSessionHistoryItem> items = new ArrayList<>();
        for (int i = 1; i <= 31; i++)
            items.add(new CodingSessionHistoryItem(
                    "short-" + i,
                    CodingSessionHistoryItem.Kind.USER,
                    "You",
                    "message",
                    "COMPLETED",
                    i,
                    Instant.EPOCH,
                    Optional.of(f.run.value()),
                    false));
        f.history.set(new CodingSessionHistoryPage(f.session, items, false, OptionalLong.empty()));
        try (var observation = f.observe("client")) {
            observation.initial();
            int reads = f.reads.get();
            for (int i = 1; i <= 3; i++) {
                f.events
                        .get()
                        .onEvent(new AgentRunEvent(
                                "event-" + i,
                                "tool.call.started",
                                "1",
                                f.run,
                                f.session,
                                i,
                                new RunEventCursor(f.run, "1", OptionalLong.of(i)),
                                Instant.EPOCH,
                                Optional.empty(),
                                Optional.empty(),
                                new RunEventPayloads.ToolLifecycle(
                                        "call-" + i, "Tool", "RUNNING", "STARTED", "safe target", "")));
                Update update = (Update) observation.poll(Duration.ofSeconds(5));
                assertThat(update.replacement().entries().stream()
                                .filter(e -> e.kind().equals("tool"))
                                .count())
                        .isEqualTo(i);
                assertThat(update.replacement().entries()).hasSize(32);
            }
            assertThat(f.reads).hasValue(reads);
        }
    }

    @Test
    void deliveryNoticesRetainSourceAndFactsInInitialReplayAndLiveUpdates() throws Exception {
        Fixture f = new Fixture();
        f.replay.set(List.of(f.delivery(
                1,
                new RunEventPayloads.DeliveryLifecycle(
                        "BUDGET",
                        "BUDGET_THRESHOLD_REACHED",
                        "REMAINING_20_PERCENT",
                        List.of(),
                        19,
                        0,
                        "TOKENS",
                        81,
                        100))));
        try (var observation = f.observe("client")) {
            var initial = observation.initial();
            var threshold = initial.entries().getLast();
            assertThat(threshold.kind()).isEqualTo("notice");
            assertThat(threshold.runId()).isEqualTo(f.run.value());
            assertThat(threshold.title()).isEqualTo("Budget threshold");
            assertThat(threshold.noticeCode()).isEqualTo("REMAINING_20_PERCENT");
            assertThat(threshold.text()).contains("Limiting resource: TOKENS", "Usage: 81 / 100", "Remaining: 19%");
            int reads = f.reads.get();

            f.events
                    .get()
                    .onEvent(f.delivery(
                            2,
                            new RunEventPayloads.DeliveryLifecycle(
                                    "COMPLETION", "DEFERRED", "EVIDENCE_REQUIRED", List.of("VERIFICATION"), 17, 2)));
            var update = (Update) observation.poll(Duration.ofSeconds(5));
            var deferred = update.replacement().entries().getLast();
            assertThat(deferred.title()).isEqualTo("Completion deferred");
            assertThat(deferred.noticeCode()).isEqualTo("EVIDENCE_REQUIRED");
            assertThat(deferred.text()).contains("Missing: VERIFICATION", "Repair: 2", "Remaining: 17%");
            assertThat(update.replacement().entries()).contains(threshold);
            assertThat(f.reads).hasValue(reads);
        }
    }

    @Test
    void physicalAttemptIsNotMergedWithThePreviousModelGeneration() throws Exception {
        Fixture f = new Fixture();
        try (var observation = f.observe("client")) {
            observation.initial();
            f.emit(1, "old attempt ");
            observation.poll(Duration.ofSeconds(5));
            f.output
                    .get()
                    .onOutput(new AgentRunOutputEvent(
                            f.run,
                            "model-call",
                            "generation",
                            2,
                            2,
                            AgentRunOutputEventType.ASSISTANT_TEXT_DELTA,
                            "new attempt ",
                            Instant.EPOCH));
            Update update = (Update) observation.poll(Duration.ofSeconds(5));
            assertThat(update.replacement().live().physicalAttempt()).isEqualTo(2);
            assertThat(update.replacement().live().text())
                    .contains("new attempt")
                    .doesNotContain("old attempt");
        }
    }

    @Test
    void splitDisplayControlsCannotBypassStreamingCredentialRedaction() throws Exception {
        Fixture f = new Fixture();
        try (var observation = f.observe("client")) {
            observation.initial();
            f.emit(1, "Bea\u001b[");
            var first = (Update) observation.poll(Duration.ofSeconds(5));
            assertThat(first.replacement().live().text()).doesNotContain("synthetic");
            f.emit(2, "31mrer synthetic-");
            var second = (Update) observation.poll(Duration.ofSeconds(5));
            assertThat(second.replacement().live().text()).doesNotContain("synthetic");
            f.emit(3, "credential ");
            var third = (Update) observation.poll(Duration.ofSeconds(5));
            assertThat(third.replacement().live().text())
                    .contains("[REDACTED]")
                    .doesNotContain("synthetic-credential", "\u001b");
        }
    }

    @Test
    void splitSurrogatePairIsPublishedOnlyAsACompleteUnicodeScalar() throws Exception {
        Fixture f = new Fixture();
        try (var observation = f.observe("client")) {
            observation.initial();
            f.emit(1, "\uD83D");
            Update first = (Update) observation.poll(Duration.ofSeconds(5));
            assertThat(first.replacement().live().text()).isEmpty();
            f.emit(2, "\uDE00 汉字 ");
            Update second = (Update) observation.poll(Duration.ofSeconds(5));
            assertThat(second.replacement().live().text()).contains("😀 汉字");
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("controlled read was not released");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static final class Fixture {
        final AgentSessionId session = new AgentSessionId("session");
        final AgentRunId run = new AgentRunId("run");
        final AtomicReference<AgentRunId> selected = new AtomicReference<>(run);
        final AtomicReference<AgentRunEventListener> events = new AtomicReference<>();
        final AtomicReference<List<AgentRunEvent>> replay = new AtomicReference<>(List.of());
        final AtomicReference<String> title = new AtomicReference<>("initial");
        final AtomicReference<Runnable> source = new AtomicReference<>();
        final AtomicReference<Runnable> beforeRead = new AtomicReference<>();
        final AtomicReference<AgentRunOutputListener> output = new AtomicReference<>();
        final AtomicInteger reads = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        final AtomicReference<CodingSessionHistoryPage> history =
                new AtomicReference<>(CodingSessionHistoryPage.empty(session));

        AgentRunSnapshot runView(AgentRunId id) {
            return new AgentRunSnapshot(
                    id, AgentRunStatus.RUNNING, 1, Instant.EPOCH, Optional.empty(), Optional.empty(), Optional.empty());
        }

        final CodingSessionClient client = (CodingSessionClient) Proxy.newProxyInstance(
                CodingSessionClient.class.getClassLoader(),
                new Class<?>[] {CodingSessionClient.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "presentationSnapshot" -> {
                        reads.incrementAndGet();
                        var hook = beforeRead.getAndSet(null);
                        if (hook != null) hook.run();
                        yield new CodingSessionView(
                                new CodingSessionSummary(
                                        session,
                                        new ProjectId("project"),
                                        title.get(),
                                        AgentSessionStatus.ACTIVE,
                                        Optional.of(selected.get()),
                                        Optional.of(AgentRunStatus.RUNNING),
                                        0,
                                        Instant.EPOCH,
                                        1),
                                Optional.of(runView(selected.get())),
                                Optional.empty(),
                                Optional.empty(),
                                "config",
                                "profile");
                    }
                    case "history" -> history.get();
                    case "events" -> {
                        var items = replay.get();
                        var next = items.isEmpty()
                                ? (RunEventCursor) args[1]
                                : items.getLast().cursor();
                        yield new RunEventPage(items, next, next, false);
                    }
                    case "findRun" -> Optional.of(runView((AgentRunId) args[0]));
                    case "subscribe" -> {
                        events.set((AgentRunEventListener) args[2]);
                        yield new RunEventSubscription() {
                            public boolean closed() {
                                return false;
                            }

                            public void close() {
                                closes.incrementAndGet();
                            }
                        };
                    }
                    case "subscribeOutput" -> {
                        output.set((AgentRunOutputListener) args[2]);
                        yield new RunOutputSubscription() {
                            public boolean closed() {
                                return false;
                            }

                            public void close() {
                                closes.incrementAndGet();
                            }
                        };
                    }
                    default -> throw new AssertionError("Observation cannot invoke " + method.getName());
                });

        CodingPresentationObservation observe(String clientId) {
            return new CodingPresentationObservation(
                    listener -> {
                        source.set(listener);
                        return closes::incrementAndGet;
                    },
                    client,
                    new Scope("view", "host", clientId, session.value(), 1, "native"),
                    new DefaultSecretRedactor(),
                    () -> new Consent("ASK", null, false));
        }

        AgentRunEvent delivery(long sequence, RunEventPayloads.DeliveryLifecycle payload) {
            return new AgentRunEvent(
                    "delivery-" + sequence,
                    "delivery.notice",
                    "1",
                    run,
                    session,
                    sequence,
                    new RunEventCursor(run, "1", OptionalLong.of(sequence)),
                    Instant.EPOCH,
                    Optional.empty(),
                    Optional.empty(),
                    payload);
        }

        void emit(long sequence, String text) {
            output.get()
                    .onOutput(new AgentRunOutputEvent(
                            run,
                            "model-call",
                            "generation",
                            1,
                            sequence,
                            AgentRunOutputEventType.ASSISTANT_TEXT_DELTA,
                            text,
                            Instant.EPOCH));
        }
    }
}
