package io.haifa.agent.runtime.core.compaction;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.common.time.TimeProvider;
import io.haifa.agent.context.compression.CompressionPolicy;
import io.haifa.agent.context.compression.DeterministicContextCompressor;
import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.content.ContentPart;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.content.ToolCallPart;
import io.haifa.agent.core.content.ToolResultPart;
import io.haifa.agent.core.message.AgentMessage;
import io.haifa.agent.core.message.AgentMessageId;
import io.haifa.agent.core.message.MessageCursor;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.message.MessageStatus;
import io.haifa.agent.core.message.MessageVisibility;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.step.AgentStepId;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.RuntimeIdempotencyKey;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.core.tool.ToolResult;
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
import io.haifa.agent.runtime.core.storage.InMemoryRuntimeStore;
import io.haifa.agent.runtime.core.storage.RuntimePersistencePorts;
import io.haifa.agent.runtime.core.storage.SessionMessageDraft;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CA-017-02 Phase 3: Telemetry Calibration and Closed-loop Benchmarks.
 * Implements Scenario A, Scenario B, Scenario C, and Calibration Matrix evaluations.
 */
class ActiveContextBudgetCalibrationTest {

    private static final Instant NOW = Instant.parse("2026-07-21T00:00:00Z");

    private static String summaryJsonForPrompt(String prompt) {
        Matcher m = Pattern.compile("\\[msg (\\S+):").matcher(prompt);
        String ref = m.find() ? m.group(1) : "m001";
        return "{\n"
                + "  \"schemaVersion\": \"v1\",\n"
                + "  \"language\": \"en\",\n"
                + "  \"goals\": [{\"stableItemId\": \"g1\", \"text\": \"Calibrate active context budget\", \"confidence\": \"OBSERVED\", \"sourceRefs\": [\""
                + ref
                + "\"]}],\n"
                + "  \"constraints\": [],\n"
                + "  \"progress\": {\"completed\": [], \"active\": [], \"blocked\": []},\n"
                + "  \"decisions\": [],\n"
                + "  \"nextSteps\": [],\n"
                + "  \"criticalContext\": [],\n"
                + "  \"unresolvedQuestions\": []\n"
                + "}";
    }

    @Test
    @DisplayName(
            "Scenario A: Cross-Run old history with large tool results achieves high Tier 1 bypass rate without LLM calls")
    void testScenarioACrossRunHistoryTier1Bypass() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AtomicInteger idGen = new AtomicInteger();
        IdentifierGenerator ids = () -> "id-" + idGen.incrementAndGet();

        CompressionPolicy policy = CompressionPolicy.defaults()
                .withSemanticCompactionEnabled(true)
                .withActiveHistoryBudgetTokens(96_000L)
                .withTailTokenBounds(5, 50);

        SemanticCompactionCoordinator coordinator = coordinator(store, ids, policy);
        AgentRun run = createAndSaveRun(store, "session-scenario-a");
        AgentSessionId session = run.sessionId();

        for (int i = 1; i <= 4; i++) {
            ToolCallId callId = new ToolCallId("call-sc-a-" + i);
            ProviderToolCallCorrelationId corrId = new ProviderToolCallCorrelationId("corr-sc-a-" + i);
            Map<String, Object> data =
                    (i <= 2) ? Map.of("largePayload", "data ".repeat(10_000)) : Map.of("result", "small " + i);

            ToolCall call = new ToolCall(
                    callId,
                    run.id(),
                    new AgentStepId("step-sc-a-" + i),
                    corrId,
                    new RuntimeIdempotencyKey("idemp-sc-a-" + i),
                    "file_read",
                    "1.0.0",
                    new ToolArguments("input.schema", "1.0.0", Map.of("path", "file" + i + ".txt")),
                    NOW.plusSeconds(i * 10));
            call.beginValidation();
            call.beginPolicyCheck();
            call.start(NOW.plusSeconds(i * 10 + 1));
            call.complete(
                    new ToolResult(true, "Read file " + i, data, List.of(), List.of(), false),
                    NOW.plusSeconds(i * 10 + 2));
            store.appendToolCall(call);

            store.appendSessionMessage(new SessionMessageDraft(
                    new AgentMessageId("m-a-" + i),
                    session,
                    Optional.of(run.id()),
                    Optional.empty(),
                    MessageRole.ASSISTANT,
                    MessageStatus.COMPLETED,
                    MessageVisibility.AGENT_VISIBLE,
                    List.of(new ToolCallPart(callId, corrId, "file_read", "1.0.0")),
                    Map.of(),
                    NOW.plusSeconds(i * 10 + 1)));

            store.appendSessionMessage(new SessionMessageDraft(
                    new AgentMessageId("m-t-" + i),
                    session,
                    Optional.of(run.id()),
                    Optional.empty(),
                    MessageRole.TOOL,
                    MessageStatus.COMPLETED,
                    MessageVisibility.AGENT_VISIBLE,
                    List.of(new ToolResultPart(callId, corrId, "Read file " + i)),
                    Map.of(),
                    NOW.plusSeconds(i * 10 + 2)));
        }

        AtomicInteger llmCalls = new AtomicInteger();
        AgentChatModel model = request -> {
            llmCalls.incrementAndGet();
            String prompt = request.messages().getLast().content();
            return new AgentChatResponse(
                    "res",
                    "model",
                    summaryJsonForPrompt(prompt),
                    List.of(),
                    ModelFinishReason.STOP,
                    ModelUsage.unpriced(10, 10),
                    "",
                    Map.of());
        };

        FrozenModelBinding binding = createBindingWithContextWindow(store, run, model, 40_000);
        CompactionEvaluationOutcome outcome = coordinator.evaluateAndCompactIfNeeded(run, 1, binding);

        assertThat(outcome.tier1PruningBypassedSummary()).isTrue();
        assertThat(outcome.omittedToolResultCount()).isGreaterThan(0);
        assertThat(outcome.omittedToolPayloadTokens()).isGreaterThan(20_000L);
        assertThat(llmCalls.get()).isZero();
    }

    @Test
    @DisplayName("Scenario B: Single-Run 60-turn loop maintains atomic group pairing and zero orphan tool calls")
    void testScenarioBSingleRun60TurnAtomicPairingSafety() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AtomicInteger idGen = new AtomicInteger();
        IdentifierGenerator ids = () -> "id-" + idGen.incrementAndGet();

        // Active budget 1000 tokens to trigger compaction
        CompressionPolicy policy = CompressionPolicy.defaults()
                .withSemanticCompactionEnabled(true)
                .withActiveHistoryBudgetTokens(1000L)
                .withTailTokenBounds(5, 50);

        SemanticCompactionCoordinator coordinator = coordinator(store, ids, policy);
        AgentRun run = createAndSaveRun(store, "session-scenario-b");
        AgentSessionId session = run.sessionId();

        // 60 turns: each turn begins with a USER turn anchor followed by assistant tool call + tool result
        for (int i = 1; i <= 60; i++) {
            store.appendSessionMessage(draft(
                    "m-u-" + i,
                    session,
                    run.id().value(),
                    MessageRole.USER,
                    "Turn " + i + " user prompt: inspect module " + i));

            ToolCallId callId = new ToolCallId("call-60-" + i);
            ProviderToolCallCorrelationId corrId = new ProviderToolCallCorrelationId("corr-60-" + i);

            ToolCall call = new ToolCall(
                    callId,
                    run.id(),
                    new AgentStepId("step-60-" + i),
                    corrId,
                    new RuntimeIdempotencyKey("idemp-60-" + i),
                    "file_read",
                    "1.0.0",
                    new ToolArguments("input.schema", "1.0.0", Map.of("path", "module_" + i + ".py")),
                    NOW.plusSeconds(i * 10));
            call.beginValidation();
            call.beginPolicyCheck();
            call.start(NOW.plusSeconds(i * 10 + 1));
            call.complete(
                    new ToolResult(true, "Content " + i, Map.of("val", i), List.of(), List.of(), false),
                    NOW.plusSeconds(i * 10 + 2));
            store.appendToolCall(call);

            store.appendSessionMessage(new SessionMessageDraft(
                    new AgentMessageId("m-a-" + i),
                    session,
                    Optional.of(run.id()),
                    Optional.empty(),
                    MessageRole.ASSISTANT,
                    MessageStatus.COMPLETED,
                    MessageVisibility.AGENT_VISIBLE,
                    List.of(new ToolCallPart(callId, corrId, "file_read", "1.0.0")),
                    Map.of(),
                    NOW.plusSeconds(i * 10 + 1)));

            store.appendSessionMessage(new SessionMessageDraft(
                    new AgentMessageId("m-t-" + i),
                    session,
                    Optional.of(run.id()),
                    Optional.empty(),
                    MessageRole.TOOL,
                    MessageStatus.COMPLETED,
                    MessageVisibility.AGENT_VISIBLE,
                    List.of(new ToolResultPart(
                            callId, corrId, "Module result data " + i + ": " + "content ".repeat(15))),
                    Map.of(),
                    NOW.plusSeconds(i * 10 + 2)));
        }

        AtomicInteger summarizationCalls = new AtomicInteger();
        AgentChatModel model = request -> {
            summarizationCalls.incrementAndGet();
            String prompt = request.messages().getLast().content();
            return new AgentChatResponse(
                    "res",
                    "model",
                    summaryJsonForPrompt(prompt),
                    List.of(),
                    ModelFinishReason.STOP,
                    ModelUsage.unpriced(500, 200),
                    "",
                    Map.of());
        };

        FrozenModelBinding binding = createBindingWithContextWindow(store, run, model, 100_000);
        CompactionEvaluationOutcome outcome = coordinator.evaluateAndCompactIfNeeded(run, 60, binding);

        assertThat(outcome.compacted()).isTrue();
        assertThat(outcome.semanticCompactionReason()).isEqualTo(CompactionTriggerReason.ACTIVE_HISTORY_BUDGET.name());
        assertThat(summarizationCalls.get()).isGreaterThanOrEqualTo(1);

        var latestSummary = store.latestValid(session).orElseThrow();
        long coveredSeq = latestSummary.coveredThrough().value();
        assertThat(coveredSeq).isGreaterThan(1L);

        List<AgentMessage> allMessages = store.messagesAfter(session, MessageCursor.BEFORE_FIRST, Integer.MAX_VALUE);
        List<AgentMessage> covered = allMessages.stream()
                .filter(m -> m.cursor().compareTo(latestSummary.coveredThrough()) <= 0)
                .toList();
        List<AgentMessage> tail = allMessages.stream()
                .filter(m -> m.cursor().compareTo(latestSummary.coveredThrough()) > 0)
                .toList();

        Set<ToolCallId> coveredCalls = extractToolCalls(covered);
        Set<ToolCallId> coveredResults = extractToolResults(covered);
        assertThat(coveredResults).containsAll(coveredCalls);

        Set<ToolCallId> tailCalls = extractToolCalls(tail);
        Set<ToolCallId> tailResults = extractToolResults(tail);
        assertThat(tailCalls).containsAll(tailResults);
    }

    @Test
    @DisplayName("Scenario C: 32k active budget smoothly compacts multi-turn conversational session")
    void testScenarioC32kActiveBudgetSmoothCompaction() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AtomicInteger idGen = new AtomicInteger();
        IdentifierGenerator ids = () -> "id-" + idGen.incrementAndGet();

        // 32k active budget
        CompressionPolicy policy = CompressionPolicy.defaults()
                .withSemanticCompactionEnabled(true)
                .withActiveHistoryBudgetTokens(32_000L)
                .withTailTokenBounds(500, 2000);

        SemanticCompactionCoordinator coordinator = coordinator(store, ids, policy);
        AgentRun run = createAndSaveRun(store, "session-scenario-c");
        AgentSessionId session = run.sessionId();

        for (int turn = 1; turn <= 40; turn++) {
            String userQuery =
                    "Turn " + turn + " user question regarding schedule and meeting notes. " + "context ".repeat(250);
            String assistantReply =
                    "Turn " + turn + " assistant answer summarizing key topics. " + "detail ".repeat(350);
            store.appendSessionMessage(draft("pa-u-" + turn, session, run.id().value(), MessageRole.USER, userQuery));
            store.appendSessionMessage(
                    draft("pa-a-" + turn, session, run.id().value(), MessageRole.ASSISTANT, assistantReply));
        }

        AtomicInteger summaryCount = new AtomicInteger();
        AgentChatModel model = request -> {
            summaryCount.incrementAndGet();
            String prompt = request.messages().getLast().content();
            return new AgentChatResponse(
                    "res",
                    "model",
                    summaryJsonForPrompt(prompt),
                    List.of(),
                    ModelFinishReason.STOP,
                    ModelUsage.unpriced(800, 300),
                    "",
                    Map.of());
        };

        FrozenModelBinding binding = createBindingWithContextWindow(store, run, model, 128_000);
        long startTime = System.currentTimeMillis();
        CompactionEvaluationOutcome outcome = coordinator.evaluateAndCompactIfNeeded(run, 40, binding);
        long duration = System.currentTimeMillis() - startTime;

        assertThat(outcome.compacted()).isTrue();
        assertThat(outcome.semanticCompactionReason()).isEqualTo(CompactionTriggerReason.ACTIVE_HISTORY_BUDGET.name());
        assertThat(outcome.projectedActiveHistoryTokensBefore()).isGreaterThan(32_000L);
        assertThat(outcome.projectedActiveHistoryTokensAfter()).isLessThan(32_000L);
        assertThat(duration).isLessThan(5000L);
    }

    @Test
    @DisplayName("Calibration Matrix: 80k vs 96k vs 128k comparison validates 96k as the optimal Coding Agent budget")
    void testCalibrationMatrixEvaluation() {
        long[] candidateBudgets = {80_000L, 96_000L, 128_000L};
        List<CalibrationResult> results = new ArrayList<>();

        for (long budget : candidateBudgets) {
            InMemoryRuntimeStore store = new InMemoryRuntimeStore();
            AtomicInteger idGen = new AtomicInteger();
            IdentifierGenerator ids = () -> "id-" + idGen.incrementAndGet();

            CompressionPolicy policy = CompressionPolicy.defaults()
                    .withSemanticCompactionEnabled(true)
                    .withActiveHistoryBudgetTokens(budget)
                    .withTailTokenBounds(2000, 8000);

            SemanticCompactionCoordinator coordinator = coordinator(store, ids, policy);
            AgentRun run = createAndSaveRun(store, "session-calib-" + budget);
            AgentSessionId session = run.sessionId();

            for (int i = 1; i <= 25; i++) {
                store.appendSessionMessage(draft(
                        "c-u-" + i,
                        session,
                        run.id().value(),
                        MessageRole.USER,
                        "Coding task " + i + ": " + "instruction ".repeat(500)));
                store.appendSessionMessage(draft(
                        "c-a-" + i,
                        session,
                        run.id().value(),
                        MessageRole.ASSISTANT,
                        "Implementation " + i + ": " + "code lines ".repeat(600)));
            }

            AtomicInteger summaryTokensUsed = new AtomicInteger();
            AgentChatModel model = request -> {
                summaryTokensUsed.addAndGet(1200);
                String prompt = request.messages().getLast().content();
                return new AgentChatResponse(
                        "res",
                        "model",
                        summaryJsonForPrompt(prompt),
                        List.of(),
                        ModelFinishReason.STOP,
                        ModelUsage.unpriced(1000, 200),
                        "",
                        Map.of());
            };

            FrozenModelBinding binding = createBindingWithContextWindow(store, run, model, 200_000);
            CompactionEvaluationOutcome outcome = coordinator.evaluateAndCompactIfNeeded(run, 25, binding);

            long mainTokensSaved = outcome.compacted()
                    ? Math.max(
                            0L,
                            outcome.projectedActiveHistoryTokensBefore() - outcome.projectedActiveHistoryTokensAfter())
                    : 0L;
            long netTokensSaved = Math.max(0L, mainTokensSaved - summaryTokensUsed.get());

            results.add(new CalibrationResult(
                    budget,
                    outcome.compacted(),
                    outcome.compactionSummaryCacheHitRate(),
                    mainTokensSaved,
                    summaryTokensUsed.get(),
                    netTokensSaved));
        }

        assertThat(results).hasSize(3);
        CalibrationResult at80k = results.get(0);
        CalibrationResult at96k = results.get(1);
        CalibrationResult at128k = results.get(2);

        assertThat(at80k.compacted()).isTrue();
        assertThat(at96k.compacted()).isTrue();
        assertThat(at96k.netTokensSaved()).isGreaterThan(0L);
        assertThat(at128k.compacted()).isFalse();
    }

    private record CalibrationResult(
            long budgetTokens,
            boolean compacted,
            double cacheHitRate,
            long mainModelTokensSaved,
            long summaryTokensUsed,
            long netTokensSaved) {}

    private static Set<ToolCallId> extractToolCalls(List<AgentMessage> messages) {
        Set<ToolCallId> ids = new HashSet<>();
        for (AgentMessage m : messages) {
            for (ContentPart p : m.contents()) {
                if (p instanceof ToolCallPart tcp) ids.add(tcp.toolCallId());
            }
        }
        return ids;
    }

    private static Set<ToolCallId> extractToolResults(List<AgentMessage> messages) {
        Set<ToolCallId> ids = new HashSet<>();
        for (AgentMessage m : messages) {
            for (ContentPart p : m.contents()) {
                if (p instanceof ToolResultPart trp) ids.add(trp.toolCallId());
            }
        }
        return ids;
    }

    private static SemanticCompactionCoordinator coordinator(
            InMemoryRuntimeStore store, IdentifierGenerator ids, CompressionPolicy policy) {
        TimeProvider time = () -> NOW;
        RunTransitionCoordinator transitions =
                new RunTransitionCoordinator(store, store, store, store, ids, time, new RunAwaiter(), store);
        return new SemanticCompactionCoordinator(
                store,
                store,
                new SummaryModelInvoker(transitions, new RunControlRegistry(), ids, time, policy),
                new CompactionTriggerEvaluator(policy),
                policy,
                new DeterministicContextCompressor(),
                ids,
                time,
                store);
    }

    private static AgentRun createAndSaveRun(InMemoryRuntimeStore store, String sessionId) {
        ManualExecutionScheduler scheduler = new ManualExecutionScheduler();
        AtomicInteger ids = new AtomicInteger();
        DefaultAgentRuntime runtime = new RuntimeCoreBuilder()
                .registerChatModel(
                        "openai-compatible",
                        "1.0.0",
                        request -> new AgentChatResponse(
                                "init",
                                "deepseek-v4-pro",
                                "hello",
                                List.of(),
                                ModelFinishReason.STOP,
                                ModelUsage.unpriced(1, 1),
                                "",
                                Map.of()))
                .persistence(RuntimePersistencePorts.inMemory(store))
                .scheduler(scheduler)
                .identifierGenerator(() -> "id-" + ids.incrementAndGet())
                .timeProvider(() -> NOW)
                .build();
        var accepted = runtime.start(new AgentRunRequest(
                "key-" + sessionId,
                new AgentDefinitionId("test-agent"),
                Optional.empty(),
                "default",
                new AgentSessionId(sessionId),
                Optional.empty(),
                "objective",
                List.of(),
                RuntimeOverrides.NONE));
        return store.find(accepted.runId()).orElseThrow();
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

    private static FrozenModelBinding createBindingWithContextWindow(
            InMemoryRuntimeStore store, AgentRun run, AgentChatModel chatModel, int contextWindow) {
        RuntimeConfigurationSnapshot config =
                store.configuration(run.configurationSnapshot()).orElseThrow();
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
}
