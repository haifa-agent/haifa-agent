package io.haifa.agent.runtime.core.compaction;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.context.compression.CompressionPolicy;
import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.runtime.api.AgentRunRequest;
import io.haifa.agent.runtime.api.RuntimeOverrides;
import io.haifa.agent.runtime.core.DefaultAgentRuntime;
import io.haifa.agent.runtime.core.RuntimeCoreBuilder;
import io.haifa.agent.runtime.core.bootstrap.RuntimeConfigurationSnapshot;
import io.haifa.agent.runtime.core.control.RunControlRegistry;
import io.haifa.agent.runtime.core.execution.ManualExecutionScheduler;
import io.haifa.agent.runtime.core.lifecycle.RunAwaiter;
import io.haifa.agent.runtime.core.lifecycle.RunTransitionCoordinator;
import io.haifa.agent.runtime.core.model.FrozenModelBinding;
import io.haifa.agent.runtime.core.storage.InMemoryRuntimeStore;
import io.haifa.agent.runtime.core.storage.RuntimePersistencePorts;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SummaryModelInvokerTest {
    private static final Instant NOW = Instant.parse("2026-07-21T00:00:00Z");

    private static final Map<String, Object> VALID_SUMMARY_MAP = Map.of(
            "schemaVersion", "v1",
            "language", "en",
            "goals", List.of(),
            "constraints", List.of(),
            "progress", Map.of("completed", List.of(), "active", List.of(), "blocked", List.of()),
            "decisions", List.of(),
            "nextSteps", List.of(),
            "criticalContext", List.of(),
            "unresolvedQuestions", List.of());

    @Test
    void maxOutputTokensIsCappedAt32768WhenModelSupportsLargerOutput() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AtomicInteger ids = new AtomicInteger();
        IdentifierGenerator generator = () -> "summary-invoker-" + ids.incrementAndGet();
        RunControlRegistry controls = new RunControlRegistry();
        CompressionPolicy policy = CompressionPolicy.defaults().withSemanticCompactionEnabled(true);
        TimeProvider time = () -> NOW;
        RunTransitionCoordinator transitions =
                new RunTransitionCoordinator(store, store, store, store, generator, time, new RunAwaiter(), store);
        SummaryModelInvoker invoker = new SummaryModelInvoker(transitions, controls, generator, time, policy);

        AgentRun run = createRun(store);
        AtomicReference<AgentChatRequest> capturedRequest = new AtomicReference<>();
        AgentChatModel chatModel = req -> {
            capturedRequest.set(req);
            return new AgentChatResponse(
                    "resp-1",
                    "test-model",
                    "{}",
                    List.of(),
                    ModelFinishReason.STOP,
                    new ModelUsage(10, 10, 0, 10, 0, false, 0),
                    "",
                    Map.of(),
                    Optional.empty(),
                    Optional.of(VALID_SUMMARY_MAP));
        };

        FrozenModelBinding binding = bindingWithMaxOutput(store, run, chatModel, 65_536);

        invoker.invoke(binding, run, 1, "system prompt", "user prompt", 1, false);

        assertThat(capturedRequest.get()).isNotNull();
        // Capped at 32768, NOT the old hardcoded 4096!
        assertThat(capturedRequest.get().maxOutputTokens()).isEqualTo(32_768);
    }

    @Test
    void maxOutputTokensHonorsModelBoundWhenModelLimitIsSmallerThan32768() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AtomicInteger ids = new AtomicInteger();
        IdentifierGenerator generator = () -> "summary-invoker-" + ids.incrementAndGet();
        RunControlRegistry controls = new RunControlRegistry();
        CompressionPolicy policy = CompressionPolicy.defaults().withSemanticCompactionEnabled(true);
        TimeProvider time = () -> NOW;
        RunTransitionCoordinator transitions =
                new RunTransitionCoordinator(store, store, store, store, generator, time, new RunAwaiter(), store);
        SummaryModelInvoker invoker = new SummaryModelInvoker(transitions, controls, generator, time, policy);

        AgentRun run = createRun(store);
        AtomicReference<AgentChatRequest> capturedRequest = new AtomicReference<>();
        AgentChatModel chatModel = req -> {
            capturedRequest.set(req);
            return new AgentChatResponse(
                    "resp-1",
                    "test-model",
                    "{}",
                    List.of(),
                    ModelFinishReason.STOP,
                    new ModelUsage(10, 10, 0, 10, 0, false, 0),
                    "",
                    Map.of(),
                    Optional.empty(),
                    Optional.of(VALID_SUMMARY_MAP));
        };

        FrozenModelBinding binding = bindingWithMaxOutput(store, run, chatModel, 8_192);

        invoker.invoke(binding, run, 1, "system prompt", "user prompt", 1, false);

        assertThat(capturedRequest.get()).isNotNull();
        // Honors smaller model limit 8192
        assertThat(capturedRequest.get().maxOutputTokens()).isEqualTo(8_192);
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
                .identifierGenerator(() -> "run-" + ids.incrementAndGet())
                .timeProvider(() -> NOW)
                .build();
        var accepted = runtime.start(new AgentRunRequest(
                "summary-invoker-run",
                new AgentDefinitionId("summary-invoker-agent"),
                Optional.empty(),
                "default",
                new AgentSessionId("summary-invoker-session"),
                Optional.empty(),
                "initial user turn",
                List.of(),
                RuntimeOverrides.NONE));
        return store.find(accepted.runId()).orElseThrow();
    }

    private static FrozenModelBinding bindingWithMaxOutput(
            InMemoryRuntimeStore store, AgentRun run, AgentChatModel model, int maxOutputTokens) {
        RuntimeConfigurationSnapshot configuration =
                store.configuration(run.configurationSnapshot()).orElseThrow();
        ResolvedModelSnapshot original = configuration.model();
        ResolvedModelSnapshot resized = ResolvedModelSnapshot.create(
                original.providerId(),
                original.providerVersion(),
                original.modelId(),
                original.modelVersion(),
                original.providerModelId(),
                original.adapterType(),
                original.adapterVersion(),
                original.apiStyle(),
                original.dialect(),
                original.endpoint(),
                original.credentialRef(),
                original.nativeStreaming(),
                original.capabilities(),
                original.contextWindow(),
                maxOutputTokens,
                original.providerOptions(),
                original.invocationOptions());
        RuntimeConfigurationSnapshot resizedConfiguration = new RuntimeConfigurationSnapshot(
                configuration.reference(),
                configuration.definitionId(),
                configuration.definitionVersion(),
                configuration.profileId(),
                configuration.profileVersion(),
                configuration.runType(),
                configuration.budget(),
                configuration.limits(),
                configuration.toolBindings(),
                configuration.skillBindings(),
                configuration.skillCatalogDigest(),
                configuration.skillResolutionPolicyRef(),
                configuration.skillTrust(),
                configuration.allowedChildAgents(),
                configuration.agentInstruction(),
                configuration.overrides(),
                configuration.capabilities(),
                resized,
                configuration.modelRequestOptions(),
                configuration.structuredOutput());
        return new FrozenModelBinding(resizedConfiguration, model, List.of());
    }
}
