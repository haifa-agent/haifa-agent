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
import io.haifa.agent.core.content.StoredAudioContentPart;
import io.haifa.agent.core.content.StoredImageContentPart;
import io.haifa.agent.core.content.TextPart;
import io.haifa.agent.core.content.ToolCallPart;
import io.haifa.agent.core.content.ToolResultPart;
import io.haifa.agent.core.message.AgentMessage;
import io.haifa.agent.core.message.MessageRole;
import io.haifa.agent.core.run.AgentRunId;
import io.haifa.agent.core.tool.ProviderToolCallCorrelationId;
import io.haifa.agent.core.tool.ToolCall;
import io.haifa.agent.model.api.ImageUrlPart;
import io.haifa.agent.model.api.ModelApiStyles;
import io.haifa.agent.model.api.ModelAudioPart;
import io.haifa.agent.model.api.ModelImagePart;
import io.haifa.agent.model.api.ModelMessage;
import io.haifa.agent.model.api.ModelMessageRole;
import io.haifa.agent.model.api.ModelToolCall;
import io.haifa.agent.model.api.ResolvedModelSnapshot;
import io.haifa.agent.runtime.core.storage.RuntimeStateRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The only Runtime boundary that turns Context IR into provider-neutral ModelMessage values. */
public final class ModelMessageAssembler {
    private final RuntimeStateRepository state;
    private final ModelImageResolver images;
    private final ModelAudioResolver audios;

    public ModelMessageAssembler(RuntimeStateRepository state) {
        this(state, ModelImageResolver.unsupported(), ModelAudioResolver.unsupported());
    }

    public ModelMessageAssembler(RuntimeStateRepository state, ModelImageResolver images) {
        this(state, images, ModelAudioResolver.unsupported());
    }

    public ModelMessageAssembler(RuntimeStateRepository state, ModelImageResolver images, ModelAudioResolver audios) {
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.images = Objects.requireNonNull(images, "images must not be null");
        this.audios = Objects.requireNonNull(audios, "audios must not be null");
    }

    public List<ModelMessage> assemble(AgentRunId runId, AgentContext context) {
        return assemble(runId, context, null);
    }

    public List<ModelMessage> assemble(AgentRunId runId, AgentContext context, ResolvedModelSnapshot model) {
        List<ModelMessage> messages = new ArrayList<>();
        Set<ModelMessage> priorModelAssistants = Collections.newSetFromMap(new IdentityHashMap<>());
        context.prompts()
                .forEach(prompt -> messages.add(ModelMessage.text(
                        ModelMessageRole.SYSTEM, "[" + prompt.layer() + "/" + prompt.role() + "] " + prompt.text())));
        Map<AgentRunId, Map<io.haifa.agent.core.tool.ToolCallId, ToolCall>> toolCallsByRun = new HashMap<>();
        for (ContextItem item : context.items()) {
            if (item.content() instanceof MessageContextContent message) {
                messages.addAll(mapMessage(runId, message.message(), toolCallsByRun, model, priorModelAssistants));
            } else if (item.content() instanceof MessageGroupContextContent group) {
                group.messages()
                        .forEach(message -> messages.addAll(
                                mapMessage(runId, message, toolCallsByRun, model, priorModelAssistants)));
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
        return canonicalizeToolProtocol(messages, priorModelAssistants, model);
    }

    private List<ModelMessage> canonicalizeToolProtocol(List<ModelMessage> messages) {
        return canonicalizeToolProtocol(messages, Set.of(), null);
    }

    /**
     * Keeps every provider tool-call group contiguous at the model boundary.
     *
     * <p>Runtime recovery, approval, steering, and other control messages may be persisted while tools execute. The
     * OpenAI tool protocol nevertheless requires the assistant tool-call message to be followed immediately by one
     * tool result for every provider correlation id. Control messages retain their relative order, but move behind
     * the completed tool group before the request is serialized.
     *
     * <p>When a later run uses another model binding, historical tool groups remain structured. Provider-specific
     * continuation data is removed earlier during message mapping, and provider correlation ids are deterministically
     * projected to transport-safe values without mutating persisted history. A malformed historical group receives a
     * synthetic structured result so it cannot poison the new request; an incomplete current-model group still fails
     * closed.
     */
    private List<ModelMessage> canonicalizeToolProtocol(
            List<ModelMessage> messages, Set<ModelMessage> priorModelAssistants, ResolvedModelSnapshot model) {
        List<ModelMessage> canonical = new ArrayList<>(messages.size());
        int index = 0;
        while (index < messages.size()) {
            ModelMessage message = messages.get(index++);
            if (message.role() != ModelMessageRole.ASSISTANT
                    || message.toolCalls().isEmpty()) {
                canonical.add(message);
                continue;
            }

            boolean isPrior = priorModelAssistants.contains(message);
            if (isPrior && requiresNeutralToolGroupSummary(model)) {
                Map<ProviderToolCallCorrelationId, ModelMessage> matchingResults = new LinkedHashMap<>();
                var pending = message.toolCalls().stream()
                        .map(ModelToolCall::providerCorrelationId)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
                List<ModelMessage> deferred = new ArrayList<>();
                while (!pending.isEmpty() && index < messages.size()) {
                    ModelMessage candidate = messages.get(index++);
                    if (candidate.role() == ModelMessageRole.TOOL
                            && candidate.providerCorrelationId().isPresent()
                            && pending.contains(
                                    candidate.providerCorrelationId().get())) {
                        ProviderToolCallCorrelationId corrId =
                                candidate.providerCorrelationId().get();
                        pending.remove(corrId);
                        matchingResults.put(corrId, candidate);
                    } else {
                        deferred.add(candidate);
                    }
                }
                String summaryText = renderToolGroupSummary(message, message.toolCalls(), matchingResults);
                canonical.add(ModelMessage.text(ModelMessageRole.ASSISTANT, summaryText));
                canonical.addAll(deferred);
                continue;
            }
            Map<ProviderToolCallCorrelationId, ProviderToolCallCorrelationId> projectedCorrelations = isPrior
                    ? message.toolCalls().stream()
                            .collect(Collectors.toUnmodifiableMap(
                                    ModelToolCall::providerCorrelationId,
                                    call -> projectHistoricalCorrelation(call.providerCorrelationId())))
                    : Map.of();
            canonical.add(isPrior ? projectHistoricalAssistant(message, projectedCorrelations) : message);

            var pending = message.toolCalls().stream()
                    .map(ModelToolCall::providerCorrelationId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            List<ModelMessage> deferred = new ArrayList<>();
            while (!pending.isEmpty() && index < messages.size()) {
                ModelMessage candidate = messages.get(index++);
                if (candidate.role() == ModelMessageRole.TOOL
                        && candidate.providerCorrelationId().isPresent()
                        && pending.contains(candidate.providerCorrelationId().get())) {
                    ProviderToolCallCorrelationId corrId =
                            candidate.providerCorrelationId().get();
                    pending.remove(corrId);
                    if (isPrior) {
                        canonical.add(projectHistoricalToolResult(candidate, projectedCorrelations.get(corrId)));
                    } else {
                        canonical.add(candidate);
                    }
                } else {
                    deferred.add(candidate);
                }
            }
            if (!pending.isEmpty()) {
                if (isPrior) {
                    pending.forEach(correlationId ->
                            canonical.add(missingHistoricalToolResult(projectedCorrelations.get(correlationId))));
                } else {
                    throw new IllegalStateException("model context contains an incomplete tool-call group");
                }
            }
            canonical.addAll(deferred);
        }
        return List.copyOf(canonical);
    }

    private String renderSummary(ConversationSummaryContent summary) {
        if (summary.renderedMarkdown().isPresent()) {
            return summary.renderedMarkdown().get();
        }
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
            ResolvedModelSnapshot model,
            Set<ModelMessage> priorModelAssistants) {
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
            if (isPriorModel(message, model)) {
                ModelMessage historicalAssistant = ModelMessage.assistant(text, mapped);
                priorModelAssistants.add(historicalAssistant);
                return List.of(historicalAssistant);
            }
            var continuation = state.continuationForMessage(message.id());
            if (continuation.isEmpty()) return List.of(ModelMessage.assistant(text, mapped));
            if (model == null) {
                throw new IllegalStateException("model snapshot is required to resolve provider continuation");
            }
            var correlations = mapped.stream()
                    .map(call -> call.providerCorrelationId().value())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            var record = continuation.orElseThrow();
            if (!record.providerId().equals(model.providerId().value())
                    || !record.modelId().equals(model.providerModelId())
                    || !record.configurationDigest().equals(model.configurationDigest())
                    || !record.toolCorrelationIds().equals(correlations)) {
                return List.of(ModelMessage.assistant(text, mapped));
            }
            return List.of(
                    ModelMessage.assistant(text, mapped, state.resolveContinuation(message.id(), model, correlations)));
        }
        if (message.role() == MessageRole.TOOL) {
            throw new IllegalStateException("tool message has no typed provider correlation");
        }
        List<ModelImagePart> mappedImages = renderImages(message.contents());
        List<ModelAudioPart> mappedAudios = renderAudios(message.contents());
        if (!mappedImages.isEmpty() || !mappedAudios.isEmpty()) {
            if (message.role() != MessageRole.USER) {
                throw new ContextBuildException(
                        ContextBuildFailure.UNSUPPORTED_CONTEXT_CONTENT,
                        "media inputs are only allowed on user messages");
            }
            return List.of(ModelMessage.user(text, mappedImages, mappedAudios));
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
                    && !(content instanceof StoredAudioContentPart)
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

    private List<ModelAudioPart> renderAudios(List<ContentPart> contents) {
        List<ModelAudioPart> values = new ArrayList<>();
        for (ContentPart content : contents) {
            if (content instanceof StoredAudioContentPart audio) values.add(audios.resolve(audio));
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

    private boolean isPriorModel(AgentMessage message, ResolvedModelSnapshot model) {
        if (model == null) {
            return false;
        }
        var continuation = state.continuationForMessage(message.id());
        if (continuation.isPresent()) {
            var record = continuation.orElseThrow();
            return !matchesModelBinding(record.providerId(), record.modelId(), record.configurationDigest(), model);
        }
        Object metaProvider = message.metadata().get("providerId");
        Object metaModel = message.metadata().get("modelId");
        Object metaConfiguration = message.metadata().get("configurationDigest");
        if (metaProvider instanceof String providerId
                && !providerId.isBlank()
                && !providerId.equals(model.providerId().value())) {
            return true;
        }
        if (metaModel instanceof String modelId && !modelId.isBlank() && !modelId.equals(model.providerModelId())) {
            return true;
        }
        if (metaConfiguration instanceof String configurationDigest
                && !configurationDigest.isBlank()
                && !configurationDigest.equals(model.configurationDigest())) {
            return true;
        }
        if (message.runId().isPresent()) {
            var continuations = state.modelContinuations(message.runId().get());
            if (!continuations.isEmpty()) {
                var record = continuations.getFirst();
                return !matchesModelBinding(record.providerId(), record.modelId(), record.configurationDigest(), model);
            }
        }
        return false;
    }

    private boolean matchesModelBinding(
            String providerId, String modelId, String configurationDigest, ResolvedModelSnapshot model) {
        return providerId.equals(model.providerId().value())
                && modelId.equals(model.providerModelId())
                && configurationDigest.equals(model.configurationDigest());
    }

    private ModelMessage projectHistoricalAssistant(
            ModelMessage assistant,
            Map<ProviderToolCallCorrelationId, ProviderToolCallCorrelationId> projectedCorrelations) {
        List<ModelToolCall> projectedCalls = assistant.toolCalls().stream()
                .map(call -> new ModelToolCall(
                        projectedCorrelations.get(call.providerCorrelationId()), call.name(), call.arguments()))
                .toList();
        return ModelMessage.assistant(assistant.content(), projectedCalls);
    }

    private ModelMessage projectHistoricalToolResult(
            ModelMessage result, ProviderToolCallCorrelationId projectedCorrelation) {
        return ModelMessage.tool(
                projectedCorrelation, result.content(), result.toolResultData(), result.toolResultTruncated());
    }

    private ModelMessage missingHistoricalToolResult(ProviderToolCallCorrelationId projectedCorrelation) {
        return ModelMessage.tool(
                projectedCorrelation,
                "Historical tool result was not recorded.",
                Map.of("status", "UNKNOWN", "reasonCode", "HISTORICAL_TOOL_RESULT_MISSING"),
                false);
    }

    private ProviderToolCallCorrelationId projectHistoricalCorrelation(
            ProviderToolCallCorrelationId providerCorrelationId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(providerCorrelationId.value().getBytes(StandardCharsets.UTF_8));
            return new ProviderToolCallCorrelationId(
                    "haifa_handoff_" + HexFormat.of().formatHex(digest, 0, 20));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static boolean requiresNeutralToolGroupSummary(ResolvedModelSnapshot model) {
        if (model == null) {
            return false;
        }
        return ModelApiStyles.GOOGLE_GEMINI_GENERATE_CONTENT.equals(model.apiStyle())
                || "google-antigravity".equals(model.providerId().value());
    }

    private String renderToolGroupSummary(
            ModelMessage assistantMessage,
            List<ModelToolCall> toolCalls,
            Map<ProviderToolCallCorrelationId, ModelMessage> matchingResults) {
        List<String> lines = new ArrayList<>();
        if (!assistantMessage.content().isBlank()) {
            lines.add(assistantMessage.content().trim());
        }
        for (ModelToolCall call : toolCalls) {
            String argJson = formatArguments(call.arguments());
            lines.add("[tool-call: " + call.name() + " arguments: " + argJson + "]");
            ModelMessage resultMsg = matchingResults.get(call.providerCorrelationId());
            if (resultMsg != null) {
                lines.add("[tool-result: " + call.name() + "]");
                if (!resultMsg.content().isBlank()) {
                    lines.add(resultMsg.content().trim());
                }
                if (resultMsg.toolResultTruncated()) {
                    lines.add("[output truncated]");
                }
            } else {
                lines.add("[tool-result: " + call.name() + "]");
                lines.add("Historical tool result was not recorded.");
            }
        }
        return String.join("\n", lines);
    }

    private String formatArguments(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append("\"").append(escapeString(entry.getKey())).append("\": ");
            sb.append(formatValue(entry.getValue()));
        }
        sb.append("}");
        return sb.toString();
    }

    private String formatValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String s) {
            return "\"" + escapeString(s) + "\"";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append("\"")
                        .append(escapeString(String.valueOf(entry.getKey())))
                        .append("\": ");
                sb.append(formatValue(entry.getValue()));
            }
            sb.append("}");
            return sb.toString();
        }
        if (value instanceof Iterable<?> iterable) {
            StringBuilder sb = new StringBuilder();
            sb.append("[");
            boolean first = true;
            for (Object item : iterable) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append(formatValue(item));
            }
            sb.append("]");
            return sb.toString();
        }
        return "\"" + escapeString(String.valueOf(value)) + "\"";
    }

    private String escapeString(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
