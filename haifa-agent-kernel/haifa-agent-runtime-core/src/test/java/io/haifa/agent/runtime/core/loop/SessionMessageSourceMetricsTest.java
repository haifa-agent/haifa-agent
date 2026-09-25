package io.haifa.agent.runtime.core.loop;

import static org.assertj.core.api.Assertions.assertThat;

import io.haifa.agent.context.compression.CompressionPolicy;
import io.haifa.agent.context.compression.DeterministicContextCompressor;
import io.haifa.agent.core.agent.AgentDefinitionId;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.content.ToolCallPart;
import io.haifa.agent.core.content.ToolResultPart;
import io.haifa.agent.core.message.AgentMessageId;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.message.MessageStatus;
import io.haifa.agent.core.message.MessageVisibility;
import io.haifa.agent.core.run.AgentRun;
import io.haifa.agent.core.step.AgentStepId;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.RuntimeIdempotencyKey;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.model.api.AgentChatResponse;
import io.haifa.agent.model.api.ModelFinishReason;
import io.haifa.agent.model.api.ModelUsage;
import io.haifa.agent.runtime.api.AgentRunRequest;
import io.haifa.agent.runtime.api.RuntimeOverrides;
import io.haifa.agent.runtime.core.DefaultAgentRuntime;
import io.haifa.agent.runtime.core.RuntimeCoreBuilder;
import io.haifa.agent.runtime.core.context.ActiveContextSnapshots;
import io.haifa.agent.runtime.core.execution.ManualExecutionScheduler;
import io.haifa.agent.runtime.core.storage.InMemoryRuntimeStore;
import io.haifa.agent.runtime.core.storage.RuntimePersistencePorts;
import io.haifa.agent.runtime.core.storage.SessionMessageDraft;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SessionMessageSourceMetricsTest {
    private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");

    @Test
    void longHistoryStopsScanningAfterTheToolResultAndReportsBoundedDiagnostics() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AgentRun run = createRun(store);
        ToolCallId callId = new ToolCallId("tool-call-1");
        ProviderToolCallCorrelationId correlationId = new ProviderToolCallCorrelationId("provider-call-1");
        store.appendToolCall(new ToolCall(
                callId,
                run.id(),
                new AgentStepId("step-1"),
                correlationId,
                new RuntimeIdempotencyKey("idempotency-1"),
                "read_file",
                "1.0.0",
                new ToolArguments("read.input", "1.0.0", Map.of("path", "README.md")),
                NOW));
        store.appendSessionMessage(new SessionMessageDraft(
                new AgentMessageId("assistant-tool-call"),
                run.sessionId(),
                Optional.of(run.id()),
                Optional.empty(),
                MessageRole.ASSISTANT,
                MessageStatus.COMPLETED,
                MessageVisibility.AGENT_VISIBLE,
                List.of(new ToolCallPart(callId, correlationId, "read_file", "1.0.0")),
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
                List.of(new ToolResultPart(callId, correlationId, "read complete")),
                Map.of(),
                NOW));
        for (int index = 0; index < 1_000; index++) {
            store.appendSessionMessage(new SessionMessageDraft(
                    new AgentMessageId("history-" + index),
                    run.sessionId(),
                    Optional.of(run.id()),
                    Optional.empty(),
                    MessageRole.USER,
                    MessageStatus.COMPLETED,
                    MessageVisibility.USER_VISIBLE,
                    List.of(new TextPart("history " + index, "plain")),
                    Map.of(),
                    NOW));
        }
        SessionMessageSource source = new SessionMessageSource(
                store,
                store,
                new DeterministicContextCompressor(),
                new CompressionPolicy(12, 32, 4),
                () -> "summary-id",
                () -> NOW,
                new ActiveContextSnapshots(
                        store, store, new CompressionPolicy(12, 32, 4), new DeterministicContextCompressor()));

        SessionMessageSource.MeasuredSelection measured = source.selectMeasured(run, Long.MAX_VALUE);

        assertThat(measured.metrics().historyRowsRead()).isEqualTo(1_003);
        assertThat(measured.metrics().activeRowsSelected()).isEqualTo(1_003);
        assertThat(measured.metrics().atomicGroupCandidateScans()).isEqualTo(1);
        assertThat(measured.metrics().atomicGroupsBuilt()).isEqualTo(1_002);
        assertThat(measured.metrics().toolCallBatchCount()).isEqualTo(1);
        assertThat(measured.selection().items()).hasSize(1_002);
        SessionMessageSource oracle = SessionMessageSource.fullHistoryOracle(
                store,
                store,
                new DeterministicContextCompressor(),
                new CompressionPolicy(12, 32, 4),
                () -> "oracle-summary",
                () -> NOW);
        assertThat(measured.selection()).isEqualTo(oracle.select(run, Long.MAX_VALUE));
    }

    @Test
    void boundedSnapshotMatchesTheDisabledFullHistoryOracleBeforeAndAfterExternalAppend() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AgentRun run = createRun(store);
        CompressionPolicy policy = new CompressionPolicy(12, 32, 4);
        DeterministicContextCompressor compressor = new DeterministicContextCompressor();
        SessionMessageSource bounded = new SessionMessageSource(
                store,
                store,
                compressor,
                policy,
                () -> "summary-bounded",
                () -> NOW,
                new ActiveContextSnapshots(store, store, policy, compressor));
        SessionMessageSource oracle = SessionMessageSource.fullHistoryOracle(
                store, store, compressor, policy, () -> "summary-oracle", () -> NOW);
        appendText(store, run, "oracle-assistant-1", MessageRole.ASSISTANT, "first response");

        assertThat(bounded.select(run, Long.MAX_VALUE)).isEqualTo(oracle.select(run, Long.MAX_VALUE));

        appendText(store, run, "oracle-user-2", MessageRole.USER, "external append");
        appendText(store, run, "oracle-assistant-2", MessageRole.ASSISTANT, "second response");
        assertThat(bounded.select(run, Long.MAX_VALUE)).isEqualTo(oracle.select(run, Long.MAX_VALUE));
    }

    private static void appendText(InMemoryRuntimeStore store, AgentRun run, String id, MessageRole role, String text) {
        store.appendSessionMessage(new SessionMessageDraft(
                new AgentMessageId(id),
                run.sessionId(),
                Optional.of(run.id()),
                Optional.empty(),
                role,
                MessageStatus.COMPLETED,
                role == MessageRole.USER ? MessageVisibility.USER_VISIBLE : MessageVisibility.AGENT_VISIBLE,
                List.of(new TextPart(text, "plain")),
                Map.of(),
                NOW));
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
                .identifierGenerator(() -> "metrics-test-" + ids.incrementAndGet())
                .timeProvider(() -> NOW)
                .build();
        var accepted = runtime.start(new AgentRunRequest(
                "context-metrics",
                new AgentDefinitionId("metrics-test-agent"),
                Optional.empty(),
                "default",
                new io.haifa.agent.core.session.AgentSessionId("metrics-session"),
                Optional.empty(),
                "first user turn",
                List.of(),
                RuntimeOverrides.NONE));
        return store.find(accepted.runId()).orElseThrow();
    }
}
