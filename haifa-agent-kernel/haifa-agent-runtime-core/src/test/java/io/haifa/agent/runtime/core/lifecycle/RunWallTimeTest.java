package io.haifa.agent.runtime.core.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunOutcome;
import io.haifa.agent.core.run.AgentRunResult;
import io.haifa.agent.core.run.RunTerminationReason;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.runtime.api.AgentRunRequest;
import io.haifa.agent.runtime.api.RuntimeOverrides;
import io.haifa.agent.runtime.core.RuntimeCoreBuilder;
import io.haifa.agent.runtime.core.execution.ManualExecutionScheduler;
import io.haifa.agent.runtime.core.storage.InMemoryRuntimeStore;
import io.haifa.agent.runtime.core.storage.RuntimePersistencePorts;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** A terminal run must carry its own duration: nothing else increments the wall time usage. */
class RunWallTimeTest {
    private static final Instant CREATED = Instant.parse("2026-07-21T00:00:00Z");

    @Test
    void aCompletedRunRecordsItsActiveWallTime() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AgentRun run = createRun(store);

        RunTransitionCoordinator transitions = coordinator(store, CREATED.plus(Duration.ofMinutes(4)));
        transitions.started(run);
        transitions.beginCompleting(run);
        transitions.completed(
                run,
                new AgentRunResult(AgentRunOutcome.SUCCESS, "done", "result", "1.0", Map.of(), List.of(), List.of()));

        assertThat(store.find(run.id()).orElseThrow().usage().wallTimeMillis())
                .isEqualTo(Duration.ofMinutes(4).toMillis());
    }

    @Test
    void aTimedOutRunRecordsItsWallTimeToo() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AgentRun run = createRun(store);

        coordinator(store, CREATED.plus(Duration.ofSeconds(90)))
                .timedOut(run, new RunTerminationReason("DEADLINE_EXCEEDED", "budget exhausted"));

        assertThat(store.find(run.id()).orElseThrow().usage().wallTimeMillis())
                .isEqualTo(Duration.ofSeconds(90).toMillis());
    }

    private static RunTransitionCoordinator coordinator(InMemoryRuntimeStore store, Instant now) {
        AtomicInteger ids = new AtomicInteger();
        return new RunTransitionCoordinator(
                store,
                store,
                store,
                store,
                () -> "transition-" + ids.incrementAndGet(),
                () -> now,
                new RunAwaiter(),
                store);
    }

    private static AgentRun createRun(InMemoryRuntimeStore store) {
        AtomicInteger ids = new AtomicInteger();
        var runtime = new RuntimeCoreBuilder()
                .registerChatModel(
                        "openai-compatible",
                        "1.0.0",
                        request -> new AgentChatResponse(
                                "init",
                                "deepseek-v4-pro",
                                "hello",
                                List.of(),
                                ModelFinishReason.STOP,
                                ModelUsage.unpriced(1, 1),
                                "",
                                Map.of()))
                .persistence(RuntimePersistencePorts.inMemory(store))
                .scheduler(new ManualExecutionScheduler())
                .identifierGenerator(() -> "id-" + ids.incrementAndGet())
                .timeProvider(() -> CREATED)
                .build();
        var accepted = runtime.start(new AgentRunRequest(
                "key-1",
                new AgentDefinitionId("test-agent"),
                Optional.empty(),
                "default",
                new AgentSessionId("session-1"),
                Optional.empty(),
                "objective",
                List.of(),
                RuntimeOverrides.NONE));
        return store.find(accepted.runId()).orElseThrow();
    }
}
