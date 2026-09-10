package io.haifa.agent.runtime.core;

import static org.assertj.core.api.Assertions.assertThatCode;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.memory.api.Memory;
import io.haifa.agent.memory.api.MemoryAuditSink;
import io.haifa.agent.memory.api.MemoryId;
import io.haifa.agent.memory.api.MemoryQuery;
import io.haifa.agent.memory.api.MemoryRetrieval;
import io.haifa.agent.memory.api.MemoryRetriever;
import io.haifa.agent.memory.api.MemoryVersion;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelUsage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RuntimeCoreBuilderMemoryAssemblyTest {
    private static final MemoryRetriever EMPTY_RETRIEVER = new MemoryRetriever() {
        @Override
        public MemoryRetrieval retrieve(MemoryQuery query) {
            return new MemoryRetrieval(List.of(), "custom-memory-policy", "sha256:custom-memory-query");
        }

        @Override
        public Optional<Memory> findAuthorized(
                MemoryId id, MemoryVersion version, TenantRef tenant, PrincipalRef owner, Instant now) {
            return Optional.empty();
        }
    };

    private static final MemoryAuditSink CUSTOM_AUDIT = event -> {};

    @Test
    void buildsWithAllMemoryDefaults() {
        assertThatCode(() -> builder().build()).doesNotThrowAnyException();
    }

    @Test
    void buildsWithOnlyACustomMemoryRetriever() {
        assertThatCode(() -> builder().memoryRetriever(EMPTY_RETRIEVER).build()).doesNotThrowAnyException();
    }

    @Test
    void buildsWithOnlyACustomMemoryAuditSink() {
        assertThatCode(() -> builder().memoryAudit(CUSTOM_AUDIT).build()).doesNotThrowAnyException();
    }

    @Test
    void buildsWithCustomMemoryRetrieverAndAuditSink() {
        assertThatCode(() -> builder().memory(EMPTY_RETRIEVER, CUSTOM_AUDIT).build()).doesNotThrowAnyException();
    }

    private static RuntimeCoreBuilder builder() {
        return new RuntimeCoreBuilder().registerChatModel(
                "test",
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
