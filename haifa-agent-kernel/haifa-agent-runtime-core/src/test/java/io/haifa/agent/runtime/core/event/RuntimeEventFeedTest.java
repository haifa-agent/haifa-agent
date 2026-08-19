package io.haifa.agent.runtime.core.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import io.haifa.agent.core.run.AgentRunType;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.runtime.api.RunEventCursor;
import io.haifa.agent.runtime.api.RunEventPayloads;
import io.haifa.agent.runtime.api.RuntimeApiErrorCode;
import io.haifa.agent.runtime.api.RuntimeContractException;
import io.haifa.agent.runtime.core.storage.InMemoryRuntimeStore;
import io.haifa.agent.runtime.core.storage.RuntimeEvent;
import io.haifa.agent.runtime.core.storage.RuntimeEventAppender;
import io.haifa.agent.runtime.core.storage.RuntimeEventSlice;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RuntimeEventFeedTest {
    private static final Instant NOW = Instant.parse("2026-07-26T00:00:00Z");

    @Test
    void pagesTypedAllowlistAndAdvancesAcrossFilteredEntries() {
        InMemoryRuntimeStore store = storeWithRun("run");
        AgentRunId runId = new AgentRunId("run");
        store.append(runId, "trace.internal", Map.of("secret", "must-not-project"), NOW);
        RuntimeEvent accepted = store.append(runId, "run.created", Map.of("version", 0L), NOW);
        RuntimeEvent delta = store.append(
                runId,
                "model.output.delta",
                Map.of("eventType", "ASSISTANT_TEXT_DELTA", "generationId", "generation", "textDelta", "hello"),
                NOW);
        RuntimeEventFeed feed = new RuntimeEventFeed(store, new RuntimeClientEventProjector(store));

        var first = feed.page(runId, RunEventCursor.beforeFirst(runId), 1);

        assertThat(first.items()).singleElement().satisfies(event -> {
            assertThat(event.eventId()).isEqualTo(accepted.eventId());
            assertThat(event.eventType()).isEqualTo("run.accepted");
            assertThat(event.payload()).isInstanceOf(RunEventPayloads.RunLifecycle.class);
        });
        assertThat(first.nextCursor().exclusiveSequence()).hasValue(accepted.sequence());
        assertThat(first.headCursor().exclusiveSequence()).hasValue(delta.sequence());
        assertThat(first.hasMore()).isTrue();

        var second = feed.page(runId, first.nextCursor(), 10);
        assertThat(second.items()).isEmpty();
        assertThat(second.nextCursor()).isEqualTo(second.headCursor());
        assertThat(second.hasMore()).isFalse();
    }

    @Test
    void filtersEveryLegacyAssistantDeltaWithoutPoisoningThePage() {
        InMemoryRuntimeStore store = storeWithRun("run");
        AgentRunId runId = new AgentRunId("run");
        store.append(
                runId,
                "model.output.assistant_text_delta",
                Map.of("eventType", "ASSISTANT_TEXT_DELTA", "generationId", "generation", "textDelta", "Clamp.java"),
                NOW);
        store.append(
                runId,
                "model.output.assistant_text_delta",
                Map.of("eventType", "ASSISTANT_TEXT_DELTA", "generationId", "generation", "textDelta", " "),
                NOW);
        store.append(
                runId,
                "model.output.assistant_text_delta",
                Map.of("eventType", "ASSISTANT_TEXT_DELTA", "generationId", "generation", "textDelta", "boundary"),
                NOW);
        RuntimeEventFeed feed = new RuntimeEventFeed(store, new RuntimeClientEventProjector(store));

        var page = feed.page(runId, RunEventCursor.beforeFirst(runId), 10);

        assertThat(page.items()).isEmpty();
        assertThat(page.nextCursor()).isEqualTo(page.headCursor());
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void filtersMetadataOnlyAssistantTextDeltaAndContinuesToTheFeedHead() {
        InMemoryRuntimeStore store = storeWithRun("run");
        AgentRunId runId = new AgentRunId("run");
        store.append(
                runId,
                "model.output.delta",
                Map.of("eventType", "ASSISTANT_TEXT_DELTA", "generationId", "generation", "textDelta", ""),
                NOW);
        RuntimeEvent status = store.append(runId, "run.status", Map.of("status", "COMPLETED", "version", 2L), NOW);
        RuntimeEventFeed feed = new RuntimeEventFeed(store, new RuntimeClientEventProjector(store));

        var page = feed.page(runId, RunEventCursor.beforeFirst(runId), 10);

        assertThat(page.items()).singleElement().satisfies(event -> {
            assertThat(event.sequence()).isEqualTo(status.sequence());
            assertThat(event.eventType()).isEqualTo("run.status.changed");
        });
        assertThat(page.nextCursor()).isEqualTo(page.headCursor());
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void reportsWrongAheadExpiredAndUnsupportedCursorsDeterministically() {
        InMemoryRuntimeStore store = storeWithRun("run");
        AgentRunId runId = new AgentRunId("run");
        store.append(runId, "trace.internal", Map.of(), NOW);
        store.append(runId, "run.created", Map.of("version", 0L), NOW);
        store.append(runId, "run.status", Map.of("status", "RUNNING", "version", 1L), NOW);
        RuntimeEventFeed feed = new RuntimeEventFeed(store, new RuntimeClientEventProjector(store));

        assertCode(
                () -> feed.page(new AgentRunId("other"), RunEventCursor.beforeFirst(runId), 10),
                RuntimeApiErrorCode.CURSOR_INVALID);
        assertCode(
                () -> feed.page(runId, new RunEventCursor(runId, "2", OptionalLong.empty()), 10),
                RuntimeApiErrorCode.CONTRACT_VERSION_UNSUPPORTED);
        assertCode(
                () -> feed.page(runId, new RunEventCursor(runId, "1", OptionalLong.of(4)), 10),
                RuntimeApiErrorCode.CURSOR_INVALID);

        assertThat(store.deleteBefore(runId, 3, NOW)).isEqualTo(2);
        assertCode(() -> feed.page(runId, RunEventCursor.beforeFirst(runId), 10), RuntimeApiErrorCode.CURSOR_EXPIRED);
        var retained = feed.page(runId, new RunEventCursor(runId, "1", OptionalLong.of(2)), 10);
        assertThat(retained.items()).extracting(event -> event.sequence()).containsExactly(3L);
    }

    @Test
    void emptyFeedOnlyAcceptsBeforeFirst() {
        InMemoryRuntimeStore store = storeWithRun("run");
        AgentRunId runId = new AgentRunId("run");
        RuntimeEventFeed feed = new RuntimeEventFeed(store, new RuntimeClientEventProjector(store));

        assertThat(feed.page(runId, RunEventCursor.beforeFirst(runId), 10).items())
                .isEmpty();
        assertCode(
                () -> feed.page(runId, new RunEventCursor(runId, "1", OptionalLong.of(1)), 10),
                RuntimeApiErrorCode.CURSOR_INVALID);
    }

    @Test
    void knownSchemaAndPayloadFailuresFailClosedWhileUnknownTypesAreFiltered() {
        InMemoryRuntimeStore store = storeWithRun("run");
        RuntimeClientEventProjector projector = new RuntimeClientEventProjector(store);
        AgentRunId runId = new AgentRunId("run");

        assertThat(projector.project(new RuntimeEvent(
                        "unknown",
                        runId,
                        1,
                        "provider.raw",
                        "99",
                        Map.of("apiKey", "fake-secret"),
                        NOW,
                        Optional.empty(),
                        Optional.empty())))
                .isEmpty();
        assertThatThrownBy(() -> projector.project(new RuntimeEvent(
                        "known", runId, 2, "run.created", "99", Map.of(), NOW, Optional.empty(), Optional.empty())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("known client event has an unsupported schema version");
        assertThatThrownBy(() -> projector.project(new RuntimeEvent(
                        "missing-field",
                        runId,
                        3,
                        "run.input.accepted",
                        "1",
                        Map.of("fullPrompt", "must-not-project"),
                        NOW,
                        Optional.empty(),
                        Optional.empty())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing a required safe field");
    }

    @Test
    void projectsModelToolExecutionAndResourceFactsFromTheAllowlist() {
        InMemoryRuntimeStore store = storeWithRun("run");
        AgentRunId runId = new AgentRunId("run");
        RuntimeClientEventProjector projector = new RuntimeClientEventProjector(store);

        var model = projector
                .project(new RuntimeEvent(
                        "model",
                        runId,
                        1,
                        "model.call.succeeded",
                        "1",
                        Map.ofEntries(
                                Map.entry("modelCallId", "model-call-1"),
                                Map.entry("providerId", "deepseek"),
                                Map.entry("modelId", "deepseek-chat"),
                                Map.entry("status", "SUCCEEDED"),
                                Map.entry("iteration", 1),
                                Map.entry("attempt", 1),
                                Map.entry("inputTokens", 20L),
                                Map.entry("outputTokens", 5L),
                                Map.entry("finishReason", "STOP"),
                                Map.entry("reasonCode", "NONE"),
                                Map.entry("fullPrompt", "must-not-project"),
                                Map.entry("responseText", "must-not-project")),
                        NOW,
                        Optional.empty(),
                        Optional.empty()))
                .orElseThrow();
        var tool = projector
                .project(new RuntimeEvent(
                        "tool",
                        runId,
                        1,
                        "tool.succeeded",
                        "1",
                        Map.of(
                                "toolCallId", "call-1",
                                "toolName", "execution.run",
                                "targetSummary", "workspace command",
                                "resultRef", "tool-result:1"),
                        NOW,
                        Optional.empty(),
                        Optional.empty()))
                .orElseThrow();
        var execution = projector
                .project(new RuntimeEvent(
                        "execution",
                        runId,
                        2,
                        "execution.completed",
                        "1",
                        Map.of(
                                "executionId", "execution-1",
                                "toolCallId", "call-1",
                                "status", "SUCCEEDED",
                                "commandSummary", "shell command",
                                "logicalWorkdir", ".",
                                "streamKind", "MERGED",
                                "chunkOrRef", "output:1",
                                "exitCode", 0,
                                "truncated", false,
                                "fileChangeSetRef", "changes:1"),
                        NOW,
                        Optional.empty(),
                        Optional.empty()))
                .orElseThrow();
        var resource = projector
                .project(new RuntimeEvent(
                        "resource",
                        runId,
                        3,
                        "checkpoint.available",
                        "1",
                        Map.of(
                                "reference", "checkpoint:1",
                                "kind", "checkpoint",
                                "title", "Checkpoint 1",
                                "status", "AVAILABLE"),
                        NOW,
                        Optional.empty(),
                        Optional.empty()))
                .orElseThrow();

        assertThat(model.eventType()).isEqualTo("model.call.succeeded");
        assertThat(model.payload()).isInstanceOfSatisfying(RunEventPayloads.ModelLifecycle.class, payload -> {
            assertThat(payload.modelCallId()).isEqualTo("model-call-1");
            assertThat(payload.providerId()).isEqualTo("deepseek");
            assertThat(payload.modelId()).isEqualTo("deepseek-chat");
            assertThat(payload.inputTokens()).isEqualTo(20);
            assertThat(payload.outputTokens()).isEqualTo(5);
            assertThat(payload.toString()).doesNotContain("must-not-project");
        });
        assertThat(tool.eventType()).isEqualTo("tool.call.succeeded");
        assertThat(tool.payload()).isInstanceOf(RunEventPayloads.ToolLifecycle.class);
        assertThat(execution.eventType()).isEqualTo("execution.completed");
        assertThat(execution.payload()).isInstanceOf(RunEventPayloads.ExecutionLifecycle.class);
        assertThat(resource.eventType()).isEqualTo("checkpoint.available");
        assertThat(resource.payload()).isInstanceOf(RunEventPayloads.ResourceAvailable.class);
    }

    @Test
    void projectsOnlySafeStructuredDeliveryControlFields() {
        InMemoryRuntimeStore store = storeWithRun("run");
        AgentRunId runId = new AgentRunId("run");
        RuntimeClientEventProjector projector = new RuntimeClientEventProjector(store);

        var deferred = projector
                .project(new RuntimeEvent(
                        "deferred",
                        runId,
                        1,
                        "completion.deferred",
                        "1",
                        Map.of(
                                "phase", "VERIFYING",
                                "status", "COMPLETION_DEFERRED",
                                "reasonCode", "DIFF_INSPECTION_MISSING",
                                "missingEvidence", List.of("DIFF_INSPECTION"),
                                "remainingPercent", 24,
                                "attempt", 1,
                                "fullPrompt", "must-not-project",
                                "hostPath", "/private/workspace",
                                "stderr", "must-not-project"),
                        NOW,
                        Optional.empty(),
                        Optional.empty()))
                .orElseThrow();
        var payload = (RunEventPayloads.DeliveryLifecycle) deferred.payload();

        assertThat(deferred.eventType()).isEqualTo("completion.deferred");
        assertThat(payload.phase()).isEqualTo("VERIFYING");
        assertThat(payload.missingEvidence()).containsExactly("DIFF_INSPECTION");
        assertThat(payload.toString()).doesNotContain("must-not-project", "/private/workspace");

        assertThat(projector.project(new RuntimeEvent(
                        "budget",
                        runId,
                        2,
                        "loop.budget-snapshot",
                        "1",
                        Map.of("newThresholds", List.of(), "remainingPercent", 90),
                        NOW,
                        Optional.empty(),
                        Optional.empty())))
                .isEmpty();
    }

    @Test
    void projectsCodingWorkPhaseWithoutExposingInternalProjectionInputs() {
        InMemoryRuntimeStore store = storeWithRun("run-coding-phase");
        AgentRunId runId = new AgentRunId("run-coding-phase");
        RuntimeClientEventProjector projector = new RuntimeClientEventProjector(store);

        var projected = projector
                .project(new RuntimeEvent(
                        "coding-phase",
                        runId,
                        1,
                        "coding.work-phase",
                        "1",
                        Map.ofEntries(
                                Map.entry("phase", "VERIFY"),
                                Map.entry("status", "ACTIVE"),
                                Map.entry("reasonCode", "AUTHORITATIVE_EVIDENCE_PROJECTION"),
                                Map.entry("missingEvidence", List.of("VALIDATION_ATTEMPT", "DIFF_INSPECTION")),
                                Map.entry("remainingPercent", 42),
                                Map.entry("attempt", 0),
                                Map.entry("projectionDigest", "a".repeat(64)),
                                Map.entry("taskContractDigest", "b".repeat(64)),
                                Map.entry("rawPath", "must-not-project")),
                        NOW,
                        Optional.empty(),
                        Optional.empty()))
                .orElseThrow();

        assertThat(projected.eventType()).isEqualTo("coding.work-phase");
        assertThat(projected.payload()).isInstanceOfSatisfying(RunEventPayloads.DeliveryLifecycle.class, payload -> {
            assertThat(payload.phase()).isEqualTo("VERIFY");
            assertThat(payload.missingEvidence()).containsExactly("VALIDATION_ATTEMPT", "DIFF_INSPECTION");
            assertThat(payload.remainingPercent()).isEqualTo(42);
            assertThat(payload.toString()).doesNotContain("must-not-project", "rawPath");
        });
    }

    @Test
    void subscriptionReplaysThenTailsAndCloseIsIdempotentWithoutDependingOnWakeupPayloads()
            throws InterruptedException {
        InMemoryRuntimeStore store = storeWithRun("run");
        AgentRunId runId = new AgentRunId("run");
        RuntimeEventWakeupRegistry wakeups = new RuntimeEventWakeupRegistry();
        NotifyingRuntimeEventAppender journal = new NotifyingRuntimeEventAppender(store, store, wakeups);
        RuntimeEventFeed feed = new RuntimeEventFeed(journal, new RuntimeClientEventProjector(store));
        RuntimeEventSubscriptions subscriptions = new RuntimeEventSubscriptions(feed, wakeups);
        journal.append(runId, "run.created", Map.of("version", 0L), NOW);
        CountDownLatch delivered = new CountDownLatch(2);
        java.util.concurrent.CopyOnWriteArrayList<String> eventTypes =
                new java.util.concurrent.CopyOnWriteArrayList<>();

        var subscription = subscriptions.subscribe(runId, RunEventCursor.beforeFirst(runId), event -> {
            eventTypes.add(event.eventType());
            delivered.countDown();
        });
        journal.append(runId, "run.status", Map.of("status", "RUNNING", "version", 1L), NOW);

        assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(eventTypes).containsExactly("run.accepted", "run.status.changed");
        subscription.close();
        subscription.close();
        assertThat(subscription.closed()).isTrue();
        journal.append(runId, "run.status", Map.of("status", "PAUSED", "version", 2L), NOW);
        assertThat(eventTypes).containsExactly("run.accepted", "run.status.changed");
    }

    @Test
    void subscriptionRetriesFromItsDurableCursorAfterATransientListenerFailure() throws InterruptedException {
        InMemoryRuntimeStore store = storeWithRun("run");
        AgentRunId runId = new AgentRunId("run");
        RuntimeEventWakeupRegistry wakeups = new RuntimeEventWakeupRegistry();
        NotifyingRuntimeEventAppender journal = new NotifyingRuntimeEventAppender(store, store, wakeups);
        RuntimeEventFeed feed = new RuntimeEventFeed(journal, new RuntimeClientEventProjector(store));
        RuntimeEventSubscriptions subscriptions = new RuntimeEventSubscriptions(feed, wakeups);
        journal.append(runId, "run.created", Map.of("version", 0L), NOW);
        AtomicBoolean failOnce = new AtomicBoolean(true);
        CountDownLatch delivered = new CountDownLatch(1);
        java.util.concurrent.CopyOnWriteArrayList<String> eventTypes =
                new java.util.concurrent.CopyOnWriteArrayList<>();

        var subscription = subscriptions.subscribe(runId, RunEventCursor.beforeFirst(runId), event -> {
            if (failOnce.compareAndSet(true, false)) {
                throw new IllegalStateException("transient listener failure");
            }
            eventTypes.add(event.eventType());
            delivered.countDown();
        });

        assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(eventTypes).containsExactly("run.accepted");
        assertThat(subscription.closed()).isFalse();
        subscription.close();
    }

    @Test
    void healthyIdleSubscriptionReadsOnlyWhenWoken() throws InterruptedException {
        InMemoryRuntimeStore store = storeWithRun("run");
        AgentRunId runId = new AgentRunId("run");
        RuntimeEventWakeupRegistry wakeups = new RuntimeEventWakeupRegistry();
        CountingRuntimeEventAppender counting = new CountingRuntimeEventAppender(store);
        NotifyingRuntimeEventAppender journal = new NotifyingRuntimeEventAppender(counting, store, wakeups);
        RuntimeEventFeed feed = new RuntimeEventFeed(journal, new RuntimeClientEventProjector(store));
        RuntimeEventSubscriptions subscriptions = new RuntimeEventSubscriptions(feed, wakeups);
        journal.append(runId, "run.created", Map.of("version", 0L), NOW);
        CountDownLatch delivered = new CountDownLatch(1);

        var subscription =
                subscriptions.subscribe(runId, RunEventCursor.beforeFirst(runId), event -> delivered.countDown());
        assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();
        int readsAfterReplay = counting.reads();

        Thread.sleep(1_200);

        assertThat(counting.reads()).isEqualTo(readsAfterReplay);
        subscription.close();
    }

    private static InMemoryRuntimeStore storeWithRun(String runValue) {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        store.insert(AgentRun.createRoot(
                new AgentRunId(runValue),
                new AgentRunSpec(
                        new AgentSessionId("session-" + runValue),
                        null,
                        new TenantRef("tenant"),
                        new PrincipalRef("principal", "user"),
                        new AgentDefinitionId("agent"),
                        new AgentDefinitionVersion(1, 0, 0),
                        "profile",
                        "1",
                        AgentRunType.CHAT,
                        "objective",
                        new AgentRunBudget(100, 100, 100, 10, 10, 2, "USD", 100),
                        new AgentRunLimits(10, 2, 1, 60_000, 10_000),
                        new RunConfigurationSnapshotRef("config", "sha256:config")),
                NOW));
        return store;
    }

    private static void assertCode(Runnable action, RuntimeApiErrorCode code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(RuntimeContractException.class, exception -> assertThat(exception.code())
                        .isEqualTo(code));
    }

    private static final class CountingRuntimeEventAppender implements RuntimeEventAppender {
        private final RuntimeEventAppender delegate;
        private final AtomicInteger reads = new AtomicInteger();

        private CountingRuntimeEventAppender(RuntimeEventAppender delegate) {
            this.delegate = delegate;
        }

        private int reads() {
            return reads.get();
        }

        @Override
        public RuntimeEvent append(AgentRunId runId, String type, Map<String, Object> data, Instant occurredAt) {
            return delegate.append(runId, type, data, occurredAt);
        }

        @Override
        public List<RuntimeEvent> eventsFor(AgentRunId runId) {
            return delegate.eventsFor(runId);
        }

        @Override
        public RuntimeEventSlice eventsAfter(
                AgentRunId runId, long exclusiveSequence, OptionalLong observedHead, int limit) {
            reads.incrementAndGet();
            return delegate.eventsAfter(runId, exclusiveSequence, observedHead, limit);
        }

        @Override
        public OptionalLong earliestSequence(AgentRunId runId) {
            return delegate.earliestSequence(runId);
        }

        @Override
        public OptionalLong headSequence(AgentRunId runId) {
            return delegate.headSequence(runId);
        }

        @Override
        public long deleteBefore(AgentRunId runId, long retainFromSequence, Instant deletedAt) {
            return delegate.deleteBefore(runId, retainFromSequence, deletedAt);
        }
    }
}
