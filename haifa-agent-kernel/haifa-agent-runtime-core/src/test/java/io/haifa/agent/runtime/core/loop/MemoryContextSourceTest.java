package io.haifa.agent.runtime.core.loop;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.message.AgentMessageId;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.message.MessageStatus;
import io.haifa.agent.core.message.MessageVisibility;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.run.AgentRun;
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
import io.haifa.agent.runtime.core.DefaultAgentRuntime;
import io.haifa.agent.runtime.core.RuntimeCoreBuilder;
import io.haifa.agent.runtime.core.execution.ManualExecutionScheduler;
import io.haifa.agent.runtime.core.model.FrozenModelBinding;
import io.haifa.agent.runtime.core.storage.InMemoryRuntimeStore;
import io.haifa.agent.runtime.core.storage.RuntimePersistencePorts;
import io.haifa.agent.runtime.core.storage.SessionMessageDraft;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MemoryContextSourceTest {
    private static final Instant NOW = Instant.parse("2026-07-21T00:00:00Z");

    @Test
    void retrievesAndWritesMemorySelectionOncePerCompletedUserTurn() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AgentRun run = createRun(store);
        List<String> queryTexts = new ArrayList<>();
        AtomicInteger retrievals = new AtomicInteger();
        MemoryContextSource source = new MemoryContextSource(retriever(retrievals, queryTexts), store, () -> NOW);
        FrozenModelBinding binding = binding(store, run);
        AgentLoopContext loopContext = new AgentLoopContext(1);

        source.select(run, binding, loopContext);
        source.select(run, binding, loopContext);

        assertThat(retrievals).hasValue(1);
        assertThat(store.memorySelection(run.id())).hasValueSatisfying(selection -> {
            assertThat(selection.retrievalPolicyVersion()).isEqualTo("test-memory-policy");
            assertThat(selection.queryDigest()).isEqualTo("sha256:query-1");
        });

        store.appendSessionMessage(new SessionMessageDraft(
                new AgentMessageId("memory-turn-two"),
                run.sessionId(),
                Optional.of(run.id()),
                Optional.empty(),
                MessageRole.USER,
                MessageStatus.COMPLETED,
                MessageVisibility.USER_VISIBLE,
                List.of(new TextPart("second user turn", "plain")),
                Map.of(),
                NOW));

        source.select(run, binding, loopContext);

        assertThat(retrievals).hasValue(2);
        assertThat(queryTexts).containsExactly("first user turn", "second user turn");
        assertThat(store.memorySelection(run.id())).hasValueSatisfying(selection -> assertThat(selection.queryDigest())
                .isEqualTo("sha256:query-2"));
    }

    private static MemoryRetriever retriever(AtomicInteger retrievals, List<String> queryTexts) {
        return new MemoryRetriever() {
            @Override
            public MemoryRetrieval retrieve(MemoryQuery query) {
                int retrieval = retrievals.incrementAndGet();
                queryTexts.add(query.queryText());
                return new MemoryRetrieval(List.of(), "test-memory-policy", "sha256:query-" + retrieval);
            }

            @Override
            public Optional<Memory> findAuthorized(
                    MemoryId id, MemoryVersion version, TenantRef tenant, PrincipalRef owner, Instant now) {
                return Optional.empty();
            }
        };
    }

    private static AgentRun createRun(InMemoryRuntimeStore store) {
        AtomicInteger ids = new AtomicInteger();
        DefaultAgentRuntime runtime = new RuntimeCoreBuilder()
                .registerChatModel(
                        "openai-compatible",
                        "1.0.0",
                        request -> new AgentChatResponse(
                                "test-response",
                                "test-model",
                                "done",
                                List.of(),
                                ModelFinishReason.STOP,
                                ModelUsage.unpriced(1, 1),
                                "",
                                Map.of()))
                .persistence(RuntimePersistencePorts.inMemory(store))
                .scheduler(new ManualExecutionScheduler())
                .identifierGenerator(() -> "memory-test-" + ids.incrementAndGet())
                .timeProvider(() -> NOW)
                .build();
        var accepted = runtime.start(new AgentRunRequest(
                "memory-turn-cache",
                new AgentDefinitionId("memory-test-agent"),
                Optional.empty(),
                "default",
                new AgentSessionId("memory-turn-cache-session"),
                Optional.empty(),
                "first user turn",
                List.of(),
                RuntimeOverrides.NONE));
        return store.find(accepted.runId()).orElseThrow();
    }

    private static FrozenModelBinding binding(InMemoryRuntimeStore store, AgentRun run) {
        return new FrozenModelBinding(
                store.configuration(run.configurationSnapshot()).orElseThrow(),
                request -> new AgentChatResponse(
                        "test-response",
                        "test-model",
                        "done",
                        List.of(),
                        ModelFinishReason.STOP,
                        ModelUsage.unpriced(1, 1),
                        "",
                        Map.of()),
                List.of());
    }
}
