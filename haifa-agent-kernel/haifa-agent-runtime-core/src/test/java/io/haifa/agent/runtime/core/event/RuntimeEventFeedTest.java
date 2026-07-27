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
import io.haifa.agent.runtime.api.RuntimeContractException;
import io.haifa.agent.runtime.api.RuntimeErrorCode;
import io.haifa.agent.runtime.core.storage.InMemoryRuntimeStore;
import io.haifa.agent.runtime.core.storage.RuntimeEvent;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
        assertThat(second.items()).singleElement().satisfies(event -> {
            assertThat(event.eventId()).isEqualTo(delta.eventId());
            assertThat(event.eventType()).isEqualTo("assistant.text.delta");
            assertThat(event.payload()).isEqualTo(new RunEventPayloads.AssistantTextDelta("generation", "hello"));
        });
        assertThat(second.nextCursor()).isEqualTo(second.headCursor());
        assertThat(second.hasMore()).isFalse();
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
                RuntimeErrorCode.CURSOR_INVALID);
        assertCode(
                () -> feed.page(runId, new RunEventCursor(runId, "2", OptionalLong.empty()), 10),
                RuntimeErrorCode.CONTRACT_VERSION_UNSUPPORTED);
        assertCode(
                () -> feed.page(runId, new RunEventCursor(runId, "1", OptionalLong.of(4)), 10),
                RuntimeErrorCode.CURSOR_INVALID);

        assertThat(store.deleteBefore(runId, 3, NOW)).isEqualTo(2);
        assertCode(() -> feed.page(runId, RunEventCursor.beforeFirst(runId), 10), RuntimeErrorCode.CURSOR_EXPIRED);
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
                RuntimeErrorCode.CURSOR_INVALID);
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
    void projectsToolExecutionAndResourceFactsFromTheAllowlist() {
        InMemoryRuntimeStore store = storeWithRun("run");
        AgentRunId runId = new AgentRunId("run");
        RuntimeClientEventProjector projector = new RuntimeClientEventProjector(store);

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

        assertThat(tool.eventType()).isEqualTo("tool.call.succeeded");
        assertThat(tool.payload()).isInstanceOf(RunEventPayloads.ToolLifecycle.class);
        assertThat(execution.eventType()).isEqualTo("execution.completed");
        assertThat(execution.payload()).isInstanceOf(RunEventPayloads.ExecutionLifecycle.class);
        assertThat(resource.eventType()).isEqualTo("checkpoint.available");
        assertThat(resource.payload()).isInstanceOf(RunEventPayloads.ResourceAvailable.class);
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

    private static void assertCode(Runnable action, RuntimeErrorCode code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(RuntimeContractException.class, exception -> assertThat(exception.code())
                        .isEqualTo(code));
    }
}
