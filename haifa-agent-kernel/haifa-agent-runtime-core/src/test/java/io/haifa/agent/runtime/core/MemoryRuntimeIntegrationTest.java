package io.haifa.agent.runtime.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.memory.api.Memory;
import io.haifa.agent.memory.api.MemoryActor;
import io.haifa.agent.memory.api.MemoryDraft;
import io.haifa.agent.memory.api.MemoryKind;
import io.haifa.agent.memory.api.MemoryRetriever;
import io.haifa.agent.memory.api.MemoryScope;
import io.haifa.agent.memory.core.DefaultMemoryRetriever;
import io.haifa.agent.memory.core.DefaultMemoryService;
import io.haifa.agent.memory.core.InMemoryMemoryStore;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.runtime.api.AgentRunRequest;
import io.haifa.agent.runtime.api.RuntimeOverrides;
import io.haifa.agent.runtime.core.execution.ManualExecutionScheduler;
import io.haifa.agent.runtime.core.storage.InMemoryRuntimeStore;
import io.haifa.agent.runtime.core.storage.RuntimePersistencePorts;
import io.haifa.agent.runtime.core.trace.RuntimeTraceEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MemoryRuntimeIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-07-21T00:00:00Z");
    private static final TenantRef TENANT = new TenantRef("local");
    private static final PrincipalRef OWNER = new PrincipalRef("local-user", "user");
    private static final MemoryActor ACTOR = new MemoryActor(TENANT, OWNER);

    private final InMemoryMemoryStore memories = new InMemoryMemoryStore();
    private final AtomicInteger memoryIds = new AtomicInteger();
    private final AtomicInteger clock = new AtomicInteger();
    private final DefaultMemoryService memoryService = new DefaultMemoryService(
            memories, () -> "memory-" + memoryIds.incrementAndGet(), () -> NOW.plusMillis(clock.incrementAndGet()));
    private final InMemoryRuntimeStore runtimeStore = new InMemoryRuntimeStore();
    private final ManualExecutionScheduler scheduler = new ManualExecutionScheduler();
    private final AtomicReference<AgentChatRequest> modelRequest = new AtomicReference<>();
    private final List<RuntimeTraceEvent> traces = new ArrayList<>();
    private final AtomicInteger runtimeIds = new AtomicInteger();

    @Test
    void agentMemoryEntersContextByReferenceAndDeleteStopsTheNextRecall() {
        var runtime = runtime(new DefaultMemoryRetriever(memories));
        Memory memory = memoryService.put(
                MemoryDraft.of(
                        MemoryScope.agent(TENANT, OWNER, "builder-agent"),
                        MemoryKind.PREFERENCE,
                        "build-tool",
                        "remembered build preference is Maven"),
                ACTOR);
        memoryService.put(
                MemoryDraft.of(
                        MemoryScope.agent(TENANT, OWNER, "other-agent"),
                        MemoryKind.PREFERENCE,
                        "build-tool",
                        "remembered build preference is Gradle"),
                ACTOR);

        var first = start(runtime, "memory-runtime-1", "remembered build preference");
        scheduler.runAll();

        assertThat(modelRequest.get().messages()).anySatisfy(message -> assertThat(message.content())
                .contains("[memory " + memory.id().value() + "@1]")
                .contains("remembered build preference is Maven")
                .doesNotContain("Gradle"));
        assertThat(runtimeStore.memorySelection(first.runId()).orElseThrow().memories())
                .singleElement()
                .satisfies(reference -> {
                    assertThat(reference.id()).isEqualTo(memory.id());
                    assertThat(reference.revision()).isEqualTo(1);
                });
        assertThat(traces).allSatisfy(trace -> assertThat(trace.safeAttributes().toString())
                .doesNotContain("remembered build preference is Maven"));

        memoryService.delete(memory.id(), memory.revision(), ACTOR);
        modelRequest.set(null);
        start(runtime, "memory-runtime-2", "remembered build preference");
        scheduler.runAll();

        assertThat(modelRequest.get().messages()).allSatisfy(message -> assertThat(message.content())
                .doesNotContain("remembered build preference is Maven"));
    }

    @Test
    void recallCanBeSwitchedOffForOneAgent() {
        memoryService.put(
                MemoryDraft.of(
                        MemoryScope.user(TENANT, OWNER), MemoryKind.FACT, "editor", "remembered editor is IntelliJ"),
                ACTOR);
        MemoryRetriever retriever = new DefaultMemoryRetriever(memories)
                .onlyWhen(request -> !request.agentId().equals("builder-agent"));
        var runtime = runtime(retriever);

        start(runtime, "memory-runtime-off", "remembered editor");
        scheduler.runAll();

        assertThat(modelRequest.get().messages())
                .allSatisfy(message -> assertThat(message.content()).doesNotContain("remembered editor is IntelliJ"));
    }

    private DefaultAgentRuntime runtime(MemoryRetriever retriever) {
        return new RuntimeCoreBuilder()
                .registerChatModel("openai-compatible", "1.0.0", request -> {
                    modelRequest.set(request);
                    return new AgentChatResponse(
                            "memory-response",
                            "deepseek-v4-pro",
                            "done",
                            List.of(),
                            ModelFinishReason.STOP,
                            ModelUsage.unpriced(2, 2),
                            "",
                            Map.of());
                })
                .persistence(RuntimePersistencePorts.inMemory(runtimeStore))
                .scheduler(scheduler)
                .identifierGenerator(() -> "runtime-memory-" + runtimeIds.incrementAndGet())
                .timeProvider(() -> NOW)
                .trace(traces::add)
                .memory(retriever)
                .build();
    }

    private static io.haifa.agent.runtime.api.AgentRunSnapshot start(
            DefaultAgentRuntime runtime, String key, String objective) {
        return runtime.start(new AgentRunRequest(
                key,
                new AgentDefinitionId("builder-agent"),
                Optional.empty(),
                "default",
                new AgentSessionId(key + "-session"),
                Optional.empty(),
                objective,
                List.of(),
                RuntimeOverrides.NONE));
    }
}
