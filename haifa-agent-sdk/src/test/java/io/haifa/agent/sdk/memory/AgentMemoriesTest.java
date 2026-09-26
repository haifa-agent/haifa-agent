package io.haifa.agent.sdk.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.memory.api.Memory;
import io.haifa.agent.memory.api.MemoryKind;
import io.haifa.agent.memory.api.MemoryScopeType;
import io.haifa.agent.memory.core.DefaultMemoryRetriever;
import io.haifa.agent.memory.core.DefaultMemoryService;
import io.haifa.agent.memory.core.InMemoryMemoryStore;
import io.haifa.agent.sdk.SdkTestFixtures;
import io.haifa.agent.sdk.api.HaifaAgent;
import io.haifa.agent.sdk.api.SdkCaller;
import io.haifa.agent.sdk.contribution.MemoryPlatformContribution;
import io.haifa.agent.sdk.product.ProductMemoryPolicy;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

class AgentMemoriesTest {
    private static final SdkCaller ALICE = new SdkCaller(new TenantRef("tenant-a"), new PrincipalRef("alice", "user"));
    private static final SdkCaller BOB = new SdkCaller(new TenantRef("tenant-a"), new PrincipalRef("bob", "user"));

    private final AtomicReference<SdkCaller> caller = new AtomicReference<>(ALICE);
    private final AtomicInteger ids = new AtomicInteger();

    @Test
    void directCrudIsBoundToTheTrustedCallerAndIsolatesAgentBuckets() {
        try (HaifaAgent agent = agent()) {
            AgentMemories memories = agent.memories().orElseThrow();
            Memory user = memories.put(
                    PutMemoryCommand.of(MemoryScopeSpec.user(), MemoryKind.FACT, "city", "Lives in Hangzhou"));
            Memory research = memories.put(PutMemoryCommand.of(
                    MemoryScopeSpec.agent("research"), MemoryKind.INSTRUCTION, "style", "Cite sources"));

            assertThat(user.scope().owner()).isEqualTo(ALICE.principal());
            assertThat(user.scope().type()).isEqualTo(MemoryScopeType.USER);
            assertThat(memories.list(MemoryListQuery.of(MemoryScopeSpec.agent("research"), 10))
                            .items())
                    .containsExactly(research);
            assertThat(memories.list(MemoryListQuery.of(MemoryScopeSpec.agent("writer"), 10))
                            .items())
                    .isEmpty();
            assertThat(memories.list(MemoryListQuery.of(MemoryScopeSpec.user(), 10))
                            .items())
                    .containsExactly(user);

            Memory updated = memories.update(user.id(), user.revision(), "Lives in Shanghai");
            assertThat(updated.revision()).isEqualTo(2);

            caller.set(BOB);
            assertThat(memories.find(user.id())).isEmpty();
            assertThat(memories.list(MemoryListQuery.of(MemoryScopeSpec.user(), 10))
                            .items())
                    .isEmpty();
            assertCode(() -> memories.update(user.id(), 2, "Lives in Beijing"), "MEMORY_UNAVAILABLE");
            assertCode(() -> memories.delete(user.id(), 2), "MEMORY_UNAVAILABLE");
            assertThat(memories.clear(MemoryScopeSpec.agent("research"))).isZero();

            caller.set(ALICE);
            assertThat(memories.find(user.id())).contains(updated);
            assertThat(memories.list(MemoryListQuery.of(MemoryScopeSpec.agent("research"), 10))
                            .items())
                    .containsExactly(research);
            memories.delete(updated.id(), updated.revision());
            assertThat(memories.find(updated.id())).isEmpty();
            assertThat(memories.clear(MemoryScopeSpec.agent("research"))).isEqualTo(1);
        }
    }

    @Test
    void callerCannotInjectAnotherOwnerOrUnknownSession() {
        assertThatThrownBy(() -> new MemoryScopeSpec(MemoryScopeType.USER, Optional.of("bob")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemoryScopeSpec(MemoryScopeType.AGENT, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        try (HaifaAgent agent = agent()) {
            AgentMemories memories = agent.memories().orElseThrow();
            assertCode(
                    () -> memories.put(PutMemoryCommand.of(
                            MemoryScopeSpec.session("missing-session"), MemoryKind.FACT, "topic", "Unknown session")),
                    "MEMORY_SCOPE_UNAVAILABLE");
        }
    }

    @Test
    void sensitiveOrOversizedContentIsRejected() {
        try (HaifaAgent agent = agent()) {
            AgentMemories memories = agent.memories().orElseThrow();
            assertCode(
                    () -> memories.put(PutMemoryCommand.of(
                            MemoryScopeSpec.user(), MemoryKind.FACT, "login", "my password is hunter2")),
                    "MEMORY_CONTENT_SENSITIVE");
            assertCode(
                    () -> memories.put(
                            PutMemoryCommand.of(MemoryScopeSpec.user(), MemoryKind.FACT, "essay", "x ".repeat(200))),
                    "MEMORY_INVALID_REQUEST");
            assertThat(memories.list(MemoryListQuery.of(MemoryScopeSpec.user(), 10))
                            .items())
                    .isEmpty();
        }
    }

    private HaifaAgent agent() {
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        return SdkTestFixtures.builder("memory-crud")
                .memory(new MemoryPlatformContribution(
                        new DefaultMemoryService(store, () -> "memory-" + ids.incrementAndGet(), () -> Instant.parse(
                                        "2026-09-25T00:00:00Z")
                                .plusMillis(ids.incrementAndGet())),
                        new DefaultMemoryRetriever(store),
                        new ProductMemoryPolicy(256, 50)))
                .callerProvider(caller::get)
                .build();
    }

    private static void assertCode(ThrowingCallable call, String code) {
        assertThatThrownBy(call)
                .isInstanceOf(MemoryException.class)
                .extracting("code")
                .isEqualTo(code);
    }
}
