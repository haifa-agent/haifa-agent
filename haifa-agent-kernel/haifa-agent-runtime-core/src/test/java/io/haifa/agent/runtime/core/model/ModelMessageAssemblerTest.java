package io.haifa.agent.runtime.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.haifa.agent.context.api.AgentContext;
import io.haifa.agent.context.api.ContextBuildException;
import io.haifa.agent.context.api.ContextBuildFailure;
import io.haifa.agent.context.budget.ContextWindowBudget;
import io.haifa.agent.context.item.AssetDerivedTextContent;
import io.haifa.agent.context.item.ContextItem;
import io.haifa.agent.context.item.ContextItemId;
import io.haifa.agent.context.item.ContextItemType;
import io.haifa.agent.context.item.ContextPriority;
import io.haifa.agent.context.item.ContextProvenance;
import io.haifa.agent.context.item.ContextRetention;
import io.haifa.agent.context.item.ContextRole;
import io.haifa.agent.context.item.ContextSecurity;
import io.haifa.agent.context.item.DerivedTextKind;
import io.haifa.agent.context.item.MessageContextContent;
import io.haifa.agent.context.item.TextContextContent;
import io.haifa.agent.context.prompt.PromptComponent;
import io.haifa.agent.context.prompt.PromptComponentId;
import io.haifa.agent.context.prompt.PromptLayer;
import io.haifa.agent.context.prompt.PromptRole;
import io.haifa.agent.core.content.AssetRefPart;
import io.haifa.agent.core.content.ImageUrlContentPart;
import io.haifa.agent.core.content.StoredImageContentPart;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.content.ToolCallPart;
import io.haifa.agent.core.content.ToolResultPart;
import io.haifa.agent.core.message.AgentMessage;
import io.haifa.agent.core.message.AgentMessageId;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.message.MessageStatus;
import io.haifa.agent.core.message.MessageVisibility;
import io.haifa.agent.core.reference.AssetRef;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.session.AgentSessionId;
import io.haifa.agent.core.step.AgentStepId;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.RuntimeIdempotencyKey;
import io.haifa.agent.core.tool.ToolArguments;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.core.tool.ToolCallId;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.runtime.core.storage.InMemoryRuntimeStore;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModelMessageAssemblerTest {
    private static final AgentRunId RUN_ID = new AgentRunId("run-1");

    @Test
    void preservesPromptAndContextOrderAndMapsDerivedAssetText() {
        AgentContext context = new AgentContext(
                List.of(prompt()),
                List.of(
                        item(
                                "user-text",
                                ContextItemType.RUNTIME_STATE,
                                new TextContextContent(ContextRole.USER, "question")),
                        item(
                                "asset-ocr",
                                ContextItemType.ASSET_DERIVED_TEXT,
                                new AssetDerivedTextContent(
                                        new AssetRef("asset-1", "image/png", "scan.png"),
                                        DerivedTextKind.OCR,
                                        "invoice total 42"))),
                List.of(),
                budget(),
                30);

        var messages = new ModelMessageAssembler(new InMemoryRuntimeStore()).assemble(RUN_ID, context);

        assertThat(messages)
                .extracting(message -> message.role())
                .containsExactly(ModelMessageRole.SYSTEM, ModelMessageRole.USER, ModelMessageRole.USER);
        assertThat(messages.get(0).content()).isEqualTo("[SYSTEM_SAFETY/SYSTEM] follow safety policy");
        assertThat(messages.get(1).content()).isEqualTo("question");
        assertThat(messages.get(2).content()).isEqualTo("[derived OCR asset=asset-1]\ninvoice total 42");
    }

    @Test
    void mapsRuntimeNotificationsToAUserTurn() {
        AgentMessage notification = message(
                "runtime-notification",
                new AgentSessionId("session-1"),
                RUN_ID,
                MessageRole.RUNTIME,
                1,
                List.of(new TextPart("collect the missing evidence", "plain")));
        AgentContext context = new AgentContext(
                List.of(prompt()),
                List.of(item(
                        "runtime-notification",
                        ContextItemType.MESSAGE,
                        new MessageContextContent(notification))),
                List.of(),
                budget(),
                20);

        var messages = new ModelMessageAssembler(new InMemoryRuntimeStore()).assemble(RUN_ID, context);

        assertThat(messages)
                .extracting(message -> message.role())
                .containsExactly(ModelMessageRole.SYSTEM, ModelMessageRole.USER);
        assertThat(messages.getLast().content()).isEqualTo("collect the missing evidence");
    }

    @Test
    void rejectsRawAssetMessageContent() {
        AgentMessage message = new AgentMessage(
                new AgentMessageId("message-1"),
                new AgentSessionId("session-1"),
                Optional.of(RUN_ID),
                Optional.empty(),
                MessageRole.USER,
                MessageStatus.COMPLETED,
                MessageVisibility.USER_VISIBLE,
                1,
                List.of(new AssetRefPart(new AssetRef("asset-1", "image/png", "scan.png"))),
                Map.of(),
                Instant.parse("2026-07-21T00:00:00Z"));
        AgentContext context = new AgentContext(
                List.of(prompt()),
                List.of(item("raw-asset", ContextItemType.MESSAGE, new MessageContextContent(message))),
                List.of(),
                budget(),
                20);

        assertThatThrownBy(() -> new ModelMessageAssembler(new InMemoryRuntimeStore()).assemble(RUN_ID, context))
                .isInstanceOf(ContextBuildException.class)
                .extracting(error -> ((ContextBuildException) error).failure())
                .isEqualTo(ContextBuildFailure.UNSUPPORTED_CONTEXT_CONTENT);
    }

    @Test
    void mapsRemoteAndStoredImagesWithoutPersistingBinaryData() {
        StoredImageContentPart stored = new StoredImageContentPart(
                "personal-local", "img-1", "image/png", 4, "sha256:" + "a".repeat(64), "cat.png");
        AgentMessage message = message(
                "image-message",
                new AgentSessionId("session-1"),
                RUN_ID,
                MessageRole.USER,
                1,
                List.of(
                        new TextPart("describe", "plain"),
                        new ImageUrlContentPart(URI.create("https://images.example.test/cat.png")),
                        stored));
        AgentContext context = new AgentContext(
                List.of(prompt()),
                List.of(item("image-message", ContextItemType.MESSAGE, new MessageContextContent(message))),
                List.of(),
                budget(),
                20);

        var messages = new ModelMessageAssembler(
                        new InMemoryRuntimeStore(),
                        image -> new io.haifa.agent.model.api.ImageDataPart("image/png", new byte[] {1, 2, 3, 4}))
                .assemble(RUN_ID, context);

        assertThat(messages.getLast().content()).isEqualTo("describe");
        assertThat(messages.getLast().images())
                .hasSize(2)
                .anyMatch(io.haifa.agent.model.api.ImageUrlPart.class::isInstance)
                .anyMatch(io.haifa.agent.model.api.ImageDataPart.class::isInstance);
    }

    @Test
    void resolvesToolProtocolHistoryFromTheRunThatCreatedEachMessage() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AgentRunId previousRunId = new AgentRunId("run-previous");
        AgentSessionId sessionId = new AgentSessionId("session-1");
        ToolCallId toolCallId = new ToolCallId("tool-call-1");
        ProviderToolCallCorrelationId correlationId = new ProviderToolCallCorrelationId("provider-tool-call-1");
        ToolCall call = new ToolCall(
                toolCallId,
                previousRunId,
                new AgentStepId("step-1"),
                correlationId,
                new RuntimeIdempotencyKey("idempotency-1"),
                "execution_run",
                "1.0.0",
                new ToolArguments("execution.input", "1.0.0", Map.of("command", "ls")),
                Instant.parse("2026-07-21T00:00:00Z"));
        call.beginValidation();
        call.beginPolicyCheck();
        call.waitForApproval();
        call.deny(Instant.parse("2026-07-21T00:00:01Z"));
        store.appendToolCall(call);

        AgentMessage toolCall = message(
                "assistant-tool-call",
                sessionId,
                previousRunId,
                MessageRole.ASSISTANT,
                1,
                List.of(new ToolCallPart(toolCallId, correlationId, "execution_run", "1.0.0")));
        AgentMessage toolResult = message(
                "rejected-tool-result",
                sessionId,
                previousRunId,
                MessageRole.TOOL,
                2,
                List.of(new ToolResultPart(toolCallId, correlationId, "Tool execution was rejected by the operator.")));
        AgentMessage nextUserMessage = message(
                "next-user", sessionId, RUN_ID, MessageRole.USER, 3, List.of(new TextPart("continue", "plain")));
        AgentContext context = new AgentContext(
                List.of(prompt()),
                List.of(
                        item("assistant-tool-call", ContextItemType.MESSAGE, new MessageContextContent(toolCall)),
                        item("rejected-tool-result", ContextItemType.MESSAGE, new MessageContextContent(toolResult)),
                        item("next-user", ContextItemType.MESSAGE, new MessageContextContent(nextUserMessage))),
                List.of(),
                budget(),
                30);

        var messages = new ModelMessageAssembler(store).assemble(RUN_ID, context);

        assertThat(messages)
                .extracting(message -> message.role())
                .containsExactly(
                        ModelMessageRole.SYSTEM,
                        ModelMessageRole.ASSISTANT,
                        ModelMessageRole.TOOL,
                        ModelMessageRole.USER);
        assertThat(messages.get(1).toolCalls().getFirst().providerCorrelationId())
                .isEqualTo(correlationId);
        assertThat(messages.get(2).providerCorrelationId()).contains(correlationId);
        assertThat(messages.get(2).content()).isEqualTo("Tool execution was rejected by the operator.");
    }

    private static AgentMessage message(
            String id,
            AgentSessionId sessionId,
            AgentRunId runId,
            MessageRole role,
            long sequence,
            List<io.haifa.agent.core.content.ContentPart> contents) {
        return new AgentMessage(
                new AgentMessageId(id),
                sessionId,
                Optional.of(runId),
                Optional.empty(),
                role,
                MessageStatus.COMPLETED,
                role == MessageRole.USER ? MessageVisibility.USER_VISIBLE : MessageVisibility.AGENT_VISIBLE,
                sequence,
                contents,
                Map.of(),
                Instant.parse("2026-07-21T00:00:00Z").plusSeconds(sequence));
    }

    private static PromptComponent prompt() {
        return new PromptComponent(
                new PromptComponentId("safety"),
                "1",
                PromptLayer.SYSTEM_SAFETY,
                PromptRole.SYSTEM,
                "follow safety policy",
                false,
                Set.of("internal"));
    }

    private static ContextItem item(
            String id, ContextItemType type, io.haifa.agent.context.item.ContextContent content) {
        return new ContextItem(
                new ContextItemId(id),
                type,
                content,
                10,
                ContextPriority.NORMAL,
                ContextRetention.KEEP_IF_RELEVANT,
                ContextSecurity.INTERNAL,
                new ContextProvenance("test", id, "1", "hash-" + id),
                Map.of());
    }

    private static ContextWindowBudget budget() {
        return new ContextWindowBudget(200, 50, 10, 140, 1_000, 1_000);
    }
}
