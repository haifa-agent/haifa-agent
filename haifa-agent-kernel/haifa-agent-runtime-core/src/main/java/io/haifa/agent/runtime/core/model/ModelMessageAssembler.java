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
import io.haifa.agent.core.content.ImageUrlContentPart;
import io.haifa.agent.core.content.StoredImageContentPart;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.content.ToolCallPart;
import io.haifa.agent.core.content.ToolResultPart;
import io.haifa.agent.core.message.AgentMessage;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.model.api.ImageUrlPart;
import io.haifa.agent.model.api.ModelImagePart;
import io.haifa.agent.model.api.ModelMessage;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The only Runtime boundary that turns Context IR into provider-neutral ModelMessage values. */
public final class ModelMessageAssembler {
    private final RuntimeStateRepository state;
    private final ModelImageResolver images;

    public ModelMessageAssembler(RuntimeStateRepository state) {
        this(state, ModelImageResolver.unsupported());
    }

    public ModelMessageAssembler(RuntimeStateRepository state, ModelImageResolver images) {
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.images = Objects.requireNonNull(images, "images must not be null");
    }

    public List<ModelMessage> assemble(AgentRunId runId, AgentContext context) {
        return assemble(runId, context, null);
    }

    public List<ModelMessage> assemble(AgentRunId runId, AgentContext context, ResolvedModelSnapshot model) {
        List<ModelMessage> messages = new ArrayList<>();
        context.prompts()
                .forEach(prompt -> messages.add(ModelMessage.text(
                        ModelMessageRole.SYSTEM, "[" + prompt.layer() + "/" + prompt.role() + "] " + prompt.text())));
        Map<AgentRunId, Map<io.haifa.agent.core.tool.ToolCallId, ToolCall>> toolCallsByRun = new HashMap<>();
        for (ContextItem item : context.items()) {
            if (item.content() instanceof MessageContextContent message) {
                messages.addAll(mapMessage(runId, message.message(), toolCallsByRun, model));
            } else if (item.content() instanceof MessageGroupContextContent group) {
                group.messages().forEach(message -> messages.addAll(mapMessage(runId, message, toolCallsByRun, model)));
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
        return canonicalizeToolProtocol(messages);
    }

    /**
     * Keeps every provider tool-call group contiguous at the model boundary.
     *
     * <p>Runtime recovery, approval, steering, and other control messages may be persisted while tools execute. The
     * OpenAI tool protocol nevertheless requires the assistant tool-call message to be followed immediately by one
     * tool result for every provider correlation id. Control messages retain their relative order, but move behind
     * the completed tool group before the request is serialized.
     */
    private List<ModelMessage> canonicalizeToolProtocol(List<ModelMessage> messages) {
        List<ModelMessage> canonical = new ArrayList<>(messages.size());
        int index = 0;
        while (index < messages.size()) {
            ModelMessage message = messages.get(index++);
            canonical.add(message);
            if (message.role() != ModelMessageRole.ASSISTANT
                    || message.toolCalls().isEmpty()) {
                continue;
            }

            var pending = message.toolCalls().stream()
                    .map(ModelToolCall::providerCorrelationId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            List<ModelMessage> deferred = new ArrayList<>();
            while (!pending.isEmpty() && index < messages.size()) {
                ModelMessage candidate = messages.get(index++);
                if (candidate.role() == ModelMessageRole.TOOL
                        && candidate
                                .providerCorrelationId()
                                .filter(pending::remove)
                                .isPresent()) {
                    canonical.add(candidate);
                } else {
                    deferred.add(candidate);
                }
            }
            if (!pending.isEmpty()) {
                throw new IllegalStateException("model context contains an incomplete tool-call group");
            }
            canonical.addAll(deferred);
        }
        return List.copyOf(canonical);
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
            AgentRunId currentRunId,
            AgentMessage message,
            Map<AgentRunId, Map<io.haifa.agent.core.tool.ToolCallId, ToolCall>> toolCallsByRun,
            ResolvedModelSnapshot model) {
        AgentRunId messageRunId = message.runId().orElse(currentRunId);
        Map<io.haifa.agent.core.tool.ToolCallId, ToolCall> authoritativeCalls =
                toolCallsByRun.computeIfAbsent(messageRunId, this::toolCallsById);
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
            var continuation = state.continuationForMessage(message.id());
            if (continuation.isEmpty()) return List.of(ModelMessage.assistant(text, mapped));
            if (model == null) {
                throw new IllegalStateException("model snapshot is required to resolve provider continuation");
            }
            var correlations = mapped.stream()
                    .map(call -> call.providerCorrelationId().value())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            return List.of(
                    ModelMessage.assistant(text, mapped, state.resolveContinuation(message.id(), model, correlations)));
        }
        if (message.role() == MessageRole.TOOL) {
            throw new IllegalStateException("tool message has no typed provider correlation");
        }
        List<ModelImagePart> mappedImages = renderImages(message.contents());
        if (!mappedImages.isEmpty()) {
            if (message.role() != MessageRole.USER) {
                throw new ContextBuildException(
                        ContextBuildFailure.UNSUPPORTED_CONTEXT_CONTENT,
                        "image inputs are only allowed on user messages");
            }
            return List.of(ModelMessage.user(text, mappedImages));
        }
        return List.of(ModelMessage.text(mapRole(message.role()), text));
    }

    private Map<io.haifa.agent.core.tool.ToolCallId, ToolCall> toolCallsById(AgentRunId runId) {
        return state.toolCalls(runId).stream().collect(Collectors.toUnmodifiableMap(ToolCall::id, Function.identity()));
    }

    private String renderText(List<ContentPart> contents) {
        List<String> values = new ArrayList<>();
        for (ContentPart content : contents) {
            if (content instanceof TextPart text) values.add(text.text());
            else if (content instanceof AssetRefPart) {
                throw new ContextBuildException(
                        ContextBuildFailure.UNSUPPORTED_CONTEXT_CONTENT,
                        "raw asset references require a derived text, OCR, or transcript context item");
            } else if (!(content instanceof ImageUrlContentPart)
                    && !(content instanceof StoredImageContentPart)
                    && !(content instanceof ToolCallPart)
                    && !(content instanceof ToolResultPart)) {
                throw new ContextBuildException(
                        ContextBuildFailure.UNSUPPORTED_CONTEXT_CONTENT,
                        "unsupported context content: " + content.contentType());
            }
        }
        return String.join("\n", values).trim();
    }

    private List<ModelImagePart> renderImages(List<ContentPart> contents) {
        List<ModelImagePart> values = new ArrayList<>();
        for (ContentPart content : contents) {
            if (content instanceof ImageUrlContentPart image) values.add(new ImageUrlPart(image.url()));
            else if (content instanceof StoredImageContentPart image) values.add(images.resolve(image));
        }
        return List.copyOf(values);
    }

    private ModelMessageRole mapRole(MessageRole role) {
        return switch (role) {
            case USER -> ModelMessageRole.USER;
            case ASSISTANT -> ModelMessageRole.ASSISTANT;
            case TOOL -> ModelMessageRole.TOOL;
            case RUNTIME -> ModelMessageRole.USER;
            case SYSTEM, DEVELOPER, AGENT -> ModelMessageRole.SYSTEM;
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
