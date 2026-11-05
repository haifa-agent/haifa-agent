package io.haifa.agent.runtime.core.compaction;

import static io.haifa.agent.runtime.core.compaction.CompactionTestSupport.consumeModelCalls;
import static io.haifa.agent.runtime.core.compaction.CompactionTestSupport.createRun;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.context.compression.CompressionPolicy;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.AgentChatRequest;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.runtime.core.bootstrap.RuntimeConfigurationSnapshot;
import io.haifa.agent.runtime.core.control.CancellationObservedException;
import io.haifa.agent.runtime.core.control.RunControlRegistry;
import io.haifa.agent.runtime.core.guard.RuntimeLimitExceededException;
import io.haifa.agent.runtime.core.lifecycle.RunAwaiter;
import io.haifa.agent.runtime.core.lifecycle.RunTransitionCoordinator;
import io.haifa.agent.runtime.core.model.FrozenModelBinding;
import io.haifa.agent.runtime.core.storage.InMemoryRuntimeStore;
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
        assertThat(capturedRequest.get().tools()).isEmpty();
        assertThat(capturedRequest.get().structuredOutput()).hasValueSatisfying(requirement -> {
            assertThat(requirement.responseName()).isEqualTo("SemanticConversationSummaryV1");
            assertThat(requirement.jsonSchema())
                    .isEqualTo(io.haifa.agent.context.compression.SemanticSummarySchema.jsonSchema());
        });
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

    @Test
    void timeoutIsClippedToRemainingActiveWallTime() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AgentRun run = createRun(store);
        AtomicReference<Instant> clock =
                new AtomicReference<>(NOW.plusMillis(run.limits().maxWallTimeMillis() - 7));
        SummaryModelInvoker invoker = invoker(store, new RunControlRegistry(), clock);
        AtomicReference<AgentChatRequest> captured = new AtomicReference<>();
        invoker.invoke(
                bindingWithMaxOutput(
                        store,
                        run,
                        request -> {
                            captured.set(request);
                            return structuredResponse();
                        },
                        8192),
                run,
                1,
                "system",
                "source",
                0,
                false);
        assertThat(captured.get().timeout().toMillis()).isEqualTo(7);
    }

    @Test
    void exactlyExpiredWallTimeDoesNotDispatchOrConsumeACall() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AgentRun run = createRun(store);
        AtomicReference<Instant> clock =
                new AtomicReference<>(NOW.plusMillis(run.limits().maxWallTimeMillis()));
        SummaryModelInvoker invoker = invoker(store, new RunControlRegistry(), clock);
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> invoker.invoke(
                        bindingWithMaxOutput(
                                store,
                                run,
                                request -> {
                                    calls.incrementAndGet();
                                    return structuredResponse();
                                },
                                8192),
                        run,
                        1,
                        "system",
                        "source",
                        0,
                        false))
                .isInstanceOf(RuntimeLimitExceededException.class)
                .hasFieldOrPropertyWithValue("resource", "wallTimeMillis");
        assertThat(calls).hasValue(0);
        assertThat(run.usage().modelCalls()).isZero();
    }

    @Test
    void lastAdmittedCallReturnsButDoesNotAdmitRepair() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AgentRun run = createRun(store);
        consumeModelCalls(store, run, run.limits().maxModelCalls() - 1);
        SummaryModelInvoker invoker = invoker(store, new RunControlRegistry(), new AtomicReference<>(NOW));
        AtomicInteger calls = new AtomicInteger();
        FrozenModelBinding binding = bindingWithMaxOutput(
                store,
                run,
                request -> {
                    calls.incrementAndGet();
                    return structuredResponse();
                },
                8192);
        assertThat(invoker.invoke(binding, run, 1, "system", "source", 0, false))
                .isNotNull();
        assertThatThrownBy(() -> invoker.invoke(binding, run, 1, "system", "repair", 1, true))
                .isInstanceOf(RuntimeLimitExceededException.class)
                .hasFieldOrPropertyWithValue("resource", "modelCalls");
        assertThat(calls).hasValue(1);
    }

    @Test
    void responseUsageIsRetainedWhenStopOrDeadlineIsObservedAfterReturn() {
        for (boolean deadline : List.of(false, true)) {
            responseUsageIsRetainedWhenStopOrDeadlineIsObservedAfterReturn(deadline);
        }
    }

    private void responseUsageIsRetainedWhenStopOrDeadlineIsObservedAfterReturn(boolean deadline) {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AgentRun run = createRun(store);
        AtomicReference<Instant> clock = new AtomicReference<>(NOW);
        RunControlRegistry controls = new RunControlRegistry();
        SummaryModelInvoker invoker = invoker(store, controls, clock);
        FrozenModelBinding binding = bindingWithMaxOutput(
                store,
                run,
                request -> {
                    if (deadline) clock.set(NOW.plusMillis(run.limits().maxWallTimeMillis()));
                    else controls.requestCancel(run.id());
                    return structuredResponse();
                },
                8192);
        assertThatThrownBy(() -> invoker.invoke(binding, run, 1, "system", "source", 0, false))
                .isInstanceOf(deadline ? RuntimeLimitExceededException.class : CancellationObservedException.class);
        assertThat(run.usage().modelCalls()).isEqualTo(1);
        assertThat(run.usage().inputTokens()).isEqualTo(10);
        assertThat(run.usage().outputTokens()).isEqualTo(10);
    }

    private static SummaryModelInvoker invoker(
            InMemoryRuntimeStore store, RunControlRegistry controls, AtomicReference<Instant> clock) {
        AtomicInteger ids = new AtomicInteger();
        IdentifierGenerator generator = () -> "guard-" + ids.incrementAndGet();
        TimeProvider time = clock::get;
        RunTransitionCoordinator transitions =
                new RunTransitionCoordinator(store, store, store, store, generator, time, new RunAwaiter(), store);
        return new SummaryModelInvoker(transitions, controls, generator, time, CompressionPolicy.defaults());
    }

    private static AgentChatResponse structuredResponse() {
        return new AgentChatResponse(
                "response",
                "model",
                "",
                List.of(),
                ModelFinishReason.STOP,
                ModelUsage.unpriced(10, 10),
                "",
                Map.of(),
                Optional.empty(),
                Optional.of(VALID_SUMMARY_MAP));
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
