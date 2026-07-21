package io.haifa.agent.runtime.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.reference.PrincipalRef;
import io.haifa.agent.core.reference.TenantRef;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.memory.api.MemoryActor;
import io.haifa.agent.memory.api.MemoryCandidateDraft;
import io.haifa.agent.memory.api.MemoryEvidenceRef;
import io.haifa.agent.memory.api.MemoryKind;
import io.haifa.agent.memory.api.MemoryRetentionPolicy;
import io.haifa.agent.memory.api.MemoryScope;
import io.haifa.agent.memory.api.MemoryScopeType;
import io.haifa.agent.memory.api.MemorySourceRef;
import io.haifa.agent.memory.api.MemorySourceType;
import io.haifa.agent.memory.api.MemoryStatus;
import io.haifa.agent.memory.api.MemoryVisibility;
import io.haifa.agent.memory.api.TextMemoryContent;
import io.haifa.agent.memory.core.DefaultMemoryPolicy;
import io.haifa.agent.memory.core.DefaultMemoryRetriever;
import io.haifa.agent.memory.core.DefaultMemoryService;
import io.haifa.agent.memory.core.InMemoryMemoryEvidenceVerifier;
import io.haifa.agent.memory.core.InMemoryMemoryStore;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.runtime.api.AgentRunRequest;
import io.haifa.agent.runtime.api.RuntimeOverrides;
import io.haifa.agent.runtime.core.checkpoint.MemoryCheckpointValidator;
import io.haifa.agent.runtime.core.execution.ManualExecutionScheduler;
import io.haifa.agent.runtime.core.storage.InMemoryRuntimeStore;
import io.haifa.agent.runtime.core.trace.RuntimeTraceEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MemoryRuntimeIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-07-21T00:00:00Z");

    @Test
    void authorizedMemoryEntersContextCheckpointUsesReferencesAndRedactionPreventsResumeRevival() {
        TenantRef tenant = new TenantRef("local");
        PrincipalRef owner = new PrincipalRef("local-user", "user");
        AgentSessionId session = new AgentSessionId("memory-session");
        InMemoryMemoryStore memories = new InMemoryMemoryStore();
        InMemoryMemoryEvidenceVerifier verifier = new InMemoryMemoryEvidenceVerifier();
        DefaultMemoryPolicy policy = new DefaultMemoryPolicy();
        AtomicInteger memoryIds = new AtomicInteger();
        DefaultMemoryService memoryService = new DefaultMemoryService(
                memories,
                memories,
                policy,
                verifier,
                List.of(),
                memories,
                () -> "governed-memory-" + memoryIds.incrementAndGet(),
                () -> NOW);
        DefaultMemoryRetriever retriever = new DefaultMemoryRetriever(memories, policy);
        InMemoryRuntimeStore runtimeStore = new InMemoryRuntimeStore();
        ManualExecutionScheduler scheduler = new ManualExecutionScheduler();
        AtomicReference<AgentChatRequest> modelRequest = new AtomicReference<>();
        List<RuntimeTraceEvent> traces = new ArrayList<>();
        AtomicInteger runtimeIds = new AtomicInteger();
        var runtime = new RuntimeCoreBuilder()
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
                .store(runtimeStore)
                .scheduler(scheduler)
                .identifierGenerator(() -> "runtime-memory-" + runtimeIds.incrementAndGet())
                .timeProvider(() -> NOW)
                .trace(traces::add)
                .memory(memoryService, retriever, memories)
                .build();

        var accepted = runtime.start(new AgentRunRequest(
                "memory-runtime",
                new AgentDefinitionId("agent"),
                Optional.empty(),
                "default",
                session,
                Optional.empty(),
                "remembered build preference",
                List.of(),
                RuntimeOverrides.NONE));
        var sourceMessage = runtimeStore.messages(accepted.runId()).getFirst();
        MemoryScope scope = new MemoryScope(
                tenant, owner, MemoryScopeType.SESSION, session.value(), MemoryVisibility.OWNER_ONLY, Set.of());
        MemorySourceRef source =
                new MemorySourceRef(MemorySourceType.MESSAGE, sourceMessage.id().value(), Optional.empty());
        MemoryEvidenceRef evidence = new MemoryEvidenceRef(source, "sha256:runtime-source");
        verifier.register(scope, evidence);
        var candidate = memoryService.propose(
                new MemoryCandidateDraft(
                        "runtime-memory-candidate",
                        scope,
                        MemoryKind.PREFERENCE,
                        "build-tool",
                        new TextMemoryContent("remembered build preference is Maven"),
                        List.of(source),
                        List.of(evidence),
                        MemoryRetentionPolicy.RETAIN,
                        false),
                new MemoryActor(tenant, owner, Set.of("memory:review")));
        var memory = memoryService.approve(
                candidate.id(), new MemoryActor(tenant, owner, Set.of("memory:review")), "runtime-approve");

        scheduler.runAll();

        assertThat(modelRequest.get().messages()).anySatisfy(message -> assertThat(message.content())
                .contains("[memory " + memory.id().value() + "@1]")
                .contains("remembered build preference is Maven"));
        var checkpoint = runtimeStore
                .state(runtimeStore.latest(accepted.runId()).orElseThrow().id().value())
                .orElseThrow();
        assertThat(checkpoint.selectedMemories()).singleElement().satisfies(reference -> {
            assertThat(reference.id()).isEqualTo(memory.id());
            assertThat(reference.version()).isEqualTo(memory.version());
            assertThat(reference.scope()).isEqualTo(scope);
        });
        assertThat(checkpoint.memoryRetrievalPolicyVersion()).isEqualTo("memory-governance-v1");
        assertThat(checkpoint.memoryQueryDigest()).startsWith("sha256:");
        assertThat(checkpoint.toString()).doesNotContain("remembered build preference is Maven");
        assertThat(traces).allSatisfy(trace -> assertThat(trace.safeAttributes().toString())
                .doesNotContain("remembered build preference is Maven"));

        runtimeStore.redactMessage(sourceMessage.id());
        assertThat(memories.find(memory.id(), memory.version()).orElseThrow().status())
                .isEqualTo(MemoryStatus.INVALIDATED);
        new MemoryCheckpointValidator(retriever, memories, () -> NOW)
                .validate(runtimeStore.find(accepted.runId()).orElseThrow(), checkpoint);
        assertThat(memories.auditEvents())
                .anySatisfy(event -> assertThat(event.operation()).isEqualTo("checkpoint.memory-selection-changed"));
        assertThat(memories.auditEvents()).allSatisfy(event -> assertThat(event.toString())
                .doesNotContain("remembered build preference is Maven"));
    }
}
