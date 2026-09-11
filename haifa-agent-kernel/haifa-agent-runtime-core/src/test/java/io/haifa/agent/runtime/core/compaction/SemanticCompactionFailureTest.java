package io.haifa.agent.runtime.core.compaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.context.compression.CompactionQuality;
import io.haifa.agent.context.compression.CompressionPolicy;
import io.haifa.agent.context.compression.DeterministicContextCompressor;
import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.message.AgentMessageId;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.message.MessageStatus;
import io.haifa.agent.core.message.MessageVisibility;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.model.api.AgentChatModel;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.runtime.api.AgentRunRequest;
import io.haifa.agent.runtime.api.RuntimeOverrides;
import io.haifa.agent.runtime.core.DefaultAgentRuntime;
import io.haifa.agent.runtime.core.RuntimeCoreBuilder;
import io.haifa.agent.runtime.core.bootstrap.RuntimeConfigurationSnapshot;
import io.haifa.agent.runtime.core.control.CancellationObservedException;
import io.haifa.agent.runtime.core.control.RunControlRegistry;
import io.haifa.agent.runtime.core.execution.ManualExecutionScheduler;
import io.haifa.agent.runtime.core.lifecycle.RunAwaiter;
import io.haifa.agent.runtime.core.lifecycle.RunTransitionCoordinator;
import io.haifa.agent.runtime.core.model.FrozenModelBinding;
import io.haifa.agent.runtime.core.storage.InMemoryRuntimeStore;
import io.haifa.agent.runtime.core.storage.RuntimePersistencePorts;
import io.haifa.agent.runtime.core.storage.SessionMessageDraft;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SemanticCompactionFailureTest {
    private static final Instant NOW = Instant.parse("2026-07-21T00:00:00Z");

    @Test
    void softThresholdFailureEmitsEventAndFallsBackWhenConfigured() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AtomicInteger ids = new AtomicInteger();
        IdentifierGenerator generator = () -> "compaction-failure-" + ids.incrementAndGet();
        CompressionPolicy policy = CompressionPolicy.defaults()
                .withSemanticCompactionEnabled(true)
                .withDegradedFallback(true)
                .withTailTokenBounds(5, 50);
        RunControlRegistry controls = new RunControlRegistry();
        SemanticCompactionCoordinator coordinator = coordinator(store, generator, controls, policy);
        AgentRun run = createRun(store);
        appendTurn(store, run, "u1", "a1", "first user turn", "first assistant reply " + "detail ".repeat(2_000));
        appendTurn(store, run, "u2", "a2", "second user turn", "second assistant reply " + "detail ".repeat(2_000));

        coordinator.evaluateAndCompactIfNeeded(
                run, 1, bindingWithContextWindow(store, run, request -> failure(), 4_000));

        assertThat(store.latestValid(run.sessionId())).hasValueSatisfying(summary -> assertThat(summary.quality())
                .isEqualTo(CompactionQuality.DETERMINISTIC_DEGRADED));
        assertThat(store.eventsFor(run.id()))
                .filteredOn(event -> event.type().equals("session.compaction-failed"))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.data())
                            .containsEntry("reason", CompactionTriggerReason.SOFT_TOKEN_THRESHOLD.name())
                            .containsEntry("failureCategory", "MODEL_OR_RUNTIME")
                            .containsEntry("validationErrorCode", "NONE")
                            .containsEntry("physicalCalls", 1)
                            .containsEntry("degraded", true);
                });
    }

    @Test
    void cancellationIsRethrownWithoutFallbackOrFailureEvent() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AtomicInteger ids = new AtomicInteger();
        IdentifierGenerator generator = () -> "compaction-cancel-" + ids.incrementAndGet();
        CompressionPolicy policy = CompressionPolicy.defaults()
                .withSemanticCompactionEnabled(true)
                .withDegradedFallback(true)
                .withTailTokenBounds(5, 50);
        RunControlRegistry controls = new RunControlRegistry();
        SemanticCompactionCoordinator coordinator = coordinator(store, generator, controls, policy);
        AgentRun run = createRun(store);
        appendTurn(store, run, "u1", "a1", "first user turn", "first assistant reply");
        appendTurn(store, run, "u2", "a2", "second user turn", "second assistant reply");
        controls.requestCancel(run.id());

        assertThatThrownBy(() -> coordinator.forceCompactOnOverflow(run, 1, binding(store, run, request -> failure())))
                .isInstanceOf(CancellationObservedException.class);

        assertThat(store.latestValid(run.sessionId())).isEmpty();
        assertThat(store.eventsFor(run.id())).noneMatch(event -> event.type().equals("session.compaction-failed"));
    }

    @Test
    void validationFailureEmitsOnlyTheStableValidationCode() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AtomicInteger ids = new AtomicInteger();
        IdentifierGenerator generator = () -> "compaction-validation-" + ids.incrementAndGet();
        CompressionPolicy policy = CompressionPolicy.defaults()
                .withSemanticCompactionEnabled(true)
                .withDegradedFallback(true)
                .withTailTokenBounds(5, 50);
        SemanticCompactionCoordinator coordinator = coordinator(store, generator, new RunControlRegistry(), policy);
        AgentRun run = createRun(store);
        appendTurn(store, run, "u1", "a1", "first user turn", "first assistant reply");
        appendTurn(store, run, "u2", "a2", "second user turn", "second assistant reply");

        coordinator.forceCompactOnOverflow(run, 1, binding(store, run, request -> invalidJsonResponse()));

        assertThat(store.latestValid(run.sessionId())).hasValueSatisfying(summary -> assertThat(summary.quality())
                .isEqualTo(CompactionQuality.DETERMINISTIC_DEGRADED));
        assertThat(store.eventsFor(run.id()))
                .filteredOn(event -> event.type().equals("session.compaction-failed"))
                .singleElement()
                .satisfies(event -> assertThat(event.data())
                        .containsEntry("failureCategory", "VALIDATION")
                        .containsEntry("validationErrorCode", "VALIDATION_REJECTED")
                        .doesNotContainValue("not-valid-json"));
    }

    private static SemanticCompactionCoordinator coordinator(
            InMemoryRuntimeStore store,
            IdentifierGenerator ids,
            RunControlRegistry controls,
            CompressionPolicy policy) {
        TimeProvider time = () -> NOW;
        RunTransitionCoordinator transitions =
                new RunTransitionCoordinator(store, store, store, store, ids, time, new RunAwaiter(), store);
        return new SemanticCompactionCoordinator(
                store,
                store,
                new SummaryModelInvoker(transitions, controls, ids, time, policy),
                new CompactionTriggerEvaluator(policy),
                policy,
                new DeterministicContextCompressor(),
                ids,
                time,
                store);
    }

    private static AgentRun createRun(InMemoryRuntimeStore store) {
        AtomicInteger ids = new AtomicInteger();
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

    private static void appendTurn(
            InMemoryRuntimeStore store,
            AgentRun run,
            String userId,
            String assistantId,
            String userText,
            String assistantText) {
        store.appendSessionMessage(draft(userId, run, MessageRole.USER, userText));
        store.appendSessionMessage(draft(assistantId, run, MessageRole.ASSISTANT, assistantText));
    }

    private static SessionMessageDraft draft(String id, AgentRun run, MessageRole role, String text) {
        return new SessionMessageDraft(
                new AgentMessageId(id),
                run.sessionId(),
                Optional.of(new AgentRunId(run.id().value())),
                Optional.empty(),
                role,
                MessageStatus.COMPLETED,
                role == MessageRole.USER ? MessageVisibility.USER_VISIBLE : MessageVisibility.AGENT_VISIBLE,
                List.of(new TextPart(text, "plain")),
                Map.of(),
                NOW);
    }

    private static FrozenModelBinding binding(InMemoryRuntimeStore store, AgentRun run, AgentChatModel model) {
        return new FrozenModelBinding(
                store.configuration(run.configurationSnapshot()).orElseThrow(), model, List.of());
    }

    private static FrozenModelBinding bindingWithContextWindow(
            InMemoryRuntimeStore store, AgentRun run, AgentChatModel model, int contextWindow) {
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
                contextWindow,
                256,
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

    private static AgentChatResponse response() {
        return new AgentChatResponse(
                "test-response",
                "test-model",
                "done",
                List.of(),
                ModelFinishReason.STOP,
                ModelUsage.unpriced(1, 1),
                "",
                Map.of());
    }

    private static AgentChatResponse failure() {
        throw new RuntimeException("injected model failure");
    }

    private static AgentChatResponse invalidJsonResponse() {
        return new AgentChatResponse(
                "invalid-response",
                "test-model",
                "not-valid-json",
                List.of(),
                ModelFinishReason.STOP,
                ModelUsage.unpriced(1, 1),
                "",
                Map.of());
    }
}
