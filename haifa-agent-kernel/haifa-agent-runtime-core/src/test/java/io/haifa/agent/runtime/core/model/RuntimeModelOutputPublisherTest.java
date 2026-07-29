package io.haifa.agent.runtime.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.runtime.api.AgentRunOutputEvent;
import io.haifa.agent.runtime.api.AgentRunOutputEventType;
import io.haifa.agent.runtime.api.RunOutputCursor;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RuntimeModelOutputPublisherTest {
    private static final Instant NOW = Instant.parse("2026-07-29T00:00:00Z");

    @Test
    void deliversEveryContentDeltaInProviderOrderWithoutAJournal() {
        RuntimeModelOutputPublisher publisher = new RuntimeModelOutputPublisher(() -> NOW);
        AgentRunId runId = new AgentRunId("run-1");
        List<AgentRunOutputEvent> received = new CopyOnWriteArrayList<>();
        var subscription = publisher.subscribe(runId, RunOutputCursor.BEFORE_FIRST, received::add);

        publisher.started(runId, "call-1", 1, 1);
        publisher.content(runId, "call-1", 1, "first ");
        publisher.content(runId, "call-1", 1, "\n");
        publisher.content(runId, "call-1", 1, "third");
        publisher.committed(runId, "call-1", 1, 1);

        assertThat(received)
                .filteredOn(event -> event.type() == AgentRunOutputEventType.ASSISTANT_TEXT_DELTA)
                .extracting(AgentRunOutputEvent::textDelta)
                .containsExactly("first ", "\n", "third");
        assertThat(received).extracting(AgentRunOutputEvent::sequence).containsExactly(1L, 2L, 3L, 4L, 5L);
        subscription.close();
    }

    @Test
    void isolatesRunsListenerFailuresAndClosedSubscriptions() {
        RuntimeModelOutputPublisher publisher = new RuntimeModelOutputPublisher(() -> NOW);
        AgentRunId first = new AgentRunId("run-first");
        AgentRunId second = new AgentRunId("run-second");
        List<String> firstDeltas = new CopyOnWriteArrayList<>();
        List<String> secondDeltas = new CopyOnWriteArrayList<>();
        var failing = publisher.subscribe(first, RunOutputCursor.BEFORE_FIRST, ignored -> {
            throw new IllegalStateException("observer failure");
        });
        var firstSubscription = publisher.subscribe(first, RunOutputCursor.BEFORE_FIRST, event -> {
            if (event.type() == AgentRunOutputEventType.ASSISTANT_TEXT_DELTA) {
                firstDeltas.add(event.textDelta());
            }
        });
        var secondSubscription = publisher.subscribe(second, RunOutputCursor.BEFORE_FIRST, event -> {
            if (event.type() == AgentRunOutputEventType.ASSISTANT_TEXT_DELTA) {
                secondDeltas.add(event.textDelta());
            }
        });

        publisher.content(first, "call-first", 1, "one");
        publisher.content(second, "call-second", 1, "other");
        firstSubscription.close();
        firstSubscription.close();
        publisher.content(first, "call-first", 1, "ignored-after-close");

        assertThat(firstDeltas).containsExactly("one");
        assertThat(secondDeltas).containsExactly("other");
        assertThat(firstSubscription.closed()).isTrue();
        assertThat(publisher.subscriberCount(first)).isEqualTo(1);
        failing.close();
        secondSubscription.close();
    }

    @Test
    void boundsReplayAndCleansAllRunResourcesAtTerminalState() {
        RuntimeModelOutputPublisher publisher = new RuntimeModelOutputPublisher(() -> NOW, 3, 8);
        AgentRunId runId = new AgentRunId("run-bounded");
        publisher.content(runId, "call-1", 1, "111");
        publisher.content(runId, "call-1", 1, "222");
        publisher.content(runId, "call-1", 1, "333");

        assertThat(publisher.after(runId, RunOutputCursor.BEFORE_FIRST, 10))
                .extracting(AgentRunOutputEvent::textDelta)
                .containsExactly("222", "333");
        var subscription = publisher.subscribe(runId, RunOutputCursor.BEFORE_FIRST, ignored -> {});
        assertThat(publisher.activeRunCount()).isOne();
        assertThat(publisher.subscriberCount(runId)).isOne();

        publisher.closeRun(runId);

        assertThat(subscription.closed()).isTrue();
        assertThat(publisher.activeRunCount()).isZero();
        assertThat(publisher.after(runId, RunOutputCursor.BEFORE_FIRST, 10)).isEmpty();
    }

    @Test
    void terminalTransitionDeliversFinalCommitBeforeCleaningRunResources() {
        RuntimeModelOutputPublisher publisher = new RuntimeModelOutputPublisher(() -> NOW);
        AgentRunId runId = new AgentRunId("run-terminal");
        List<AgentRunOutputEventType> received = new CopyOnWriteArrayList<>();
        var subscription =
                publisher.subscribe(runId, RunOutputCursor.BEFORE_FIRST, event -> received.add(event.type()));

        publisher.started(runId, "call-1", 1, 1);
        publisher.markRunTerminal(runId);

        assertThat(subscription.closed()).isFalse();
        publisher.committed(runId, "call-1", 1, 1);

        assertThat(received)
                .containsExactly(
                        AgentRunOutputEventType.RUN_OUTPUT_STARTED, AgentRunOutputEventType.ASSISTANT_TEXT_COMMITTED);
        assertThat(subscription.closed()).isTrue();
        assertThat(publisher.activeRunCount()).isZero();
    }

    @Test
    void retrySupersedesFailedGenerationBeforeStartingTheReplacement() {
        RuntimeModelOutputPublisher publisher = new RuntimeModelOutputPublisher(() -> NOW);
        AgentRunId runId = new AgentRunId("run-retry");
        List<AgentRunOutputEvent> received = new CopyOnWriteArrayList<>();
        var subscription = publisher.subscribe(runId, RunOutputCursor.BEFORE_FIRST, received::add);

        publisher.started(runId, "call-1", 1, 1);
        publisher.content(runId, "call-1", 1, "discard me");
        publisher.failed(runId, "call-1", 1, 1);
        publisher.started(runId, "call-2", 2, 1);

        assertThat(received)
                .extracting(AgentRunOutputEvent::type)
                .containsExactly(
                        AgentRunOutputEventType.RUN_OUTPUT_STARTED,
                        AgentRunOutputEventType.ASSISTANT_TEXT_DELTA,
                        AgentRunOutputEventType.RUN_OUTPUT_FAILED,
                        AgentRunOutputEventType.RUN_OUTPUT_SUPERSEDED,
                        AgentRunOutputEventType.RUN_OUTPUT_STARTED);
        assertThat(received.get(3).generationId()).isEqualTo("call-1");
        assertThat(received.get(4).generationId()).isEqualTo("call-2");
        subscription.close();
    }

    @Test
    void concurrentEmissionUsesOneMonotonicSequenceWithoutDroppingEvents() throws Exception {
        RuntimeModelOutputPublisher publisher = new RuntimeModelOutputPublisher(() -> NOW);
        AgentRunId runId = new AgentRunId("run-concurrent");
        List<AgentRunOutputEvent> received = new CopyOnWriteArrayList<>();
        var subscription = publisher.subscribe(runId, RunOutputCursor.BEFORE_FIRST, received::add);
        try (var executor = Executors.newFixedThreadPool(4)) {
            for (int index = 0; index < 200; index++) {
                int value = index;
                executor.submit(() -> publisher.content(runId, "call-1", 1, Integer.toString(value)));
            }
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(received).hasSize(200);
        assertThat(received.stream().map(AgentRunOutputEvent::sequence).distinct())
                .hasSize(200);
        assertThat(received).extracting(AgentRunOutputEvent::sequence).isSortedAccordingTo(Long::compareTo);
        subscription.close();
    }
}
