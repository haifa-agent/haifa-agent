package io.haifa.agent.runtime.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.context.compression.CompressionPolicy;
import io.haifa.agent.context.compression.CompressionRequest;
import io.haifa.agent.context.compression.CompressionResult;
import io.haifa.agent.context.compression.ContextCompressor;
import io.haifa.agent.context.compression.DeterministicContextCompressor;
import io.haifa.agent.context.item.MessageGroupContextContent;
import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.content.ToolCallPart;
import io.haifa.agent.core.content.ToolResultPart;
import io.haifa.agent.core.message.AgentMessage;
import io.haifa.agent.core.message.AgentMessageId;
import io.haifa.agent.core.message.MessageCursor;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.message.MessageStatus;
import io.haifa.agent.core.message.MessageVisibility;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.run.AgentRunStatus;
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
import io.haifa.agent.model.api.ModelErrorCategory;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelInvocationException;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ModelToolSpecification;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.runtime.api.AgentRunRequest;
import io.haifa.agent.runtime.api.RuntimeOverrides;
import io.haifa.agent.runtime.core.checkpoint.RuntimeCheckpointState;
import io.haifa.agent.runtime.core.execution.ManualExecutionScheduler;
import io.haifa.agent.runtime.core.loop.SessionMessageSource;
import io.haifa.agent.runtime.core.storage.InMemoryRuntimeStore;
import io.haifa.agent.runtime.core.storage.OptimisticLockException;
import io.haifa.agent.runtime.core.storage.RuntimePersistencePorts;
import io.haifa.agent.runtime.core.storage.SessionMessageDraft;
import io.haifa.agent.runtime.core.trace.RuntimeTraceEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SessionCompressionCheckpointTest {
    private static final Instant NOW = Instant.parse("2026-07-21T00:00:00Z");

    @Test
    void sessionSequencePaginationAndRecentWindowAreStableAcrossRunsAndConcurrentAppends() throws Exception {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AgentSessionId session = new AgentSessionId("shared-session");
        store.appendSessionMessage(draft("m-1", session, "run-1", MessageRole.USER, "one"));
        store.appendSessionMessage(draft("m-2", session, "run-2", MessageRole.ASSISTANT, "two"));

        try (var executor = Executors.newFixedThreadPool(8)) {
            for (int index = 0; index < 50; index++) {
                int value = index;
                executor.submit(() -> store.appendSessionMessage(
                        draft("m-concurrent-" + value, session, "run-" + (value % 3), MessageRole.USER, "v" + value)));
            }
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        List<AgentMessage> all = store.messagesAfter(session, MessageCursor.BEFORE_FIRST, 100);
        assertThat(all).hasSize(52);
        assertThat(all)
                .extracting(AgentMessage::sequence)
                .containsExactlyElementsOf(
                        java.util.stream.LongStream.rangeClosed(1, 52).boxed().toList());
        assertThat(store.messagesAfter(session, new MessageCursor(2), 3))
                .extracting(AgentMessage::sequence)
                .containsExactly(3L, 4L, 5L);
        assertThat(store.recentMessages(session, new MessageCursor(50), 3).messages())
                .extracting(AgentMessage::sequence)
                .containsExactly(48L, 49L, 50L);
        assertThat(MessageCursor.parse(new MessageCursor(50).serialize())).isEqualTo(new MessageCursor(50));
    }

    @Test
    void compressionUsesCasInvalidatesOnRedactionAndKeepsToolTurnAtomic() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        ManualExecutionScheduler scheduler = new ManualExecutionScheduler();
        AtomicInteger ids = new AtomicInteger();
        var runtime = runtime(store, scheduler, request -> finalResponse("unused"), ids);
        var accepted = runtime.start(request("compression-run", "compression-session"));
        var run = store.find(accepted.runId()).orElseThrow();
        var callId = new ToolCallId("tool-call-atomic");
        var correlation = new ProviderToolCallCorrelationId("provider-call-atomic");
        store.appendSessionMessage(new SessionMessageDraft(
                new AgentMessageId("assistant-tool"),
                run.sessionId(),
                Optional.of(run.id()),
                Optional.empty(),
                MessageRole.ASSISTANT,
                MessageStatus.COMPLETED,
                MessageVisibility.AGENT_VISIBLE,
                List.of(new ToolCallPart(callId, correlation, "echo", "1.0")),
                Map.of(),
                NOW));
        store.appendSessionMessage(new SessionMessageDraft(
                new AgentMessageId("tool-result"),
                run.sessionId(),
                Optional.of(run.id()),
                Optional.empty(),
                MessageRole.TOOL,
                MessageStatus.COMPLETED,
                MessageVisibility.AGENT_VISIBLE,
                List.of(new ToolResultPart(callId, correlation, "bounded result")),
                Map.of(),
                NOW));
        store.appendSessionMessage(
                draft("latest-user", run.sessionId(), run.id().value(), MessageRole.USER, "latest"));

        SessionMessageSource source = new SessionMessageSource(
                store,
                store,
                new DeterministicContextCompressor(),
                new CompressionPolicy(2, 10, 1),
                () -> "summary-id-" + ids.incrementAndGet(),
                () -> NOW);
        var first = source.compact(run.sessionId());
        var summary = first.summary().orElseThrow();

        assertThat(summary.sourceMessageIds())
                .containsSubsequence(new AgentMessageId("assistant-tool"), new AgentMessageId("tool-result"));
        assertThat(source.select(run, 0).summary().orElseThrow().version()).isEqualTo(summary.version());
        assertThatThrownBy(() -> store.compareAndSet(summary, 0)).isInstanceOf(OptimisticLockException.class);

        store.redactMessage(summary.sourceMessageIds().getFirst());
        assertThat(store.latestValid(run.sessionId())).isEmpty();
        store.appendSessionMessage(
                draft("post-redaction", run.sessionId(), run.id().value(), MessageRole.USER, "after redaction"));
        assertThat(source.compact(run.sessionId())
                        .summary()
                        .orElseThrow()
                        .version()
                        .value())
                .isEqualTo(2);
    }

    @Test
    void automaticSummaryRemainsImmutableWhileNewSessionMessagesAppend() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        ManualExecutionScheduler scheduler = new ManualExecutionScheduler();
        AtomicInteger ids = new AtomicInteger();
        var runtime = runtime(store, scheduler, request -> finalResponse("unused"), ids);
        var accepted = runtime.start(request("stable-summary-run", "stable-summary-session"));
        var run = store.find(accepted.runId()).orElseThrow();
        store.appendSessionMessage(draft("stable-two", run.sessionId(), run.id().value(), MessageRole.USER, "two"));
        store.appendSessionMessage(
                draft("stable-three", run.sessionId(), run.id().value(), MessageRole.USER, "three"));

        SessionMessageSource source = new SessionMessageSource(
                store,
                store,
                new DeterministicContextCompressor(),
                new CompressionPolicy(2, 10, 1),
                () -> "stable-summary-" + ids.incrementAndGet(),
                () -> NOW);
        var first = source.compact(run.sessionId());
        var checkpoint = first.summary().orElseThrow();

        store.appendSessionMessage(
                draft("stable-four", run.sessionId(), run.id().value(), MessageRole.USER, "four"));
        var second = source.select(run, 0);

        assertThat(second.summary()).contains(checkpoint);
        assertThat(second.items().stream().map(item -> item.id().value()).toList())
                .startsWith(
                        first.items().stream().map(item -> item.id().value()).toArray(String[]::new));
        assertThat(second.items())
                .filteredOn(item -> item.content() instanceof MessageGroupContextContent)
                .flatExtracting(item -> ((MessageGroupContextContent) item.content()).messages())
                .extracting(AgentMessage::id)
                .containsExactly(new AgentMessageId("stable-three"), new AgentMessageId("stable-four"));
        assertThat(store.latestVersion(run.sessionId())).isEqualTo(1);
    }

    @Test
    void automaticCompactionUsesTokenBudgetInsteadOfMessageCountAndSwitchesOnlyOnce() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        ManualExecutionScheduler scheduler = new ManualExecutionScheduler();
        AtomicInteger ids = new AtomicInteger();
        var runtime = runtime(store, scheduler, request -> finalResponse("unused"), ids);
        var accepted = runtime.start(request("token-window-run", "token-window-session"));
        var run = store.find(accepted.runId()).orElseThrow();
        for (int index = 2; index <= 15; index++) {
            store.appendSessionMessage(draft(
                    "token-message-" + index, run.sessionId(), run.id().value(), MessageRole.USER, "short-" + index));
        }

        SessionMessageSource source = new SessionMessageSource(
                store,
                store,
                new DeterministicContextCompressor(),
                new CompressionPolicy(12, 32, 4),
                () -> "token-summary-" + ids.incrementAndGet(),
                () -> NOW);

        var belowThreshold = source.select(run, 0, 10_000);
        assertThat(belowThreshold.summary()).isEmpty();
        assertThat(belowThreshold.compacted()).isFalse();

        var threshold = source.select(run, 0, 80);
        var checkpoint = threshold.summary().orElseThrow();
        assertThat(threshold.compacted()).isTrue();
        assertThat(threshold.compactionReason()).isEqualTo(SessionMessageSource.CompactionReason.TOKEN_THRESHOLD);

        store.appendSessionMessage(
                draft("token-message-16", run.sessionId(), run.id().value(), MessageRole.USER, "short-16"));
        var reused = source.select(run, 0, 10_000);
        assertThat(reused.summary()).contains(checkpoint);
        assertThat(reused.compacted()).isFalse();
        assertThat(reused.compactionReason()).isEqualTo(SessionMessageSource.CompactionReason.NONE);
        assertThat(store.latestVersion(run.sessionId())).isEqualTo(1);
    }

    @Test
    void concurrentCompactionAdoptsTheSingleCasWinner() throws Exception {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AgentSessionId session = new AgentSessionId("concurrent-compaction-session");
        store.appendSessionMessage(draft("concurrent-one", session, "run-1", MessageRole.USER, "one"));
        store.appendSessionMessage(draft("concurrent-two", session, "run-1", MessageRole.USER, "two"));
        store.appendSessionMessage(draft("concurrent-three", session, "run-1", MessageRole.USER, "three"));
        DeterministicContextCompressor delegate = new DeterministicContextCompressor();
        CountDownLatch compressorsReady = new CountDownLatch(2);
        ContextCompressor synchronizedCompressor = new ContextCompressor() {
            @Override
            public CompressionResult compress(CompressionRequest request) {
                compressorsReady.countDown();
                try {
                    assertThat(compressorsReady.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("concurrent compressor interrupted", interrupted);
                }
                return delegate.compress(request);
            }

            @Override
            public String version() {
                return delegate.version();
            }
        };
        AtomicInteger ids = new AtomicInteger();
        SessionMessageSource source = new SessionMessageSource(
                store,
                store,
                synchronizedCompressor,
                new CompressionPolicy(3, 10, 1),
                () -> "concurrent-summary-" + ids.incrementAndGet(),
                () -> NOW);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> source.compact(session));
            var second = executor.submit(() -> source.compact(session));
            assertThat(first.get(10, TimeUnit.SECONDS).summary())
                    .isEqualTo(second.get(10, TimeUnit.SECONDS).summary());
        }
        assertThat(store.latestVersion(session)).isEqualTo(1);
    }

    @Test
    void nextRunOmitsIncompleteToolProtocolTurnFromSessionContext() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AgentSessionId session = new AgentSessionId("incomplete-tool-session");
        store.appendSessionMessage(draft("m-user-before", session, "run-1", MessageRole.USER, "before"));
        store.appendSessionMessage(new SessionMessageDraft(
                new AgentMessageId("m-orphan-tool-call"),
                session,
                Optional.of(new AgentRunId("run-1")),
                Optional.empty(),
                MessageRole.ASSISTANT,
                MessageStatus.COMPLETED,
                MessageVisibility.AGENT_VISIBLE,
                List.of(new ToolCallPart(
                        new ToolCallId("orphan-tool-call"),
                        new ProviderToolCallCorrelationId("orphan-provider-call"),
                        "execution_run",
                        "2.0.0")),
                Map.of(),
                NOW));
        store.appendSessionMessage(draft("m-user-after", session, "run-2", MessageRole.USER, "after"));

        SessionMessageSource source = new SessionMessageSource(
                store,
                store,
                new DeterministicContextCompressor(),
                new CompressionPolicy(10, 10, 10),
                () -> "unused-summary",
                () -> NOW);

        var selection = source.compact(session);
        assertThat(selection.summary().orElseThrow().sourceMessageIds())
                .containsExactly(new AgentMessageId("m-user-before"));
        assertThat(selection.items())
                .filteredOn(item -> item.content() instanceof MessageGroupContextContent)
                .flatExtracting(item -> ((MessageGroupContextContent) item.content()).messages())
                .extracting(AgentMessage::id)
                .containsExactly(new AgentMessageId("m-user-after"));
    }

    @Test
    void sessionEstimateIncludesAuthoritativeToolArgumentsAndStructuredResults() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AgentSessionId session = new AgentSessionId("estimated-tool-session");
        AgentRunId runId = new AgentRunId("estimated-tool-run");
        ToolCallId callId = new ToolCallId("estimated-tool-call");
        ProviderToolCallCorrelationId correlation = new ProviderToolCallCorrelationId("estimated-provider-call");
        ToolCall call = new ToolCall(
                callId,
                runId,
                new AgentStepId("estimated-step"),
                correlation,
                new RuntimeIdempotencyKey("estimated-idempotency"),
                "file.read",
                "1.0",
                new ToolArguments("file.read.input", "1.0", Map.of("content", "a".repeat(1_200))),
                NOW);
        call.beginValidation();
        call.beginPolicyCheck();
        call.start(NOW);
        call.complete(
                new ToolResult(true, "read result", Map.of("content", "b".repeat(1_200)), List.of(), List.of(), false),
                NOW);
        store.appendToolCall(call);
        store.appendSessionMessage(new SessionMessageDraft(
                new AgentMessageId("estimated-call-message"),
                session,
                Optional.of(runId),
                Optional.empty(),
                MessageRole.ASSISTANT,
                MessageStatus.COMPLETED,
                MessageVisibility.AGENT_VISIBLE,
                List.of(new ToolCallPart(callId, correlation, "file.read", "1.0")),
                Map.of(),
                NOW));
        store.appendSessionMessage(new SessionMessageDraft(
                new AgentMessageId("estimated-result-message"),
                session,
                Optional.of(runId),
                Optional.empty(),
                MessageRole.TOOL,
                MessageStatus.COMPLETED,
                MessageVisibility.AGENT_VISIBLE,
                List.of(new ToolResultPart(callId, correlation, "read result")),
                Map.of(),
                NOW));

        SessionMessageSource source = new SessionMessageSource(
                store,
                store,
                new DeterministicContextCompressor(),
                new CompressionPolicy(10, 10, 10),
                () -> "unused-summary",
                () -> NOW);

        assertThat(source.compact(session).items()).singleElement().satisfies(item -> assertThat(item.estimatedTokens())
                .isGreaterThan(800));
    }

    @Test
    void manualCompactionKeepsOriginalMessagesAndSummarizesWholeToolTurn() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AgentSessionId session = new AgentSessionId("manual-compaction-session");
        store.appendSessionMessage(draft("m-user", session, "run-1", MessageRole.USER, "first"));
        var callId = new ToolCallId("manual-tool-call");
        var correlation = new ProviderToolCallCorrelationId("manual-provider-call");
        store.appendSessionMessage(new SessionMessageDraft(
                new AgentMessageId("m-tool-call"),
                session,
                Optional.of(new AgentRunId("run-1")),
                Optional.empty(),
                MessageRole.ASSISTANT,
                MessageStatus.COMPLETED,
                MessageVisibility.AGENT_VISIBLE,
                List.of(new ToolCallPart(callId, correlation, "echo", "1.0")),
                Map.of(),
                NOW));
        store.appendSessionMessage(new SessionMessageDraft(
                new AgentMessageId("m-tool-result"),
                session,
                Optional.of(new AgentRunId("run-1")),
                Optional.empty(),
                MessageRole.TOOL,
                MessageStatus.COMPLETED,
                MessageVisibility.AGENT_VISIBLE,
                List.of(new ToolResultPart(callId, correlation, "bounded result")),
                Map.of(),
                NOW));
        store.appendSessionMessage(draft("m-latest", session, "run-2", MessageRole.USER, "latest"));

        SessionMessageSource source = new SessionMessageSource(
                store,
                store,
                new DeterministicContextCompressor(),
                new CompressionPolicy(3, 10, 1),
                () -> "manual-summary",
                () -> NOW);
        var selection = source.compact(session);

        assertThat(selection.summary().orElseThrow().sourceMessageIds())
                .containsExactly(
                        new AgentMessageId("m-user"),
                        new AgentMessageId("m-tool-call"),
                        new AgentMessageId("m-tool-result"));
        assertThat(store.messagesAfter(session, MessageCursor.BEFORE_FIRST, 10))
                .extracting(AgentMessage::id)
                .containsExactly(
                        new AgentMessageId("m-user"),
                        new AgentMessageId("m-tool-call"),
                        new AgentMessageId("m-tool-result"),
                        new AgentMessageId("m-latest"));
    }

    @Test
    void finalAnswerIsCommittedAsSessionFactWithRunOutput() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        ManualExecutionScheduler scheduler = new ManualExecutionScheduler();
        var runtime = runtime(store, scheduler, request -> finalResponse("final answer"), new AtomicInteger());

        var accepted = runtime.start(request("final-run", "final-session"));
        scheduler.runAll();

        assertThat(runtime.find(accepted.runId()).orElseThrow().status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(store.output(accepted.runId())).contains("final answer");
        assertThat(store.messagesAfter(new AgentSessionId("final-session"), MessageCursor.BEFORE_FIRST, 10))
                .extracting(message -> message.role() + ":" + message.contents().getFirst())
                .containsExactly(
                        "USER:" + new TextPart("objective", "plain"),
                        "ASSISTANT:" + new TextPart("final answer", "plain"));
    }

    @Test
    void contextTooLongRebuildsOncePersistsAttemptAndDoesNotRepeatToolExecution() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        ManualExecutionScheduler scheduler = new ManualExecutionScheduler();
        AtomicInteger ids = new AtomicInteger();
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger toolExecutions = new AtomicInteger();
        List<RuntimeTraceEvent> traces = new ArrayList<>();
        AgentChatModel model = request -> {
            int call = modelCalls.incrementAndGet();
            if (call == 1) {
                return new AgentChatResponse(
                        "tool-response",
                        "deepseek-v4-pro",
                        "",
                        List.of(new ModelToolCall(
                                new ProviderToolCallCorrelationId("provider-call-1"), "echo", Map.of("text", "hello"))),
                        ModelFinishReason.TOOL_CALLS,
                        ModelUsage.unpriced(4, 1),
                        "",
                        Map.of());
            }
            throw new ModelInvocationException(
                    ModelErrorCategory.CONTEXT_TOO_LONG,
                    false,
                    400,
                    "context_length_exceeded",
                    request.callId(),
                    "model context is too long",
                    null);
        };
        RuntimeCoreBuilder builder = new RuntimeCoreBuilder().registerChatModel("openai-compatible", "1.0.0", model);
        var runtime = TestToolPlatform.install(builder, "echo", "1.0.0", "echo.input", false, request -> {
                    toolExecutions.incrementAndGet();
                    return new ToolResult(true, "echoed", Map.of("text", "hello"), List.of(), List.of(), false);
                })
                .persistence(RuntimePersistencePorts.inMemory(store))
                .scheduler(scheduler)
                .identifierGenerator(() -> "ctx-id-" + ids.incrementAndGet())
                .timeProvider(() -> NOW)
                .trace(traces::add)
                .build();

        var accepted = runtime.start(request("context-run", "context-session"));
        scheduler.runAll();

        assertThat(runtime.find(accepted.runId()).orElseThrow().status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(modelCalls).hasValue(3);
        assertThat(toolExecutions).hasValue(1);
        assertThat(store.steps(accepted.runId()))
                .filteredOn(step -> step.type() == io.haifa.agent.core.step.AgentStepType.MODEL_CALL)
                .hasSize(3);
        RuntimeCheckpointState checkpoint = store.state(
                        store.latest(accepted.runId()).orElseThrow().id().value())
                .orElseThrow();
        assertThat(checkpoint.forcedContextRebuildAttempts()).isEqualTo(1);
        assertThat(checkpoint.modelConfigurationDigest()).startsWith("sha256:");
        assertThat(checkpoint.toolCalls()).singleElement().satisfies(tool -> {
            assertThat(tool.toolCallId().value())
                    .isNotEqualTo(tool.idempotencyKey().value());
            assertThat(tool.providerCorrelationId().value())
                    .isNotEqualTo(tool.idempotencyKey().value());
        });
        assertThat(traces).anyMatch(trace -> trace.operation().equals("context.forced-rebuild"));
        assertThat(traces)
                .filteredOn(trace -> trace.operation().equals("context.built"))
                .anySatisfy(trace -> assertThat(trace.safeAttributes())
                        .containsKeys(
                                "windowGeneration",
                                "compactionGeneration",
                                "compactionCount",
                                "compacted",
                                "compactionReason",
                                "compactionElapsedMillis",
                                "estimatedSessionTokens",
                                "sessionTokenBudget",
                                "summarySourceHash"));
    }

    @Test
    void largeToolResultIsExternalizedAndPersistenceRetryDoesNotReexecuteTool() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        store.failNextToolResultAssetWrite();
        ManualExecutionScheduler scheduler = new ManualExecutionScheduler();
        AtomicInteger ids = new AtomicInteger();
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger executions = new AtomicInteger();
        AgentChatModel model = request -> modelCalls.incrementAndGet() == 1
                ? new AgentChatResponse(
                        "tool-response",
                        "deepseek-v4-pro",
                        "",
                        List.of(new ModelToolCall(
                                new ProviderToolCallCorrelationId("provider-large"), "echo", Map.of("text", "large"))),
                        ModelFinishReason.TOOL_CALLS,
                        ModelUsage.unpriced(1, 1),
                        "",
                        Map.of())
                : finalResponse("done");
        RuntimeCoreBuilder builder = new RuntimeCoreBuilder().registerChatModel("openai-compatible", "1.0.0", model);
        var runtime = TestToolPlatform.install(builder, "echo", "1.0.0", "echo.input", false, request -> {
                    executions.incrementAndGet();
                    return new ToolResult(
                            true, "x".repeat(20_000), Map.of("full", "y".repeat(20_000)), List.of(), List.of(), false);
                })
                .persistence(RuntimePersistencePorts.inMemory(store))
                .scheduler(scheduler)
                .identifierGenerator(() -> "asset-id-" + ids.incrementAndGet())
                .timeProvider(() -> NOW)
                .build();

        var accepted = runtime.start(request("asset-run", "asset-session"));
        scheduler.runAll();

        assertThat(runtime.find(accepted.runId()).orElseThrow().status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(executions).hasValue(1);
        var result = store.toolCalls(accepted.runId()).getFirst().result().orElseThrow();
        assertThat(result.truncated()).isTrue();
        assertThat(result.summary()).hasSize(4_000);
        assertThat(result.assets()).singleElement().satisfies(asset -> assertThat(
                        store.load(asset).orElseThrow().summary())
                .hasSize(20_000));
        RuntimeCheckpointState checkpoint = store.state(
                        store.latest(accepted.runId()).orElseThrow().id().value())
                .orElseThrow();
        assertThat(checkpoint.derivedContentReferences()).containsAll(result.assets());
    }

    private static DefaultAgentRuntime runtime(
            InMemoryRuntimeStore store, ManualExecutionScheduler scheduler, AgentChatModel model, AtomicInteger ids) {
        IdentifierGenerator generator = () -> "test-id-" + ids.incrementAndGet();
        return new RuntimeCoreBuilder()
                .registerChatModel("openai-compatible", "1.0.0", model)
                .persistence(RuntimePersistencePorts.inMemory(store))
                .scheduler(scheduler)
                .identifierGenerator(generator)
                .timeProvider(() -> NOW)
                .build();
    }

    private static AgentChatResponse finalResponse(String text) {
        return new AgentChatResponse(
                "final-response",
                "deepseek-v4-pro",
                text,
                List.of(),
                ModelFinishReason.STOP,
                ModelUsage.unpriced(2, 2),
                "",
                Map.of());
    }

    private static ModelToolSpecification toolSpec() {
        return new ModelToolSpecification(
                "echo",
                "1.0",
                "Echo text",
                "echo.input",
                "1.0",
                Map.of(
                        "type", "object",
                        "properties", Map.of("text", Map.of("type", "string")),
                        "required", List.of("text")),
                false);
    }

    private static AgentRunRequest request(String key, String session) {
        return new AgentRunRequest(
                key,
                new AgentDefinitionId("agent"),
                Optional.empty(),
                "default",
                new AgentSessionId(session),
                Optional.empty(),
                "objective",
                List.of(),
                RuntimeOverrides.NONE);
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
