package io.haifa.agent.runtime.core.compression;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.context.compression.CompressionPolicy;
import io.haifa.agent.context.compression.DeterministicContextCompressor;
import io.haifa.agent.context.compression.SemanticConversationSummaryV1;
import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.agent.AgentDefinitionVersion;
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
import io.haifa.agent.runtime.core.control.RunControlRegistry;
import io.haifa.agent.runtime.core.execution.ManualExecutionScheduler;
import io.haifa.agent.runtime.core.lifecycle.RunAwaiter;
import io.haifa.agent.runtime.core.lifecycle.RunTransitionCoordinator;
import io.haifa.agent.runtime.core.model.FrozenModelBinding;
import io.haifa.agent.runtime.core.retry.PersistenceRetryPolicy;
import io.haifa.agent.runtime.core.retry.RetryExecutor;
import io.haifa.agent.runtime.core.retry.Sleeper;
import io.haifa.agent.runtime.core.storage.InMemoryRuntimeStore;
import io.haifa.agent.runtime.core.storage.RuntimePersistencePorts;
import io.haifa.agent.runtime.core.storage.SessionMessageDraft;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SemanticCompactionCoordinatorTest {

    private static final Instant NOW = Instant.parse("2026-07-21T00:00:00Z");

    private static final String VALID_SUMMARY_JSON = """
            {
              "schemaVersion": "v1",
              "language": "en",
              "goals": [
                {
                  "stableItemId": "g1",
                  "text": "Refactor authentication flow",
                  "confidence": "OBSERVED",
                  "sourceRefs": ["m001"]
                }
              ],
              "constraints": [],
              "progress": {
                "completed": [
                  {
                    "stableItemId": "p1",
                    "text": "Extracted OAuth handler",
                    "confidence": "OBSERVED",
                    "sourceRefs": ["m001"]
                  }
                ],
                "active": [],
                "blocked": []
              },
              "decisions": [],
              "nextSteps": [
                {
                  "stableItemId": "n1",
                  "text": "Add token validation test",
                  "confidence": "OBSERVED",
                  "sourceRefs": ["m002"]
                }
              ],
              "criticalContext": [],
              "unresolvedQuestions": []
            }
            """;

    @Test
    @DisplayName("forceCompactOnOverflow executes semantic compaction and commits SemanticConversationSummaryV1")
    void testForceCompactOnOverflowSuccess() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        RunControlRegistry controls = new RunControlRegistry();
        AtomicInteger idGen = new AtomicInteger();
        IdentifierGenerator ids = () -> "id-" + idGen.incrementAndGet();
        TimeProvider time = () -> NOW;

        CompressionPolicy policy = CompressionPolicy.defaults().withSemanticCompactionEnabled(true).withTailTokenBounds(5, 50);

        RunAwaiter awaiter = new RunAwaiter();
        RunTransitionCoordinator transitions = new RunTransitionCoordinator(
                store, store, store, store, ids, time, awaiter, store,
                new RetryExecutor(Sleeper.threadSleep()), PersistenceRetryPolicy.none());

        SummaryModelInvoker invoker = new SummaryModelInvoker(transitions, controls, ids, time, policy);
        CompactionTriggerEvaluator evaluator = new CompactionTriggerEvaluator(policy);
        DeterministicContextCompressor deterministic = new DeterministicContextCompressor();

        SemanticCompactionCoordinator coordinator = new SemanticCompactionCoordinator(
                store, store, invoker, evaluator, policy, deterministic, ids, time, store);

        AgentRun run = createAndSaveRun(store);
        AgentSessionId session = run.sessionId();

        // Add 2 turns: Turn 1 (user + assistant), Turn 2 (user + assistant)
        store.appendSessionMessage(draft("m-u1", session, run.id().value(), MessageRole.USER, "First turn user message"));
        store.appendSessionMessage(draft("m-a1", session, run.id().value(), MessageRole.ASSISTANT, "First turn assistant reply"));
        store.appendSessionMessage(draft("m-u2", session, run.id().value(), MessageRole.USER, "Second turn user message"));
        store.appendSessionMessage(draft("m-a2", session, run.id().value(), MessageRole.ASSISTANT, "Second turn assistant reply"));

        AtomicInteger callCount = new AtomicInteger();
        AgentChatModel chatModel = request -> {
            callCount.incrementAndGet();
            return new AgentChatResponse(
                    "res-1",
                    "deepseek-v4-pro",
                    VALID_SUMMARY_JSON,
                    List.of(),
                    ModelFinishReason.STOP,
                    ModelUsage.unpriced(50, 20),
                    "",
                    Map.of());
        };

        FrozenModelBinding binding = createBinding(store, run, chatModel);

        // Act: Force compaction on overflow
        coordinator.forceCompactOnOverflow(run, 1, binding);

        // Assert: 1 call made, summary created and contains semantic summary
        assertThat(callCount.get()).isEqualTo(1);
        var latestOpt = store.latestValid(session);
        assertThat(latestOpt).isPresent();
        var summary = latestOpt.get();
        assertThat(summary.semanticSummary()).isPresent();

        SemanticConversationSummaryV1 semSummary = summary.semanticSummary().get();
        assertThat(semSummary.schemaVersion()).isEqualTo("v1");
        assertThat(semSummary.goals()).hasSize(1);
        assertThat(semSummary.goals().getFirst().text()).isEqualTo("Refactor authentication flow");
        assertThat(semSummary.progress().completed()).hasSize(1);

        // Verify markdown rendered
        String markdown = io.haifa.agent.context.compression.SemanticSummaryRenderer.renderMarkdown(semSummary);
        assertThat(markdown).contains("Refactor authentication flow");
    }

    @Test
    @DisplayName("repair call is executed when the first model summary fails validation")
    void testRepairCallOnValidationFailure() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        RunControlRegistry controls = new RunControlRegistry();
        AtomicInteger idGen = new AtomicInteger();
        IdentifierGenerator ids = () -> "id-" + idGen.incrementAndGet();
        TimeProvider time = () -> NOW;

        CompressionPolicy policy = CompressionPolicy.defaults().withSemanticCompactionEnabled(true).withTailTokenBounds(5, 50);

        RunAwaiter awaiter = new RunAwaiter();
        RunTransitionCoordinator transitions = new RunTransitionCoordinator(
                store, store, store, store, ids, time, awaiter, store,
                new RetryExecutor(Sleeper.threadSleep()), PersistenceRetryPolicy.none());

        SummaryModelInvoker invoker = new SummaryModelInvoker(transitions, controls, ids, time, policy);
        CompactionTriggerEvaluator evaluator = new CompactionTriggerEvaluator(policy);
        DeterministicContextCompressor deterministic = new DeterministicContextCompressor();

        SemanticCompactionCoordinator coordinator = new SemanticCompactionCoordinator(
                store, store, invoker, evaluator, policy, deterministic, ids, time, store);

        AgentRun run = createAndSaveRun(store);
        AgentSessionId session = run.sessionId();

        store.appendSessionMessage(draft("m-u1", session, run.id().value(), MessageRole.USER, "First turn user message"));
        store.appendSessionMessage(draft("m-a1", session, run.id().value(), MessageRole.ASSISTANT, "First turn assistant reply"));
        store.appendSessionMessage(draft("m-u2", session, run.id().value(), MessageRole.USER, "Second turn user message"));
        store.appendSessionMessage(draft("m-a2", session, run.id().value(), MessageRole.ASSISTANT, "Second turn assistant reply"));

        // Invalid JSON: completed item has INFERRED confidence (violates rule)
        String invalidSummaryJson = """
                {
                  "schemaVersion": "v1",
                  "language": "en",
                  "goals": [],
                  "constraints": [],
                  "progress": {
                    "completed": [
                      {
                        "stableItemId": "p1",
                        "text": "Completed task without evidence",
                        "confidence": "INFERRED",
                        "sourceRefs": ["m001"]
                      }
                    ],
                    "active": [],
                    "blocked": []
                  },
                  "decisions": [],
                  "nextSteps": [],
                  "criticalContext": [],
                  "unresolvedQuestions": []
                }
                """;

        AtomicInteger callCount = new AtomicInteger();
        List<String> userPrompts = new ArrayList<>();
        AgentChatModel chatModel = request -> {
            int call = callCount.incrementAndGet();
            userPrompts.add(request.messages().getLast().content());
            if (call == 1) {
                return new AgentChatResponse(
                        "res-1", "deepseek-v4-pro", invalidSummaryJson, List.of(),
                        ModelFinishReason.STOP, ModelUsage.unpriced(50, 20), "", Map.of());
            } else {
                return new AgentChatResponse(
                        "res-2", "deepseek-v4-pro", VALID_SUMMARY_JSON, List.of(),
                        ModelFinishReason.STOP, ModelUsage.unpriced(50, 20), "", Map.of());
            }
        };

        FrozenModelBinding binding = createBinding(store, run, chatModel);

        // Act: Force compaction on overflow
        coordinator.forceCompactOnOverflow(run, 1, binding);

        // Assert: 2 calls made (first failed validation, second repaired)
        assertThat(callCount.get()).isEqualTo(2);
        assertThat(userPrompts.get(1)).contains("The previous summary output failed validation");

        var latestOpt = store.latestValid(session);
        assertThat(latestOpt).isPresent();
        assertThat(latestOpt.get().semanticSummary().get().progress().completed().getFirst().text())
                .isEqualTo("Extracted OAuth handler");
    }

    @Test
    @DisplayName("overflow falls back to deterministic compressor when model fails and fallback is enabled")
    void testOverflowFallbackToDeterministic() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        RunControlRegistry controls = new RunControlRegistry();
        AtomicInteger idGen = new AtomicInteger();
        IdentifierGenerator ids = () -> "id-" + idGen.incrementAndGet();
        TimeProvider time = () -> NOW;

        CompressionPolicy policy = CompressionPolicy.defaults()
                .withSemanticCompactionEnabled(true)
                .withDegradedFallback(true)
                .withTailTokenBounds(5, 50);

        RunAwaiter awaiter = new RunAwaiter();
        RunTransitionCoordinator transitions = new RunTransitionCoordinator(
                store, store, store, store, ids, time, awaiter, store,
                new RetryExecutor(Sleeper.threadSleep()), PersistenceRetryPolicy.none());

        SummaryModelInvoker invoker = new SummaryModelInvoker(transitions, controls, ids, time, policy);
        CompactionTriggerEvaluator evaluator = new CompactionTriggerEvaluator(policy);
        DeterministicContextCompressor deterministic = new DeterministicContextCompressor();

        SemanticCompactionCoordinator coordinator = new SemanticCompactionCoordinator(
                store, store, invoker, evaluator, policy, deterministic, ids, time, store);

        AgentRun run = createAndSaveRun(store);
        AgentSessionId session = run.sessionId();

        store.appendSessionMessage(draft("m-u1", session, run.id().value(), MessageRole.USER, "First turn user message"));
        store.appendSessionMessage(draft("m-a1", session, run.id().value(), MessageRole.ASSISTANT, "First turn assistant reply"));
        store.appendSessionMessage(draft("m-u2", session, run.id().value(), MessageRole.USER, "Second turn user message"));
        store.appendSessionMessage(draft("m-a2", session, run.id().value(), MessageRole.ASSISTANT, "Second turn assistant reply"));

        // Chat model throws unexpected exception
        AgentChatModel failingModel = request -> {
            throw new RuntimeException("Model invocation network failure");
        };

        FrozenModelBinding binding = createBinding(store, run, failingModel);

        // Act: Force compaction on overflow
        coordinator.forceCompactOnOverflow(run, 1, binding);

        // Assert: Fallback summary was committed by deterministic compressor
        var latestOpt = store.latestValid(session);
        assertThat(latestOpt).isPresent();
        var summary = latestOpt.get();
        assertThat(summary.compressorVersion()).isEqualTo(deterministic.version());
    }

    @Test
    @DisplayName("evaluateAndCompactIfNeeded triggers compaction when soft threshold is reached")
    void testAutomaticCompactionOnSoftTokenThreshold() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        RunControlRegistry controls = new RunControlRegistry();
        AtomicInteger idGen = new AtomicInteger();
        IdentifierGenerator ids = () -> "id-" + idGen.incrementAndGet();
        TimeProvider time = () -> NOW;

        CompressionPolicy policy = CompressionPolicy.defaults()
                .withSemanticCompactionEnabled(true)
                .withTailTokenBounds(5, 50);

        RunAwaiter awaiter = new RunAwaiter();
        RunTransitionCoordinator transitions = new RunTransitionCoordinator(
                store, store, store, store, ids, time, awaiter, store,
                new RetryExecutor(Sleeper.threadSleep()), PersistenceRetryPolicy.none());

        SummaryModelInvoker invoker = new SummaryModelInvoker(transitions, controls, ids, time, policy);
        CompactionTriggerEvaluator evaluator = new CompactionTriggerEvaluator(policy);
        DeterministicContextCompressor deterministic = new DeterministicContextCompressor();

        SemanticCompactionCoordinator coordinator = new SemanticCompactionCoordinator(
                store, store, invoker, evaluator, policy, deterministic, ids, time, store);

        AgentRun run = createAndSaveRun(store);
        AgentSessionId session = run.sessionId();

        // 2 complete turns (turnCount = 2)
        store.appendSessionMessage(draft("m-u1", session, run.id().value(), MessageRole.USER, "First turn user message"));
        store.appendSessionMessage(draft("m-a1", session, run.id().value(), MessageRole.ASSISTANT, "First turn assistant reply"));
        store.appendSessionMessage(draft("m-u2", session, run.id().value(), MessageRole.USER, "Second turn user message"));
        store.appendSessionMessage(draft("m-a2", session, run.id().value(), MessageRole.ASSISTANT,
                "Second turn assistant reply with detailed content: " + "more details and explanation ".repeat(10)));

        AtomicInteger callCount = new AtomicInteger();
        AgentChatModel chatModel = request -> {
            callCount.incrementAndGet();
            return new AgentChatResponse(
                    "res-1", "deepseek-v4-pro", VALID_SUMMARY_JSON, List.of(),
                    ModelFinishReason.STOP, ModelUsage.unpriced(50, 20), "", Map.of());
        };

        FrozenModelBinding binding = createBindingWithContextWindow(store, run, chatModel, 4_000);

        // Act: Evaluate and compact if needed
        coordinator.evaluateAndCompactIfNeeded(run, 1, binding);

        // Assert: Compaction was triggered and summary was committed
        assertThat(callCount.get()).isEqualTo(1);
        var latestOpt = store.latestValid(session);
        assertThat(latestOpt).isPresent();
        assertThat(latestOpt.get().semanticSummary()).isPresent();
        assertThat(latestOpt.get().semanticSummary().get().schemaVersion()).isEqualTo("v1");
    }

    private static AgentRun createAndSaveRun(InMemoryRuntimeStore store) {
        ManualExecutionScheduler scheduler = new ManualExecutionScheduler();
        AtomicInteger ids = new AtomicInteger();
        DefaultAgentRuntime runtime = new RuntimeCoreBuilder()
                .registerChatModel("openai-compatible", "1.0.0", request -> new AgentChatResponse(
                        "init", "deepseek-v4-pro", "hello", List.of(), ModelFinishReason.STOP, ModelUsage.unpriced(1, 1), "", Map.of()))
                .persistence(RuntimePersistencePorts.inMemory(store))
                .scheduler(scheduler)
                .identifierGenerator(() -> "id-" + ids.incrementAndGet())
                .timeProvider(() -> NOW)
                .build();
        var accepted = runtime.start(new AgentRunRequest(
                "key-1",
                new AgentDefinitionId("test-agent"),
                Optional.empty(),
                "default",
                new AgentSessionId("session-1"),
                Optional.empty(),
                "objective",
                List.of(),
                RuntimeOverrides.NONE));
        return store.find(accepted.runId()).orElseThrow();
    }

    private static FrozenModelBinding createBinding(InMemoryRuntimeStore store, AgentRun run, AgentChatModel chatModel) {
        RuntimeConfigurationSnapshot config = store.configuration(run.configurationSnapshot()).orElseThrow();
        return new FrozenModelBinding(config, chatModel, List.of());
    }

    private static FrozenModelBinding createBindingWithContextWindow(
            InMemoryRuntimeStore store, AgentRun run, AgentChatModel chatModel, int contextWindow) {
        RuntimeConfigurationSnapshot config = store.configuration(run.configurationSnapshot()).orElseThrow();
        var orig = config.model();
        var smallModel = ResolvedModelSnapshot.create(
                orig.providerId(),
                orig.providerVersion(),
                orig.modelId(),
                orig.modelVersion(),
                orig.providerModelId(),
                orig.adapterType(),
                orig.adapterVersion(),
                orig.apiStyle(),
                orig.dialect(),
                orig.endpoint(),
                orig.credentialRef(),
                orig.nativeStreaming(),
                orig.capabilities(),
                contextWindow,
                256,
                orig.providerOptions(),
                orig.invocationOptions());
        var smallConfig = new RuntimeConfigurationSnapshot(
                config.reference(),
                config.definitionId(),
                config.definitionVersion(),
                config.profileId(),
                config.profileVersion(),
                config.runType(),
                config.budget(),
                config.limits(),
                config.toolBindings(),
                config.skillBindings(),
                config.skillCatalogDigest(),
                config.skillResolutionPolicyRef(),
                config.skillTrust(),
                config.allowedChildAgents(),
                config.agentInstruction(),
                config.overrides(),
                config.capabilities(),
                smallModel,
                config.modelRequestOptions(),
                config.structuredOutput());
        return new FrozenModelBinding(smallConfig, chatModel, List.of());
    }

    private static SessionMessageDraft draft(
            String id, AgentSessionId sessionId, String runId, MessageRole role, String text) {
        return new SessionMessageDraft(
                new AgentMessageId(id),
                sessionId,
                Optional.of(new AgentRunId(runId)),
                Optional.empty(),
                role,
                MessageStatus.COMPLETED,
                role == MessageRole.USER ? MessageVisibility.USER_VISIBLE : MessageVisibility.AGENT_VISIBLE,
                List.of(new TextPart(text, "plain")),
                Map.of(),
                NOW);
    }
}
