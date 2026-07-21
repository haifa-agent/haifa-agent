package io.haifa.agent.runtime.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.common.id.IdentifierGenerator;
import io.haifa.agent.context.compression.CompressionPolicy;
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
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
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
import io.haifa.agent.runtime.core.storage.SessionMessageDraft;
import io.haifa.agent.runtime.core.tool.ToolDefinition;
import io.haifa.agent.runtime.core.trace.RuntimeTraceEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        var first = source.select(run, 0);
        var summary = first.summary().orElseThrow();

        assertThat(first.items())
                .filteredOn(item -> item.content() instanceof MessageGroupContextContent)
                .anySatisfy(item -> assertThat(((MessageGroupContextContent) item.content()).messages())
                        .extracting(AgentMessage::id)
                        .containsExactly(new AgentMessageId("assistant-tool"), new AgentMessageId("tool-result")));
        assertThat(source.select(run, 0).summary().orElseThrow().version()).isEqualTo(summary.version());
        assertThatThrownBy(() -> store.compareAndSet(summary, 0)).isInstanceOf(OptimisticLockException.class);

        store.redactMessage(summary.sourceMessageIds().getFirst());
        assertThat(store.latestValid(run.sessionId())).isEmpty();
        store.appendSessionMessage(
                draft("post-redaction", run.sessionId(), run.id().value(), MessageRole.USER, "after redaction"));
        assertThat(source.select(run, 0).summary().orElseThrow().version().value())
                .isEqualTo(2);
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
        var runtime = new RuntimeCoreBuilder()
                .registerChatModel("openai-compatible", "1.0.0", model)
                .registerTool(new ToolDefinition("echo", "1.0", "echo.input", false))
                .registerModelTool(toolSpec())
                .toolExecutor((run, definition, request) -> {
                    toolExecutions.incrementAndGet();
                    return new ToolResult(true, "echoed", Map.of("text", "hello"), List.of(), List.of(), false);
                })
                .store(store)
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
        var runtime = new RuntimeCoreBuilder()
                .registerChatModel("openai-compatible", "1.0.0", model)
                .registerTool(new ToolDefinition("echo", "1.0", "echo.input", false))
                .registerModelTool(toolSpec())
                .toolExecutor((run, definition, request) -> {
                    executions.incrementAndGet();
                    return new ToolResult(
                            true, "x".repeat(20_000), Map.of("full", "y".repeat(20_000)), List.of(), List.of(), false);
                })
                .store(store)
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
                .store(store)
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
