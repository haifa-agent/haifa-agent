package io.haifa.agent.runtime.core.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.core.content.*;
import io.haifa.agent.core.message.*;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.step.AgentStepId;
import io.haifa.agent.core.tool.*;
import io.haifa.agent.runtime.core.storage.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RunMessageProjectorTest {
    private static final AgentRunId RUN = new AgentRunId("run");
    private static final Instant NOW = Instant.parse("2026-10-03T00:00:00Z");

    @Test
    void fillsFilteredPagesWithStableIndicesExactMultipleToolsAndCompleteSafeText() {
        var store = new InMemoryRuntimeStore();
        ToolCall first =
                call("first", "read", Map.of("query", "q".repeat(20_000), "api_key", "synthetic-private"), false);
        ToolCall second = call("second", "write", Map.of("path", "a.txt"), true);
        store.appendToolCall(second);
        store.appendToolCall(first);
        append(
                store,
                "user",
                MessageRole.USER,
                MessageVisibility.USER_VISIBLE,
                List.of(new TextPart("input", "plain")));
        append(
                store,
                "hidden",
                MessageRole.ASSISTANT,
                MessageVisibility.INTERNAL,
                List.of(new TextPart("control", "plain")));
        String text = "文".repeat(50_000) + " api_key=synthetic-secret sk-syntheticprivate";
        append(
                store,
                "multi",
                MessageRole.ASSISTANT,
                MessageVisibility.AGENT_VISIBLE,
                List.of(new TextPart("synthetic-hidden-reasoning", "plain"), ref(first), ref(second)));
        append(
                store,
                "result",
                MessageRole.TOOL,
                MessageVisibility.AGENT_VISIBLE,
                List.of(new ToolResultPart(first.id(), first.providerCorrelationId(), "stored summary")));
        append(
                store,
                "answer",
                MessageRole.ASSISTANT,
                MessageVisibility.USER_VISIBLE,
                List.of(new TextPart(text, "plain")));
        var full = RunMessageProjector.page(store, RUN, 0, 20);
        assertThat(full.items()).extracting(view -> view.messageId()).containsExactly("multi", "result", "answer");
        assertThat(full.items()).extracting(view -> view.messageIndex()).containsExactly(1L, 2L, 3L);
        assertThat(full.items()).extracting(view -> view.sequence()).containsExactly(3L, 4L, 5L);
        assertThat(full.items().getFirst().text()).isEmpty();
        assertThat(full.items().get(2).text())
                .startsWith("文".repeat(50_000))
                .doesNotContain("synthetic-secret", "sk-syntheticprivate");
        assertThat(full.items()).allSatisfy(view -> assertThat(view.text())
                .doesNotContain("synthetic-hidden-reasoning", "synthetic-hidden-continuation", "control", "input"));
        assertThat(full.items().getFirst().toolCalls())
                .extracting(view -> view.toolName())
                .containsExactly("read", "write");
        assertThat(full.items().getFirst().toolCalls().getFirst().arguments().truncated())
                .isTrue();
        assertThat(full.items()
                        .getFirst()
                        .toolCalls()
                        .getFirst()
                        .arguments()
                        .values()
                        .get("api_key"))
                .isEqualTo("[REDACTED]");
        assertThat(full.items().getFirst().toolCorrelations()).containsEntry("first", "provider-first");
        assertThat(full.items().get(1).text()).isEqualTo("result".repeat(10_000));
        assertThat(full.items().get(1).textTruncated()).isFalse();
        List<io.haifa.agent.runtime.api.RunMessageView> paged = new ArrayList<>();
        long cursor = 0;
        do {
            var page = RunMessageProjector.page(store, RUN, cursor, 1);
            paged.addAll(page.items());
            cursor = page.nextCursor().exclusiveSequence();
            if (!page.hasMore()) break;
        } while (true);
        assertThat(paged).isEqualTo(full.items());
        append(
                store,
                "later",
                MessageRole.ASSISTANT,
                MessageVisibility.USER_VISIBLE,
                List.of(new TextPart("later", "plain")));
        assertThat(RunMessageProjector.page(store, RUN, 0, 20).items().subList(0, 3))
                .isEqualTo(full.items());
        store.redactMessage(new AgentMessageId("multi"));
        store.redactMessage(new AgentMessageId("hidden"));
        store.deleteBefore(RUN, store.headSequence(RUN).orElseThrow() + 1, NOW);
        var redacted = RunMessageProjector.page(store, RUN, 0, 20);
        assertThat(redacted.items()).extracting(view -> view.messageIndex()).containsExactly(1L, 2L, 3L, 4L);
        assertThat(redacted.items().getFirst().text()).isEqualTo("[REDACTED]");
        assertThat(redacted.items().getFirst().toolCalls()).isEmpty();
        append(
                store,
                "already-truncated",
                MessageRole.TOOL,
                MessageVisibility.AGENT_VISIBLE,
                List.of(new ToolResultPart(second.id(), second.providerCorrelationId(), "bounded part")));
        assertThat(RunMessageProjector.page(store, RUN, 0, 20).items().getLast().textTruncated())
                .isTrue();
    }

    @Test
    void notifiesAfterCommitWithReferenceOnlyAndRejectsWrongToolLineage() {
        var store = new InMemoryRuntimeStore();
        AtomicInteger wakes = new AtomicInteger();
        store.registerMessageCommitListener(id -> wakes.incrementAndGet());
        store.execute(() -> {
            append(
                    store,
                    "answer",
                    MessageRole.ASSISTANT,
                    MessageVisibility.USER_VISIBLE,
                    List.of(new TextPart("safe", "plain")));
            assertThat(wakes).hasValue(0);
            return null;
        });
        assertThat(wakes).hasValue(1);
        assertThat(store.eventsFor(RUN)).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo("message.committed");
            assertThat(event.data()).containsOnlyKeys("messageId", "messageSequence");
        });
        var call = call("wrong", "read", Map.of(), false);
        store.appendToolCall(call);
        append(
                store,
                "invalid",
                MessageRole.TOOL,
                MessageVisibility.AGENT_VISIBLE,
                List.of(new ToolResultPart(call.id(), new ProviderToolCallCorrelationId("wrong-correlation"), "safe")));
        assertThatThrownBy(() -> RunMessageProjector.page(store, RUN, 0, 20)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void protectedContinuationAndDeniedToolUseTheSameSafeCommittedMessageSeam() {
        var store = new InMemoryRuntimeStore();
        var call = new ToolCall(
                new ToolCallId("denied"),
                RUN,
                new AgentStepId("step-denied"),
                new ProviderToolCallCorrelationId("provider-denied"),
                new RuntimeIdempotencyKey("denied-key"),
                "write",
                "1",
                new ToolArguments("schema", "1", Map.of()),
                NOW);
        call.beginValidation();
        call.beginPolicyCheck();
        call.waitForApproval();
        call.deny(NOW);
        store.appendToolCall(call);
        var reasoning = io.haifa.agent.model.api.SensitiveModelReasoning.of("synthetic-protected-reasoning");
        var session = new AgentSessionId("session");
        store.appendSessionMessageWithContinuation(
                new SessionMessageDraft(
                        new AgentMessageId("request"),
                        session,
                        Optional.of(RUN),
                        Optional.empty(),
                        MessageRole.ASSISTANT,
                        MessageStatus.COMPLETED,
                        MessageVisibility.AGENT_VISIBLE,
                        List.of(ref(call)),
                        Map.of(),
                        NOW),
                new io.haifa.agent.runtime.core.model.continuation.ModelContinuationDraft(
                        new io.haifa.agent.runtime.core.model.continuation.ModelContinuationRef(
                                "continuation", "1", reasoning.digest(), reasoning.byteLength()),
                        RUN,
                        session,
                        "model-call",
                        "provider",
                        "model",
                        "config",
                        Set.of("provider-denied"),
                        reasoning,
                        NOW));
        append(
                store,
                "denial",
                MessageRole.TOOL,
                MessageVisibility.AGENT_VISIBLE,
                List.of(new ToolResultPart(
                        call.id(), call.providerCorrelationId(), "Tool execution was rejected by the operator.")));
        var page = RunMessageProjector.page(store, RUN, 0, 10);
        assertThat(page.items()).extracting(view -> view.role()).containsExactly("ASSISTANT", "TOOL");
        assertThat(page.items())
                .extracting(view -> view.text())
                .containsExactly("", "Tool execution was rejected by the operator.");
        assertThat(page.items().getLast().toolCalls().getFirst().status()).isEqualTo(ToolCallStatus.DENIED);
        assertThat(page.items())
                .allSatisfy(view -> assertThat(view.text()).doesNotContain("synthetic-protected-reasoning"));
        assertThat(store.eventsFor(RUN)).hasSize(2).allSatisfy(event -> assertThat(event.data())
                .containsOnlyKeys("messageId", "messageSequence"));
    }

    private static ToolCall call(String id, String name, Map<String, Object> arguments, boolean truncated) {
        var call = new ToolCall(
                new ToolCallId(id),
                RUN,
                new AgentStepId("step-" + id),
                new ProviderToolCallCorrelationId("provider-" + id),
                new RuntimeIdempotencyKey("key-" + id),
                name,
                "1",
                new ToolArguments("schema", "1", arguments),
                NOW);
        call.beginValidation();
        call.beginPolicyCheck();
        call.start(NOW);
        call.complete(new ToolResult(true, "result".repeat(10_000), Map.of(), List.of(), List.of(), truncated), NOW);
        return call;
    }

    private static ToolCallPart ref(ToolCall call) {
        return new ToolCallPart(call.id(), call.providerCorrelationId(), call.toolName(), call.toolVersion());
    }

    private static void append(
            InMemoryRuntimeStore store,
            String id,
            MessageRole role,
            MessageVisibility visibility,
            List<ContentPart> contents) {
        store.appendSessionMessage(new SessionMessageDraft(
                new AgentMessageId(id),
                new AgentSessionId("session"),
                Optional.of(RUN),
                Optional.empty(),
                role,
                MessageStatus.COMPLETED,
                visibility,
                contents,
                Map.of(
                        "reasoning",
                        "synthetic-hidden-reasoning",
                        "continuation",
                        "synthetic-hidden-continuation",
                        "runMessageProjected",
                        true),
                NOW));
    }
}
