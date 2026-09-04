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
import io.haifa.agent.core.content.StoredAudioContentPart;
import io.haifa.agent.core.content.StoredImageContentPart;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.content.ToolCallPart;
import io.haifa.agent.core.content.ToolResultPart;
import io.haifa.agent.core.error.AgentError;
import io.haifa.agent.core.error.AgentErrorCode;
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
import io.haifa.agent.core.tool.ToolExecutionError;
import io.haifa.agent.model.api.CredentialRef;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelCapability;
import io.haifa.agent.model.api.ModelDefinitionId;
import io.haifa.agent.model.api.ModelMessage;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelProviderId;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.model.api.SensitiveModelReasoning;
import io.haifa.agent.runtime.core.bootstrap.DefaultResolvedModelSnapshots;
import io.haifa.agent.runtime.core.model.continuation.ModelContinuationDraft;
import io.haifa.agent.runtime.core.model.continuation.ModelContinuationException;
import io.haifa.agent.runtime.core.model.continuation.ModelContinuationFailure;
import io.haifa.agent.runtime.core.model.continuation.ModelContinuationRef;
import io.haifa.agent.runtime.core.storage.InMemoryRuntimeStore;
import io.haifa.agent.runtime.core.storage.SessionMessageDraft;
import java.net.URI;
import java.time.Instant;
import java.util.EnumSet;
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
                List.of(item("runtime-notification", ContextItemType.MESSAGE, new MessageContextContent(notification))),
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
    void mapsStoredAudioOnlyAtTheModelBoundary() {
        StoredAudioContentPart stored = new StoredAudioContentPart(
                "personal-local", "audio-1", "audio/wav", 4, "sha256:" + "b".repeat(64), "sample.wav");
        AgentMessage message = message(
                "audio-message",
                new AgentSessionId("session-1"),
                RUN_ID,
                MessageRole.USER,
                1,
                List.of(new TextPart("transcribe", "plain"), stored));
        AgentContext context = new AgentContext(
                List.of(prompt()),
                List.of(item("audio-message", ContextItemType.MESSAGE, new MessageContextContent(message))),
                List.of(),
                budget(),
                20);

        var messages = new ModelMessageAssembler(
                        new InMemoryRuntimeStore(),
                        ModelImageResolver.unsupported(),
                        audio -> new io.haifa.agent.model.api.AudioDataPart("audio/wav", new byte[] {1, 2, 3, 4}))
                .assemble(RUN_ID, context);

        assertThat(messages.getLast().content()).isEqualTo("transcribe");
        assertThat(messages.getLast().audios())
                .singleElement()
                .isInstanceOf(io.haifa.agent.model.api.AudioDataPart.class);
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

    @Test
    void passesCanonicalBoundedExecutionFailureFactsIntoTheModelToolMessage() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AgentSessionId sessionId = new AgentSessionId("session-1");
        ToolCallId toolCallId = new ToolCallId("tool-call-failed");
        ProviderToolCallCorrelationId correlationId = new ProviderToolCallCorrelationId("provider-call-failed");
        ToolCall call = new ToolCall(
                toolCallId,
                RUN_ID,
                new AgentStepId("step-failed"),
                correlationId,
                new RuntimeIdempotencyKey("idempotency-failed"),
                "execution_run",
                "1.7.2",
                new ToolArguments("haifa.execution.run.input", "1.7.2", Map.of("command", "mvn test")),
                Instant.parse("2026-07-21T00:00:00Z"));
        call.beginValidation();
        call.beginPolicyCheck();
        call.start(Instant.parse("2026-07-21T00:00:01Z"));
        var canonicalResult = new io.haifa.agent.core.tool.ToolResult(
                false,
                "Command failed (exit 1)\nbounded stderr tail",
                Map.ofEntries(
                        Map.entry("status", "FAILED"),
                        Map.entry("exitCode", 1),
                        Map.entry("durationMillis", 1240L),
                        Map.entry("truncated", true),
                        Map.entry("output", "bounded stderr tail"),
                        Map.entry("failureCategory", "COMMAND_FAILED"),
                        Map.entry("stableFailureCode", "NON_ZERO_EXIT"),
                        Map.entry("failureActionCode", "CONTINUE_WITH_DIAGNOSTIC")),
                List.of(),
                List.of(),
                true);
        call.fail(
                new ToolExecutionError(new AgentError(
                        AgentErrorCode.TOOL_BUSINESS_FAILURE,
                        Map.of("failureCategory", "COMMAND_FAILED", "stableFailureCode", "NON_ZERO_EXIT"),
                        "diagnostic-failed",
                        Instant.parse("2026-07-21T00:00:02Z"))),
                canonicalResult,
                Instant.parse("2026-07-21T00:00:02Z"));
        store.appendToolCall(call);

        AgentMessage toolResult = message(
                "tool-result-failed",
                sessionId,
                RUN_ID,
                MessageRole.TOOL,
                1,
                List.of(new ToolResultPart(toolCallId, correlationId, canonicalResult.summary())));
        AgentContext context = new AgentContext(
                List.of(prompt()),
                List.of(item("tool-result-failed", ContextItemType.MESSAGE, new MessageContextContent(toolResult))),
                List.of(),
                budget(),
                30);

        ModelMessage modelMessage =
                new ModelMessageAssembler(store).assemble(RUN_ID, context).getLast();

        assertThat(modelMessage.content()).contains("bounded stderr tail");
        assertThat(modelMessage.toolResultData()).containsAllEntriesOf(canonicalResult.structuredData());
        assertThat(modelMessage.toolResultTruncated()).isTrue();
    }

    @Test
    void omitsProviderContinuationWhenConversationSwitchesModelConfiguration() {
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
                "utility_search",
                "1.0.0",
                new ToolArguments("search.input", "1.0.0", Map.of("query", "haifa")),
                Instant.parse("2026-07-21T00:00:00Z"));
        call.beginValidation();
        call.beginPolicyCheck();
        call.waitForApproval();
        call.deny(Instant.parse("2026-07-21T00:00:01Z"));
        store.appendToolCall(call);

        ResolvedModelSnapshot previousModel = DefaultResolvedModelSnapshots.deepSeekV4Pro();
        SensitiveModelReasoning reasoning = SensitiveModelReasoning.of("provider-private-continuation");
        AgentMessage assistant = store.appendSessionMessageWithContinuation(
                new SessionMessageDraft(
                        new AgentMessageId("assistant-tool-call"),
                        sessionId,
                        Optional.of(previousRunId),
                        Optional.empty(),
                        MessageRole.ASSISTANT,
                        MessageStatus.COMPLETED,
                        MessageVisibility.AGENT_VISIBLE,
                        List.of(new ToolCallPart(toolCallId, correlationId, "utility_search", "1.0.0")),
                        Map.of(),
                        Instant.parse("2026-07-21T00:00:02Z")),
                new ModelContinuationDraft(
                        new ModelContinuationRef("continuation-1", "1.0", reasoning.digest(), reasoning.byteLength()),
                        previousRunId,
                        sessionId,
                        "model-call-1",
                        previousModel.providerId().value(),
                        previousModel.providerModelId(),
                        previousModel.configurationDigest(),
                        Set.of(correlationId.value()),
                        reasoning,
                        Instant.parse("2026-07-21T00:00:02Z")));
        AgentMessage toolResult = message(
                "tool-result",
                sessionId,
                previousRunId,
                MessageRole.TOOL,
                2,
                List.of(new ToolResultPart(toolCallId, correlationId, "Tool execution was rejected by the operator.")));
        AgentMessage user = message(
                "next-user", sessionId, RUN_ID, MessageRole.USER, 3, List.of(new TextPart("continue", "plain")));
        AgentContext context = new AgentContext(
                List.of(prompt()),
                List.of(
                        item("assistant", ContextItemType.MESSAGE, new MessageContextContent(assistant)),
                        item("tool-result", ContextItemType.MESSAGE, new MessageContextContent(toolResult)),
                        item("user", ContextItemType.MESSAGE, new MessageContextContent(user))),
                List.of(),
                budget(),
                40);

        ResolvedModelSnapshot anthropicModel = ResolvedModelSnapshot.create(
                previousModel.providerId(),
                previousModel.providerVersion(),
                new ModelDefinitionId("deepseek-anthropic-flash"),
                previousModel.modelVersion(),
                previousModel.providerModelId(),
                ModelApiStyles.ANTHROPIC_MESSAGES_ADAPTER,
                previousModel.adapterVersion(),
                ModelApiStyles.ANTHROPIC_MESSAGES,
                "deepseek-anthropic-messages",
                URI.create("https://api.deepseek.com/anthropic"),
                previousModel.credentialRef(),
                previousModel.nativeStreaming(),
                previousModel.capabilities(),
                previousModel.contextWindow(),
                previousModel.maxOutputTokens(),
                previousModel.providerOptions(),
                previousModel.invocationOptions());

        var messages = new ModelMessageAssembler(store).assemble(RUN_ID, context, anthropicModel);

        assertThat(messages)
                .filteredOn(message -> message.role() == ModelMessageRole.ASSISTANT)
                .singleElement()
                .satisfies(message -> {
                    assertThat(message.toolCalls()).singleElement();
                    assertThat(message.reasoning()).isEmpty();
                });
    }

    @Test
    void compressesCompletedPriorProviderToolGroupIntoNeutralSummaryWhenSwitchingProvider() {
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
                "utility_search",
                "1.0.0",
                new ToolArguments("search.input", "1.0.0", Map.of("query", "haifa agent")),
                Instant.parse("2026-07-21T00:00:00Z"));
        call.beginValidation();
        call.beginPolicyCheck();
        call.start(Instant.parse("2026-07-21T00:00:01Z"));
        var canonicalResult = new io.haifa.agent.core.tool.ToolResult(
                true, "found 3 results for haifa agent", Map.of("count", 3), List.of(), List.of(), false);
        call.complete(canonicalResult, Instant.parse("2026-07-21T00:00:02Z"));
        store.appendToolCall(call);

        ResolvedModelSnapshot deepSeekModel = DefaultResolvedModelSnapshots.deepSeekV4Pro();
        AgentMessage assistant = store.appendSessionMessage(new SessionMessageDraft(
                new AgentMessageId("assistant-tool-call"),
                sessionId,
                Optional.of(previousRunId),
                Optional.empty(),
                MessageRole.ASSISTANT,
                MessageStatus.COMPLETED,
                MessageVisibility.AGENT_VISIBLE,
                List.of(
                        new TextPart("Let me search for that.", "plain"),
                        new ToolCallPart(toolCallId, correlationId, "utility_search", "1.0.0")),
                Map.of("providerId", deepSeekModel.providerId().value()),
                Instant.parse("2026-07-21T00:00:02Z")));
        AgentMessage toolResult = message(
                "tool-result",
                sessionId,
                previousRunId,
                MessageRole.TOOL,
                2,
                List.of(new ToolResultPart(toolCallId, correlationId, canonicalResult.summary())));
        AgentMessage user = message(
                "next-user", sessionId, RUN_ID, MessageRole.USER, 3, List.of(new TextPart("summarize", "plain")));
        AgentContext context = new AgentContext(
                List.of(prompt()),
                List.of(
                        item("assistant", ContextItemType.MESSAGE, new MessageContextContent(assistant)),
                        item("tool-result", ContextItemType.MESSAGE, new MessageContextContent(toolResult)),
                        item("user", ContextItemType.MESSAGE, new MessageContextContent(user))),
                List.of(),
                budget(),
                40);

        ResolvedModelSnapshot openAiModel = ResolvedModelSnapshot.create(
                new ModelProviderId("openai"),
                "2026-07-21",
                new ModelDefinitionId("gpt-4o"),
                "2026-07-21",
                "gpt-4o",
                ModelApiStyles.OPENAI_RESPONSES_ADAPTER,
                "1.0.0",
                ModelApiStyles.OPENAI_RESPONSES,
                "standard",
                URI.create("https://api.openai.com"),
                new CredentialRef("env://OPENAI_API_KEY"),
                true,
                EnumSet.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING),
                128_000,
                4_096,
                Map.of(),
                Map.of());

        var messages = new ModelMessageAssembler(store).assemble(RUN_ID, context, openAiModel);

        assertThat(messages).noneMatch(m -> m.role() == ModelMessageRole.TOOL);

        List<ModelMessage> assistantMessages = messages.stream()
                .filter(m -> m.role() == ModelMessageRole.ASSISTANT)
                .toList();
        assertThat(assistantMessages).singleElement().satisfies(msg -> {
            assertThat(msg.toolCalls()).isEmpty();
            assertThat(msg.content()).contains("Let me search for that.");
            assertThat(msg.content()).contains("[tool-call: utility_search arguments: {\"query\": \"haifa agent\"}]");
            assertThat(msg.content()).contains("[tool-result: utility_search]");
            assertThat(msg.content()).contains("found 3 results for haifa agent");
        });
    }

    @Test
    void rejectsUnclosedPriorProviderToolGroupWhenSwitchingProvider() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AgentRunId previousRunId = new AgentRunId("run-previous");
        AgentSessionId sessionId = new AgentSessionId("session-1");
        ToolCallId toolCallId = new ToolCallId("tool-call-unclosed");
        ProviderToolCallCorrelationId correlationId = new ProviderToolCallCorrelationId("provider-tool-call-unclosed");
        ToolCall call = new ToolCall(
                toolCallId,
                previousRunId,
                new AgentStepId("step-unclosed"),
                correlationId,
                new RuntimeIdempotencyKey("idempotency-unclosed"),
                "utility_search",
                "1.0.0",
                new ToolArguments("search.input", "1.0.0", Map.of("query", "haifa unclosed")),
                Instant.parse("2026-07-21T00:00:00Z"));
        call.beginValidation();
        store.appendToolCall(call);

        ResolvedModelSnapshot deepSeekModel = DefaultResolvedModelSnapshots.deepSeekV4Pro();
        AgentMessage assistant = store.appendSessionMessage(new SessionMessageDraft(
                new AgentMessageId("assistant-tool-call-unclosed"),
                sessionId,
                Optional.of(previousRunId),
                Optional.empty(),
                MessageRole.ASSISTANT,
                MessageStatus.COMPLETED,
                MessageVisibility.AGENT_VISIBLE,
                List.of(new ToolCallPart(toolCallId, correlationId, "utility_search", "1.0.0")),
                Map.of("providerId", deepSeekModel.providerId().value()),
                Instant.parse("2026-07-21T00:00:02Z")));
        AgentMessage user = message(
                "next-user", sessionId, RUN_ID, MessageRole.USER, 2, List.of(new TextPart("continue", "plain")));
        AgentContext context = new AgentContext(
                List.of(prompt()),
                List.of(
                        item("assistant", ContextItemType.MESSAGE, new MessageContextContent(assistant)),
                        item("user", ContextItemType.MESSAGE, new MessageContextContent(user))),
                List.of(),
                budget(),
                40);

        ResolvedModelSnapshot openAiModel = ResolvedModelSnapshot.create(
                new ModelProviderId("openai"),
                "2026-07-21",
                new ModelDefinitionId("gpt-4o"),
                "2026-07-21",
                "gpt-4o",
                ModelApiStyles.OPENAI_RESPONSES_ADAPTER,
                "1.0.0",
                ModelApiStyles.OPENAI_RESPONSES,
                "standard",
                URI.create("https://api.openai.com"),
                new CredentialRef("env://OPENAI_API_KEY"),
                true,
                EnumSet.of(ModelCapability.TEXT_CHAT, ModelCapability.TOOL_CALLING),
                128_000,
                4_096,
                Map.of(),
                Map.of());

        assertThatThrownBy(() -> new ModelMessageAssembler(store).assemble(RUN_ID, context, openAiModel))
                .isInstanceOf(ModelContinuationException.class)
                .satisfies(error -> {
                    ModelContinuationException mce = (ModelContinuationException) error;
                    assertThat(mce.failure()).isEqualTo(ModelContinuationFailure.CROSS_MODEL_UNCLOSED_TOOL_GROUP);
                    assertThat(mce.getMessage()).contains("模型切换需要新会话或先完成原模型工具轮次");
                });
    }

    @Test
    void movesRuntimeControlMessagesBehindTheCompleteToolCallGroup() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AgentSessionId sessionId = new AgentSessionId("session-1");
        ToolCallId toolCallId = new ToolCallId("tool-call-1");
        ProviderToolCallCorrelationId correlationId = new ProviderToolCallCorrelationId("provider-tool-call-1");
        ToolCall call = new ToolCall(
                toolCallId,
                RUN_ID,
                new AgentStepId("step-1"),
                correlationId,
                new RuntimeIdempotencyKey("idempotency-1"),
                "execution_run",
                "1.0.0",
                new ToolArguments("execution.input", "1.0.0", Map.of("command", "rg --files")),
                Instant.parse("2026-07-21T00:00:00Z"));
        call.beginValidation();
        call.beginPolicyCheck();
        call.waitForApproval();
        call.deny(Instant.parse("2026-07-21T00:00:01Z"));
        store.appendToolCall(call);

        AgentMessage assistant = message(
                "assistant-tool-call",
                sessionId,
                RUN_ID,
                MessageRole.ASSISTANT,
                1,
                List.of(new ToolCallPart(toolCallId, correlationId, "execution_run", "1.0.0")));
        AgentMessage runtime = message(
                "runtime-control",
                sessionId,
                RUN_ID,
                MessageRole.RUNTIME,
                2,
                List.of(new TextPart("[RUNTIME_CONTROL_UPDATE] retry safely", "plain")));
        AgentMessage result = message(
                "tool-result",
                sessionId,
                RUN_ID,
                MessageRole.TOOL,
                3,
                List.of(new ToolResultPart(toolCallId, correlationId, "Tool execution was rejected by the operator.")));
        AgentMessage user = message(
                "next-user", sessionId, RUN_ID, MessageRole.USER, 4, List.of(new TextPart("continue", "plain")));
        AgentContext context = new AgentContext(
                List.of(prompt()),
                List.of(
                        item("assistant", ContextItemType.MESSAGE, new MessageContextContent(assistant)),
                        item("runtime", ContextItemType.MESSAGE, new MessageContextContent(runtime)),
                        item("result", ContextItemType.MESSAGE, new MessageContextContent(result)),
                        item("user", ContextItemType.MESSAGE, new MessageContextContent(user))),
                List.of(),
                budget(),
                40);

        var messages = new ModelMessageAssembler(store).assemble(RUN_ID, context);

        assertThat(messages)
                .extracting(ModelMessage::role)
                .containsExactly(
                        ModelMessageRole.SYSTEM,
                        ModelMessageRole.ASSISTANT,
                        ModelMessageRole.TOOL,
                        ModelMessageRole.USER,
                        ModelMessageRole.USER);
        assertThat(messages.get(2).providerCorrelationId()).contains(correlationId);
        assertThat(messages.get(3).content()).contains("RUNTIME_CONTROL_UPDATE");
    }

    @Test
    void mapsSummaryWithUserAnchoredMultiStepToolTurn() {
        InMemoryRuntimeStore store = new InMemoryRuntimeStore();
        AgentSessionId sessionId = new AgentSessionId("session-1");
        ToolCallId toolCall1 = new ToolCallId("tool-call-1");
        ProviderToolCallCorrelationId corr1 = new ProviderToolCallCorrelationId("provider-call-1");
        ToolCallId toolCall2 = new ToolCallId("tool-call-2");
        ProviderToolCallCorrelationId corr2 = new ProviderToolCallCorrelationId("provider-call-2");

        ToolCall call1 = new ToolCall(
                toolCall1,
                RUN_ID,
                new AgentStepId("step-1"),
                corr1,
                new RuntimeIdempotencyKey("idempotency-1"),
                "echo",
                "1.0.0",
                new ToolArguments("echo.input", "1.0.0", Map.of("text", "first")),
                Instant.parse("2026-07-21T00:00:00Z"));
        call1.beginValidation();
        call1.beginPolicyCheck();
        call1.start(Instant.parse("2026-07-21T00:00:01Z"));
        call1.complete(
                new io.haifa.agent.core.tool.ToolResult(
                        true, "first result", Map.of("text", "first"), List.of(), List.of(), false),
                Instant.parse("2026-07-21T00:00:02Z"));
        store.appendToolCall(call1);

        ToolCall call2 = new ToolCall(
                toolCall2,
                RUN_ID,
                new AgentStepId("step-2"),
                corr2,
                new RuntimeIdempotencyKey("idempotency-2"),
                "echo",
                "1.0.0",
                new ToolArguments("echo.input", "1.0.0", Map.of("text", "second")),
                Instant.parse("2026-07-21T00:00:03Z"));
        call2.beginValidation();
        call2.beginPolicyCheck();
        call2.start(Instant.parse("2026-07-21T00:00:04Z"));
        call2.complete(
                new io.haifa.agent.core.tool.ToolResult(
                        true, "second result", Map.of("text", "second"), List.of(), List.of(), false),
                Instant.parse("2026-07-21T00:00:05Z"));
        store.appendToolCall(call2);

        var summaryContent = new io.haifa.agent.context.item.ConversationSummaryContent(
                "sum-1", 1, List.of("fact: turn 0 completed"), List.of(), List.of(), List.of());
        AgentMessage user = message(
                "user-msg", sessionId, RUN_ID, MessageRole.USER, 1, List.of(new TextPart("run multi-step", "plain")));
        AgentMessage assistant1 = message(
                "asst-1",
                sessionId,
                RUN_ID,
                MessageRole.ASSISTANT,
                2,
                List.of(new ToolCallPart(toolCall1, corr1, "echo", "1.0.0")));
        AgentMessage tool1 = message(
                "tool-1",
                sessionId,
                RUN_ID,
                MessageRole.TOOL,
                3,
                List.of(new ToolResultPart(toolCall1, corr1, "first result")));
        AgentMessage assistant2 = message(
                "asst-2",
                sessionId,
                RUN_ID,
                MessageRole.ASSISTANT,
                4,
                List.of(new ToolCallPart(toolCall2, corr2, "echo", "1.0.0")));
        AgentMessage tool2 = message(
                "tool-2",
                sessionId,
                RUN_ID,
                MessageRole.TOOL,
                5,
                List.of(new ToolResultPart(toolCall2, corr2, "second result")));

        AgentContext context = new AgentContext(
                List.of(prompt()),
                List.of(
                        item("summary", ContextItemType.CONVERSATION_SUMMARY, summaryContent),
                        item("user", ContextItemType.MESSAGE, new MessageContextContent(user)),
                        item("asst-1", ContextItemType.MESSAGE, new MessageContextContent(assistant1)),
                        item("tool-1", ContextItemType.MESSAGE, new MessageContextContent(tool1)),
                        item("asst-2", ContextItemType.MESSAGE, new MessageContextContent(assistant2)),
                        item("tool-2", ContextItemType.MESSAGE, new MessageContextContent(tool2))),
                List.of(),
                budget(),
                50);

        var messages = new ModelMessageAssembler(store).assemble(RUN_ID, context);

        assertThat(messages)
                .extracting(ModelMessage::role)
                .containsExactly(
                        ModelMessageRole.SYSTEM, // prompt
                        ModelMessageRole.SYSTEM, // summary
                        ModelMessageRole.USER, // turn anchor
                        ModelMessageRole.ASSISTANT, // call 1
                        ModelMessageRole.TOOL, // result 1
                        ModelMessageRole.ASSISTANT, // call 2
                        ModelMessageRole.TOOL); // result 2
        assertThat(messages.get(1).content()).contains("conversation-summary");
        assertThat(messages.get(2).content()).isEqualTo("run multi-step");
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
        return new ContextWindowBudget(200, 50, 10, 140);
    }
}
