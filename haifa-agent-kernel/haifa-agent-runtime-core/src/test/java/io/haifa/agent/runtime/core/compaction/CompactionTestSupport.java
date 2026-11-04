package io.haifa.agent.runtime.core.compaction;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunUsageDelta;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.runtime.api.AgentRunRequest;
import io.haifa.agent.runtime.api.RuntimeOverrides;
import io.haifa.agent.runtime.core.DefaultAgentRuntime;
import io.haifa.agent.runtime.core.RuntimeCoreBuilder;
import io.haifa.agent.runtime.core.execution.ManualExecutionScheduler;
import io.haifa.agent.runtime.core.storage.InMemoryRuntimeStore;
import io.haifa.agent.runtime.core.storage.RuntimePersistencePorts;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

final class CompactionTestSupport {
    private static final Instant NOW = Instant.parse("2026-07-21T00:00:00Z");

    static AgentRun createRun(InMemoryRuntimeStore store) {
        return createRun(store, new AtomicInteger());
    }

    static AgentRun createRun(InMemoryRuntimeStore store, AtomicInteger ids) {
        DefaultAgentRuntime runtime = new RuntimeCoreBuilder()
                .registerChatModel("openai-compatible", "1.0.0", request -> response())
                .persistence(RuntimePersistencePorts.inMemory(store))
                .scheduler(new ManualExecutionScheduler())
                .identifierGenerator(() -> "run-" + ids.incrementAndGet())
                .timeProvider(() -> NOW)
                .build();
        var accepted = runtime.start(new AgentRunRequest(
                "compaction-failure",
                new AgentDefinitionId("compaction-failure-agent"),
                Optional.empty(),
                "default",
                new AgentSessionId("compaction-failure-session"),
                Optional.empty(),
                "initial user turn",
                List.of(),
                RuntimeOverrides.NONE));
        return store.find(accepted.runId()).orElseThrow();
    }

    static void consumeModelCalls(InMemoryRuntimeStore store, AgentRun run, long count) {
        recordUsage(store, run, new AgentRunUsageDelta(0, 0, 0, count, 0, 0, 0, 0));
    }

    static void recordUsage(InMemoryRuntimeStore store, AgentRun run, AgentRunUsageDelta usage) {
        long version = run.version();
        run.recordUsage(usage);
        store.save(run, version);
    }

    private static AgentChatResponse response() {
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
}
