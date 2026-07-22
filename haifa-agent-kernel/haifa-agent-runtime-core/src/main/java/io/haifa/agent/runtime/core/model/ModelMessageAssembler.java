package io.haifa.agent.runtime.core.model;

import io.haifa.agent.context.api.AgentContext;
import io.haifa.agent.context.api.ContextBuildException;
import io.haifa.agent.context.api.ContextBuildFailure;
import io.haifa.agent.context.item.AssetDerivedTextContent;
import io.haifa.agent.context.item.ContextItem;
import io.haifa.agent.context.item.ContextRole;
import io.haifa.agent.context.item.ConversationSummaryContent;
import io.haifa.agent.context.item.MemoryReferenceContent;
import io.haifa.agent.context.item.MessageContextContent;
import io.haifa.agent.context.item.MessageGroupContextContent;
import io.haifa.agent.context.item.TextContextContent;
import io.haifa.agent.core.content.AssetRefPart;
import io.haifa.agent.core.content.ContentPart;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.content.ToolCallPart;
import io.haifa.agent.core.content.ToolResultPart;
import io.haifa.agent.core.message.AgentMessage;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.model.api.ModelMessage;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The only Runtime boundary that turns Context IR into provider-neutral ModelMessage values. */
public final class ModelMessageAssembler {
    private final RuntimeStateRepository state;

    public ModelMessageAssembler(RuntimeStateRepository state) {
        this.state = Objects.requireNonNull(state, "state must not be null");
    }

    public List<ModelMessage> assemble(AgentRunId runId, AgentContext context) {
        List<ModelMessage> messages = new ArrayList<>();
        context.prompts()
                .forEach(prompt -> messages.add(ModelMessage.text(
                        ModelMessageRole.SYSTEM, "[" + prompt.layer() + "/" + prompt.role() + "] " + prompt.text())));
        Map<io.haifa.agent.core.tool.ToolCallId, ToolCall> toolCalls = state.toolCalls(runId).stream()
                .collect(Collectors.toUnmodifiableMap(ToolCall::id, Function.identity()));
        for (ContextItem item : context.items()) {
            if (item.content() instanceof MessageContextContent message) {
                messages.addAll(mapMessage(message.message(), toolCalls));
            } else if (item.content() instanceof MessageGroupContextContent group) {
                group.messages().forEach(message -> messages.addAll(mapMessage(message, toolCalls)));
            } else if (item.content() instanceof TextContextContent text) {
                messages.add(ModelMessage.text(mapRole(text.role()), text.text()));
            } else if (item.content() instanceof AssetDerivedTextContent asset) {
                messages.add(ModelMessage.text(
                        ModelMessageRole.USER,
                        "[derived " + asset.kind() + " asset=" + asset.asset().assetId() + "]\n" + asset.text()));
            } else if (item.content() instanceof MemoryReferenceContent memory) {
                messages.add(ModelMessage.text(
                        ModelMessageRole.SYSTEM,
                        "[memory " + memory.memoryId() + "@" + memory.version() + "]\n" + memory.text()));
            } else if (item.content() instanceof ConversationSummaryContent summary) {
                messages.add(ModelMessage.text(ModelMessageRole.SYSTEM, renderSummary(summary)));
            } else {
                throw unsupported(item);
            }
        }
        if (messages.isEmpty()) {
            throw new ContextBuildException(
                    ContextBuildFailure.REQUIRED_CONTEXT_TOO_LARGE, "model context must not be empty");
        }
        return List.copyOf(messages);
    }

    private String renderSummary(ConversationSummaryContent summary) {
        List<String> lines = new ArrayList<>();
        lines.add("[conversation-summary " + summary.summaryId() + "@" + summary.version() + "]");
        summary.facts().forEach(value -> lines.add("fact: " + value));
        summary.decisions().forEach(value -> lines.add("decision: " + value));
        summary.openItems().forEach(value -> lines.add("open: " + value));
        summary.toolOutcomeReferences().forEach(value -> lines.add("tool-outcome-ref: " + value));
        return String.join("\n", lines);
    }

    private List<ModelMessage> mapMessage(
            AgentMessage message, Map<io.haifa.agent.core.tool.ToolCallId, ToolCall> authoritativeCalls) {
        List<ToolCallPart> calls = message.contents().stream()
                .filter(ToolCallPart.class::isInstance)
                .map(ToolCallPart.class::cast)
                .toList();
        List<ToolResultPart> results = message.contents().stream()
                .filter(ToolResultPart.class::isInstance)
                .map(ToolResultPart.class::cast)
                .toList();
        if (!results.isEmpty()) {
            return results.stream()
                    .map(result -> {
                        ToolCall call = authoritativeCall(
                                authoritativeCalls, result.toolCallId(), result.providerCorrelationId());
                        return call.result()
                                .map(canonical -> {
                                    if (!canonical.summary().equals(result.summary())) {
                                        throw new IllegalStateException("tool result summary does not match authority");
                                    }
                                    return ModelMessage.tool(
                                            call.providerCorrelationId(),
                                            canonical.summary(),
                                            canonical.structuredData(),
                                            canonical.truncated());
                                })
                                .orElseGet(() -> ModelMessage.tool(call.providerCorrelationId(), result.summary()));
                    })
                    .toList();
        }
        String text = renderText(message.contents());
        if (!calls.isEmpty()) {
            List<ModelToolCall> mapped = calls.stream()
                    .map(part -> {
                        ToolCall call =
                                authoritativeCall(authoritativeCalls, part.toolCallId(), part.providerCorrelationId());
                        if (!call.toolName().equals(part.toolName())
                                || !call.toolVersion().equals(part.toolVersion())) {
                            throw new IllegalStateException("tool call protocol reference does not match authority");
                        }
                        return new ModelToolCall(
                                call.providerCorrelationId(),
                                call.toolName(),
                                call.arguments().values());
                    })
                    .toList();
            return List.of(ModelMessage.assistant(text, mapped));
        }
        if (message.role() == MessageRole.TOOL) {
            throw new IllegalStateException("tool message has no typed provider correlation");
        }
        return List.of(ModelMessage.text(mapRole(message.role()), text));
    }

    private String renderText(List<ContentPart> contents) {
        List<String> values = new ArrayList<>();
        for (ContentPart content : contents) {
            if (content instanceof TextPart text) values.add(text.text());
            else if (content instanceof AssetRefPart) {
                throw new ContextBuildException(
                        ContextBuildFailure.UNSUPPORTED_CONTEXT_CONTENT,
                        "raw asset references require a derived text, OCR, or transcript context item");
            } else if (!(content instanceof ToolCallPart) && !(content instanceof ToolResultPart)) {
                throw new ContextBuildException(
                        ContextBuildFailure.UNSUPPORTED_CONTEXT_CONTENT,
                        "unsupported context content: " + content.contentType());
            }
        }
        return String.join("\n", values).trim();
    }

    private ModelMessageRole mapRole(MessageRole role) {
        return switch (role) {
            case USER -> ModelMessageRole.USER;
            case ASSISTANT -> ModelMessageRole.ASSISTANT;
            case TOOL -> ModelMessageRole.TOOL;
            case SYSTEM, DEVELOPER, AGENT, RUNTIME -> ModelMessageRole.SYSTEM;
        };
    }

    private ModelMessageRole mapRole(ContextRole role) {
        return switch (role) {
            case SYSTEM -> ModelMessageRole.SYSTEM;
            case USER -> ModelMessageRole.USER;
            case ASSISTANT -> ModelMessageRole.ASSISTANT;
            case TOOL -> ModelMessageRole.TOOL;
        };
    }

    private ToolCall authoritativeCall(
            Map<io.haifa.agent.core.tool.ToolCallId, ToolCall> authoritativeCalls,
            io.haifa.agent.core.tool.ToolCallId toolCallId,
            io.haifa.agent.core.tool.ProviderToolCallCorrelationId providerCorrelationId) {
        ToolCall call = authoritativeCalls.get(toolCallId);
        if (call == null) {
            throw new IllegalStateException("canonical tool call is unavailable: " + toolCallId.value());
        }
        if (!call.providerCorrelationId().equals(providerCorrelationId)) {
            throw new IllegalStateException("tool call provider correlation does not match authority");
        }
        return call;
    }

    private ContextBuildException unsupported(ContextItem item) {
        return new ContextBuildException(
                ContextBuildFailure.UNSUPPORTED_CONTEXT_CONTENT,
                "unsupported context item content: " + item.content().getClass().getSimpleName());
    }
}
