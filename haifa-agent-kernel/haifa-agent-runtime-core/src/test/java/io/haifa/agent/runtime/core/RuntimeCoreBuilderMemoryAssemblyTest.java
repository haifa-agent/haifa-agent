package io.haifa.agent.runtime.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.memory.api.Memory;
import io.haifa.agent.memory.api.MemoryId;
import io.haifa.agent.memory.api.MemoryQuery;
import io.haifa.agent.memory.api.MemoryRetrieval;
import io.haifa.agent.memory.api.MemoryRetriever;
import io.haifa.agent.memory.api.MemoryVersion;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.runtime.api.AgentRunRequest;
import io.haifa.agent.runtime.api.RuntimeOverrides;
import io.haifa.agent.runtime.core.execution.ManualExecutionScheduler;
import io.haifa.agent.runtime.core.storage.InMemoryRuntimeStore;
import io.haifa.agent.runtime.core.storage.RuntimePersistencePorts;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RuntimeCoreBuilderMemoryAssemblyTest {
    @Test
    void buildsWithAllMemoryDefaults() {
        assertThatCode(() -> builder().build()).doesNotThrowAnyException();
    }

    @Test
    void customMemoryRetrieverIsUsedForARun() {
        AtomicInteger retrieveCalls = new AtomicInteger();
        ManualExecutionScheduler scheduler = new ManualExecutionScheduler();
        var runtime = builder()
                .persistence(RuntimePersistencePorts.inMemory(new InMemoryRuntimeStore()))
                .scheduler(scheduler)
                .memory(retriever(retrieveCalls))
                .build();

        runtime.start(new AgentRunRequest(
                "memory-assembly",
                new AgentDefinitionId("agent"),
                Optional.empty(),
                "default",
                new AgentSessionId("memory-assembly-session"),
                Optional.empty(),
                "verify the configured memory retriever",
                List.of(),
                RuntimeOverrides.NONE));
        scheduler.runAll();

        assertThat(retrieveCalls).hasValue(1);
    }

    private static MemoryRetriever retriever(AtomicInteger retrieveCalls) {
        return new MemoryRetriever() {
            @Override
            public MemoryRetrieval retrieve(MemoryQuery query) {
                retrieveCalls.incrementAndGet();
                return new MemoryRetrieval(List.of(), "custom-memory-policy", "sha256:custom-memory-query");
            }

            @Override
            public Optional<Memory> findAuthorized(
                    MemoryId id, MemoryVersion version, TenantRef tenant, PrincipalRef owner, Instant now) {
                return Optional.empty();
            }
        };
    }

    private static RuntimeCoreBuilder builder() {
        return new RuntimeCoreBuilder()
                .registerChatModel(
                        "openai-compatible",
                        "1.0.0",
                        request -> new AgentChatResponse(
                                "memory-assembly",
                                "test-model",
                                "memory assembly test",
                                List.of(),
                                ModelFinishReason.STOP,
                                ModelUsage.unpriced(1, 1),
                                "",
                                Map.of()));
    }
}
